package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.AlertDao
import com.coldchain.handshake.data.local.dao.GpsPointDao
import com.coldchain.handshake.data.local.dao.HandoverDao
import com.coldchain.handshake.data.local.dao.ShipmentDao
import com.coldchain.handshake.data.local.dao.TemperatureEventDao
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.network.NetworkMonitor
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends what this phone recorded to the server's functions: readings through [ReadingUploader], then
 * locations, alerts and any handover confirmation that couldn't be sent at the time.
 *
 * Only a shipment the server created is ever sent. One made in the debug build's local mode has no
 * server genesis and stays on the phone. A refusal from the server is final for that record: it's
 * marked [SyncStatus.REJECTED] and reported, never retried in a loop.
 */
class SyncServiceImpl(
    private val shipmentDao: ShipmentDao,
    private val temperatureEventDao: TemperatureEventDao,
    private val alertDao: AlertDao,
    private val handoverDao: HandoverDao,
    private val gpsPointDao: GpsPointDao,
    private val remote: ColdChainRemote,
    private val uploader: ReadingUploader,
    private val account: AccountRepository,
    private val networkMonitor: NetworkMonitor,
    private val deviceId: () -> String,
    coroutineScope: CoroutineScope? = null
) : SyncService {

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    private val _lastRejection = MutableStateFlow<String?>(null)
    private val syncMutex = Mutex()

    @Volatile
    private var deviceRegistered = false

    init {
        // Automatically attempt synchronization when network connectivity is restored
        coroutineScope?.launch {
            networkMonitor.observeNetworkState().collect { isConnected ->
                if (isConnected && hasPendingData()) {
                    syncPendingData()
                }
            }
        }
    }

    override fun isOnline(): Boolean = networkMonitor.isOnline()

    override fun getSyncStatus(): Flow<SyncStatus> = _syncStatus.asStateFlow()

    override fun getLastRejection(): Flow<String?> = _lastRejection.asStateFlow()

    // Under the sync lock, so a point being sent right now is settled first, then deleted.
    override suspend fun forgetSentLocation(shipmentId: String) {
        syncMutex.withLock { gpsPointDao.deleteSettled(shipmentId) }
    }

    override suspend fun syncPendingData(): Result<Unit> {
        // Prevent concurrent sync routines from processing overlapping batches
        if (!syncMutex.tryLock()) {
            return Result.success(Unit)
        }
        return try {
            executeSync()
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Unlike [syncPendingData], this waits for a sync already running, because a handover needs these
     * readings on the server before anyone confirms.
     */
    override suspend fun uploadReadings(shipmentId: String): Result<Unit> = syncMutex.withLock {
        if (!isSignedIn()) return@withLock Result.failure(IllegalStateException("Sign in to send readings."))
        registerDeviceOnce().getOrElse { return@withLock Result.failure(it) }
        uploadShipment(shipmentId)
    }

    private fun isSignedIn() = account.state.value is AccountState.Ready

    private suspend fun registerDeviceOnce(): Result<Unit> {
        if (deviceRegistered) return Result.success(Unit)
        return account.registerDevice().onSuccess { deviceRegistered = true }
    }

    private suspend fun executeSync(): Result<Unit> {
        // Nothing on this phone belongs to the server until someone signs in.
        if (!isSignedIn() || !hasPendingData()) {
            _syncStatus.value = SyncStatus.SYNCED
            return Result.success(Unit)
        }

        _syncStatus.value = SyncStatus.PENDING
        registerDeviceOnce().getOrElse {
            _syncStatus.value = SyncStatus.FAILED
            return Result.failure(it)
        }

        val failures = mutableListOf<Throwable>()
        temperatureEventDao.getPendingEvents().map { it.shipmentId }.distinct().forEach { shipmentId ->
            uploadShipment(shipmentId).onFailure { failures += it }
        }
        syncLocations(failures)
        syncAlerts(failures)
        syncHandovers(failures)

        _syncStatus.value = if (failures.isEmpty()) SyncStatus.SYNCED else SyncStatus.FAILED
        return failures.firstOrNull()?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private suspend fun isOnServer(shipmentId: String): Boolean =
        shipmentDao.getShipmentDirect(shipmentId)?.genesisHash != null

    private suspend fun uploadShipment(shipmentId: String): Result<Unit> {
        if (!isOnServer(shipmentId)) return Result.success(Unit)

        val pending = temperatureEventDao.getPendingEvents()
            .filter { it.shipmentId == shipmentId }
            .map { it.toDomain() }
        if (pending.isEmpty()) return Result.success(Unit)

        return uploader.upload(shipmentId, pending).fold(
            onSuccess = { result ->
                temperatureEventDao.updateSyncStatuses(result.uploadedIds, SyncStatus.SYNCED)
                Result.success(Unit)
            },
            onFailure = { failure ->
                // A conflict or a refusal applies to everything after it too, because each reading
                // depends on the one before.
                temperatureEventDao.updateSyncStatuses(pending.map { it.id }, statusAfter(failure))
                Result.failure(failure)
            }
        )
    }

    /**
     * Sends the queued locations, oldest first, a shipment at a time. The server takes them only from the current
     * custodian, so a refusal means custody has moved on and they can never be sent: they are set aside, not shown as
     * a refusal of the person's readings, and not tried again.
     */
    private suspend fun syncLocations(failures: MutableList<Throwable>) {
        for ((shipmentId, queued) in gpsPointDao.getPending().groupBy { it.shipmentId }) {
            if (!isOnServer(shipmentId)) continue
            for (chunk in queued.chunked(LOCATIONS_PER_CALL)) {
                val ids = chunk.map { it.id }
                val failure = remote.appendGpsPoints(shipmentId, deviceId(), chunk.map { it.toDomain() }).exceptionOrNull()
                when {
                    failure == null -> gpsPointDao.updateSyncStatuses(ids, SyncStatus.SYNCED)
                    failure is RemoteRejection -> gpsPointDao.updateSyncStatuses(ids, SyncStatus.REJECTED)
                    else -> {
                        // No connection: the rest of this shipment's points would fail the same way.
                        gpsPointDao.updateSyncStatuses(ids, SyncStatus.FAILED)
                        failures += failure
                        break
                    }
                }
            }
        }
    }

    private suspend fun syncAlerts(failures: MutableList<Throwable>) {
        for (entity in alertDao.getPendingAlerts()) {
            val alert = entity.toDomain()
            if (!isOnServer(alert.shipmentId)) continue

            val result = remote.recordAlert(alert)
                .mapCatching {
                    if (alert.escalationLevel != EscalationLevel.WORKER) {
                        remote.escalateAlert(alert.id, alert.escalationLevel).getOrThrow()
                    }
                }
                .mapCatching { if (alert.acknowledged) remote.acknowledgeAlert(alert.id).getOrThrow() }

            val status = result.fold({ SyncStatus.SYNCED }, { statusAfter(it) })
            alertDao.updateSyncStatus(alert.id, status, System.currentTimeMillis())
            result.exceptionOrNull()?.let { failures += it }
        }
    }

    /**
     * Only a confirmation recorded without a connection is here, and that's never a PASS.
     */
    private suspend fun syncHandovers(failures: MutableList<Throwable>) {
        for (entity in handoverDao.getPendingHandovers()) {
            val handover = entity.toDomain()
            if (!isOnServer(handover.shipmentId)) continue

            // A PASS has to be made on the history the server holds at the time, which nobody reviewed here.
            // Anything else is sent against the current head on purpose, because it can only hold a
            // shipment back, and tying it to the old head would lose the finding once readings move on.
            if (handover.verdict == HandoverVerdict.PASS) {
                _lastRejection.value = "A PASS recorded without the server can't be sent. Confirm again while connected."
                handoverDao.updateSyncStatus(handover.id, SyncStatus.REJECTED, System.currentTimeMillis())
                continue
            }

            val result = runCatching {
                val head = remote.getChainHead(handover.shipmentId).getOrThrow()
                    ?: throw IllegalStateException("The server has no record of ${handover.shipmentId}.")
                val custody = remote.getCustody(handover.shipmentId).getOrThrow()
                    ?: throw IllegalStateException("Nobody has custody of ${handover.shipmentId}.")
                remote.confirmHandover(
                    HandoverConfirmation(
                        shipmentId = handover.shipmentId,
                        leg = custody.leg,
                        deviceId = deviceId(),
                        headHash = head.headHash,
                        verdict = handover.verdict,
                        reason = handover.reason,
                        vvmStage = handover.vvmStage,
                        shakeTest = handover.shakeTest
                    )
                ).getOrThrow()
            }

            val alreadyThere = (result.exceptionOrNull() as? RemoteRejection)?.code == RemoteRejection.ALREADY_CONFIRMED
            val status = if (result.isSuccess || alreadyThere) SyncStatus.SYNCED else statusAfter(result.exceptionOrNull()!!)
            handoverDao.updateSyncStatus(handover.id, status, System.currentTimeMillis())
            if (!alreadyThere) result.exceptionOrNull()?.let { failures += it }
        }
    }

    private fun statusAfter(failure: Throwable): SyncStatus {
        val permanent = failure is HistoryConflict ||
            (failure is RemoteRejection && failure.code != RemoteRejection.STALE_HEAD)
        if (permanent) _lastRejection.value = failure.message
        return if (permanent) SyncStatus.REJECTED else SyncStatus.FAILED
    }

    private suspend fun hasPendingData(): Boolean {
        return temperatureEventDao.getPendingEvents().isNotEmpty() ||
            gpsPointDao.getPending().isNotEmpty() ||
            alertDao.getPendingAlerts().isNotEmpty() ||
            handoverDao.getPendingHandovers().isNotEmpty()
    }

    private companion object {
        /** A day of points at one a minute is 1,440; each call sends at most this many. */
        const val LOCATIONS_PER_CALL = 200
    }
}
