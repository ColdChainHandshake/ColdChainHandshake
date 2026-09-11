package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.AlertDao
import com.coldchain.handshake.data.local.dao.HandoverDao
import com.coldchain.handshake.data.local.dao.ShipmentDao
import com.coldchain.handshake.data.local.dao.TemperatureEventDao
import com.coldchain.handshake.data.network.NetworkMonitor
import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.data.remote.dto.toRemoteDto
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Concrete implementation of the shared SyncService contract.
 * Orchestrates store-and-forward replication of local Room records to Supabase.
 * Enforces mutex concurrency guards, idempotent retry handling, and append-only telemetry invariants.
 */
class SyncServiceImpl(
    private val shipmentDao: ShipmentDao,
    private val temperatureEventDao: TemperatureEventDao,
    private val alertDao: AlertDao,
    private val handoverDao: HandoverDao,
    private val remoteDataSource: SupabaseRemoteDataSource,
    private val networkMonitor: NetworkMonitor,
    coroutineScope: CoroutineScope? = null
) : SyncService {

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    private val syncMutex = Mutex()

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

    private suspend fun executeSync(): Result<Unit> {
        val hasPending = hasPendingData()

        if (!isOnline()) {
            if (hasPending) {
                _syncStatus.value = SyncStatus.PENDING
            }
            return Result.failure(IllegalStateException("Device is offline. Local changes remain queued."))
        }

        if (!hasPending) {
            _syncStatus.value = SyncStatus.SYNCED
            return Result.success(Unit)
        }

        _syncStatus.value = SyncStatus.PENDING

        return try {
            // 1. Sync pending shipments
            val pendingShipments = shipmentDao.getPendingShipments()
            if (pendingShipments.isNotEmpty()) {
                val dtos = pendingShipments.map { it.toRemoteDto() }
                val result = remoteDataSource.upsertShipments(dtos)
                if (result.isSuccess) {
                    pendingShipments.forEach {
                        shipmentDao.updateSyncStatus(it.id, SyncStatus.SYNCED)
                    }

                } else {
                    pendingShipments.forEach {
                        shipmentDao.updateSyncStatus(it.id, SyncStatus.FAILED)
                    }
                    throw result.exceptionOrNull() ?: Exception("Shipments sync failed")
                }
            }


            // 2. Sync pending temperature events (APPEND-ONLY: telemetry fields are untouched)
            val pendingEvents = temperatureEventDao.getPendingEvents()
            if (pendingEvents.isNotEmpty()) {
                val dtos = pendingEvents.map { it.toRemoteDto() }
                val result = remoteDataSource.upsertTemperatureEvents(dtos)
                if (result.isSuccess) {
                    val ids = pendingEvents.map { it.id }
                    temperatureEventDao.updateSyncStatuses(ids, SyncStatus.SYNCED)
                } else {
                    val ids = pendingEvents.map { it.id }
                    temperatureEventDao.updateSyncStatuses(ids, SyncStatus.FAILED)
                    throw result.exceptionOrNull() ?: Exception("Temperature events sync failed")
                }
            }

            // 3. Sync pending alerts
            val pendingAlerts = alertDao.getPendingAlerts()
            if (pendingAlerts.isNotEmpty()) {
                val dtos = pendingAlerts.map { it.toRemoteDto() }
                val result = remoteDataSource.upsertAlerts(dtos)
                if (result.isSuccess) {
                    pendingAlerts.forEach {
                        alertDao.updateSyncStatus(it.id, SyncStatus.SYNCED)
                    }
                } else {
                    pendingAlerts.forEach {
                        alertDao.updateSyncStatus(it.id, SyncStatus.FAILED)
                    }
                    throw result.exceptionOrNull() ?: Exception("Alerts sync failed")
                }
            }

            // 4. Sync pending handovers
            val pendingHandovers = handoverDao.getPendingHandovers()
            if (pendingHandovers.isNotEmpty()) {
                for (handover in pendingHandovers) {
                    val result = remoteDataSource.upsertHandover(handover.toRemoteDto())
                    if (result.isSuccess) {
                        handoverDao.updateSyncStatus(handover.id, SyncStatus.SYNCED)
                    } else {
                        handoverDao.updateSyncStatus(handover.id, SyncStatus.FAILED)
                        throw result.exceptionOrNull() ?: Exception("Handover sync failed")
                    }
                }
            }

            _syncStatus.value = SyncStatus.SYNCED
            Result.success(Unit)
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.FAILED
            Result.failure(e)
        }
    }


    private suspend fun hasPendingData(): Boolean {
        return shipmentDao.getPendingShipments().isNotEmpty() ||
                temperatureEventDao.getPendingEvents().isNotEmpty() ||
                alertDao.getPendingAlerts().isNotEmpty() ||
                handoverDao.getPendingHandovers().isNotEmpty()
    }
}
