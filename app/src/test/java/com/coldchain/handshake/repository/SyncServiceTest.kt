package com.coldchain.handshake.repository

import com.coldchain.handshake.data.network.NetworkMonitor
import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.data.remote.dto.RemoteAlertDto
import com.coldchain.handshake.data.remote.dto.RemoteHandoverDto
import com.coldchain.handshake.data.remote.dto.RemoteShipmentDto
import com.coldchain.handshake.data.remote.dto.RemoteTemperatureEventDto
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncServiceTest {

    private lateinit var shipmentDao: FakeShipmentDao
    private lateinit var temperatureEventDao: FakeTemperatureEventDao
    private lateinit var alertDao: FakeAlertDao
    private lateinit var handoverDao: FakeHandoverDao

    private lateinit var shipmentRepository: ShipmentRepository
    private lateinit var telemetryRepository: TelemetryRepository
    private lateinit var alertRepository: AlertRepository
    private lateinit var handoverRepository: HandoverRepository

    private lateinit var fakeRemoteDataSource: FakeSupabaseRemoteDataSource
    private lateinit var fakeNetworkMonitor: FakeNetworkMonitor
    private lateinit var syncService: SyncServiceImpl

    @Before
    fun setup() {
        shipmentDao = FakeShipmentDao()
        temperatureEventDao = FakeTemperatureEventDao()
        alertDao = FakeAlertDao()
        handoverDao = FakeHandoverDao()

        shipmentRepository = ShipmentRepositoryImpl(shipmentDao)
        telemetryRepository = TelemetryRepositoryImpl(temperatureEventDao)
        alertRepository = AlertRepositoryImpl(alertDao)
        handoverRepository = HandoverRepositoryImpl(handoverDao)

        fakeRemoteDataSource = FakeSupabaseRemoteDataSource()
        fakeNetworkMonitor = FakeNetworkMonitor(isInitiallyOnline = true)

        syncService = SyncServiceImpl(
            shipmentDao = shipmentDao,
            temperatureEventDao = temperatureEventDao,
            alertDao = alertDao,
            handoverDao = handoverDao,
            remoteDataSource = fakeRemoteDataSource,
            networkMonitor = fakeNetworkMonitor
        )
    }

    @Test
    fun test1_offlineLocalSaveRemainsAvailable() = runTest {
        // Device is offline
        fakeNetworkMonitor.setOnline(false)
        assertFalse(syncService.isOnline())

        val shipment = Shipment(
            id = "ship-offline-1",
            qrCode = "QR_OFFLINE_1",
            loggerId = "LOG-OFFLINE-1",
            origin = "Station Alpha",
            destination = "Station Beta",
            workerId = "WORKER-1",
            status = ShipmentStatus.CREATED
        )

        // Save locally
        val saveRes = shipmentRepository.saveShipment(shipment)
        assertTrue(saveRes.isSuccess)

        // Read locally - data must be instantly available
        val stored = shipmentRepository.getShipment("ship-offline-1").first()
        assertNotNull(stored)
        assertEquals("ship-offline-1", stored?.id)

        // Local record is marked PENDING in Room
        val pending = shipmentDao.getPendingShipments()
        assertEquals(1, pending.size)
        assertEquals(SyncStatus.PENDING, pending[0].syncStatus)
    }

    @Test
    fun test2_pendingRecordsAreDetected() = runTest {
        fakeNetworkMonitor.setOnline(false)

        val event = TemperatureEvent(
            id = "evt-detect-1",
            shipmentId = "ship-1",
            loggerId = "LOG-1",
            timestamp = 1000L,
            temperature = 4.5,
            previousHash = "0000",
            currentHash = "1111",
            syncStatus = SyncStatus.PENDING
        )
        telemetryRepository.saveTemperature(event)

        val syncResult = syncService.syncPendingData()
        assertTrue(syncResult.isFailure) // Offline failure
        assertEquals(SyncStatus.PENDING, syncService.getSyncStatus().first())

        val pendingEvents = temperatureEventDao.getPendingEvents()
        assertEquals(1, pendingEvents.size)
        assertEquals("evt-detect-1", pendingEvents[0].id)
    }

    @Test
    fun test3_successfulRemoteSyncChangesLocalStateToSynced() = runTest {
        fakeNetworkMonitor.setOnline(true)

        val shipment = Shipment(
            id = "ship-sync-1",
            qrCode = "QR_1",
            loggerId = "LOG-1",
            origin = "Origin",
            destination = "Destination",
            workerId = "W-1",
            status = ShipmentStatus.DISPATCHED
        )
        val event = TemperatureEvent(
            id = "evt-sync-1",
            shipmentId = "ship-sync-1",
            loggerId = "LOG-1",
            timestamp = 2000L,
            temperature = 5.2,
            previousHash = "HASH-PREV",
            currentHash = "HASH-CURR",
            syncStatus = SyncStatus.PENDING
        )

        shipmentRepository.saveShipment(shipment)
        telemetryRepository.saveTemperature(event)

        assertEquals(1, shipmentDao.getPendingShipments().size)
        assertEquals(1, temperatureEventDao.getPendingEvents().size)

        // Trigger sync
        val syncResult = syncService.syncPendingData()
        assertTrue(syncResult.isSuccess)

        // Verify status moved to SYNCED
        assertEquals(SyncStatus.SYNCED, syncService.getSyncStatus().first())
        assertEquals(0, shipmentDao.getPendingShipments().size)
        assertEquals(0, temperatureEventDao.getPendingEvents().size)

        val localEvent = temperatureEventDao.getTemperatures("ship-sync-1").first().first()
        assertEquals(SyncStatus.SYNCED, localEvent.syncStatus)

        // Verify remote mock received the records
        assertEquals(1, fakeRemoteDataSource.uploadedShipments.size)
        assertEquals(1, fakeRemoteDataSource.uploadedEvents.size)
    }

    @Test
    fun test4_failedSyncRemainsRetryable() = runTest {
        fakeNetworkMonitor.setOnline(true)
        fakeRemoteDataSource.shouldFail = true // Simulate cloud endpoint error

        val alert = Alert(
            id = "alt-fail-1",
            shipmentId = "ship-fail",
            type = AlertType.TEMPERATURE_BREACH,
            message = "Breach detected",
            timestamp = 3000L,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false
        )
        alertRepository.saveAlert(alert)

        // Attempt sync with failing remote
        val result = syncService.syncPendingData()
        assertTrue(result.isFailure)
        assertEquals(SyncStatus.FAILED, syncService.getSyncStatus().first())

        // Record must NOT be deleted, and must remain retryable
        val pendingAlerts = alertDao.getPendingAlerts()
        assertEquals(1, pendingAlerts.size)
        assertEquals("alt-fail-1", pendingAlerts[0].id)
        assertEquals(SyncStatus.FAILED, pendingAlerts[0].syncStatus)

        // Now restore remote and retry
        fakeRemoteDataSource.shouldFail = false
        val retryResult = syncService.syncPendingData()
        assertTrue(retryResult.isSuccess)
        assertEquals(SyncStatus.SYNCED, syncService.getSyncStatus().first())
        assertEquals(0, alertDao.getPendingAlerts().size)
    }

    @Test
    fun test5_networkRestorationAllowsPendingDataToSync() = runTest {
        // Start offline
        fakeNetworkMonitor.setOnline(false)

        val handover = Handover(
            id = "hnd-reconnect-1",
            shipmentId = "ship-reconnect",
            workerSigned = true,
            pharmacistSigned = true,
            integrityVerified = true,
            temperaturePassed = true,
            verdict = HandoverVerdict.PASS,
            timestamp = 4000L
        )
        handoverRepository.saveHandover(handover)

        // Attempt sync while offline -> fails
        val offlineResult = syncService.syncPendingData()
        assertTrue(offlineResult.isFailure)
        assertEquals(1, handoverDao.getPendingHandovers().size)

        // Network restored
        fakeNetworkMonitor.setOnline(true)
        assertTrue(syncService.isOnline())

        // Reconnect sync
        val onlineResult = syncService.syncPendingData()
        assertTrue(onlineResult.isSuccess)
        assertEquals(0, handoverDao.getPendingHandovers().size)
        assertEquals(1, fakeRemoteDataSource.uploadedHandovers.size)
    }

    @Test
    fun test6_retryingSameLogicalEventDoesNotCreateDuplicateLogicalRecords() = runTest {
        fakeNetworkMonitor.setOnline(true)

        val event = TemperatureEvent(
            id = "evt-idempotent-99",
            shipmentId = "ship-idem",
            loggerId = "LOG-IDEM",
            timestamp = 5000L,
            temperature = 4.0,
            previousHash = "H0",
            currentHash = "H1",
            syncStatus = SyncStatus.PENDING
        )
        telemetryRepository.saveTemperature(event)

        // Sync 1
        syncService.syncPendingData()
        assertEquals(1, fakeRemoteDataSource.uploadedEvents.size)

        // Force record back to PENDING to simulate a retry after network drop before ack
        temperatureEventDao.updateSyncStatus("evt-idempotent-99", SyncStatus.PENDING)

        // Sync 2 (Retry)
        syncService.syncPendingData()

        // Verify remote mock still only has 1 record for this ID (keyed by onConflict = "id")
        assertEquals(1, fakeRemoteDataSource.uploadedEvents.size)
        assertEquals("evt-idempotent-99", fakeRemoteDataSource.uploadedEvents.keys.first())
    }

    @Test
    fun test7_originalTemperatureTimestampAndHashesArePreserved() = runTest {
        fakeNetworkMonitor.setOnline(true)

        val exactTimestamp = 1719999999123L
        val exactPreviousHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val exactCurrentHash = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

        val event = TemperatureEvent(
            id = "evt-hash-preserve",
            shipmentId = "ship-hash",
            loggerId = "LOG-H",
            timestamp = exactTimestamp,
            temperature = 3.85,
            previousHash = exactPreviousHash,
            currentHash = exactCurrentHash,
            syncStatus = SyncStatus.PENDING
        )
        telemetryRepository.saveTemperature(event)

        syncService.syncPendingData()

        // Verify remote received exact byte-for-byte matching values
        val remoteDto = fakeRemoteDataSource.uploadedEvents["evt-hash-preserve"]
        assertNotNull(remoteDto)
        assertEquals(exactTimestamp, remoteDto?.timestamp)
        assertEquals(exactPreviousHash, remoteDto?.previousHash)
        assertEquals(exactCurrentHash, remoteDto?.currentHash)
        assertEquals(3.85, remoteDto?.temperature ?: 0.0, 0.0001)

        // Verify local Room record retains exact values and only syncStatus changed
        val localEvent = telemetryRepository.getTemperatures("ship-hash").first().first()
        assertEquals(exactTimestamp, localEvent.timestamp)
        assertEquals(exactPreviousHash, localEvent.previousHash)
        assertEquals(exactCurrentHash, localEvent.currentHash)
        assertEquals(3.85, localEvent.temperature, 0.0001)
        assertEquals(SyncStatus.SYNCED, localEvent.syncStatus)
    }

    @Test
    fun test8_concurrentSyncCallsDoNotDuplicateProcessing() = runTest {
        fakeNetworkMonitor.setOnline(true)

        val shipment = Shipment(
            id = "ship-concurrent-1",
            qrCode = "QR_C",
            loggerId = "LOG_C",
            origin = "Origin",
            destination = "Dest",
            workerId = "W",
            status = ShipmentStatus.CREATED
        )
        shipmentRepository.saveShipment(shipment)

        // Simulate artificial delay in remote upsert to create concurrency window
        fakeRemoteDataSource.remoteDelayMs = 50L

        // Trigger two concurrent syncs
        val syncJob1 = async { syncService.syncPendingData() }
        val syncJob2 = async { syncService.syncPendingData() }

        val res1 = syncJob1.await()
        val res2 = syncJob2.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)

        // Verify remote was upserted without duplicate entries
        assertEquals(1, fakeRemoteDataSource.uploadedShipments.size)
        assertEquals(SyncStatus.SYNCED, syncService.getSyncStatus().first())
    }

    @Test
    fun test9_automaticSyncTriggeredOnNetworkRestoredWithoutManualCall() = runTest {
        // Start completely offline
        val offlineMonitor = FakeNetworkMonitor(isInitiallyOnline = false)
        val testRemote = FakeSupabaseRemoteDataSource()

        // Create a real background scope matching production Dispatchers pattern
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

        val autoSyncService = SyncServiceImpl(
            shipmentDao = shipmentDao,
            temperatureEventDao = temperatureEventDao,
            alertDao = alertDao,
            handoverDao = handoverDao,
            remoteDataSource = testRemote,
            networkMonitor = offlineMonitor,
            coroutineScope = testScope
        )

        val shipment = Shipment(
            id = "ship-auto-sync-1",
            qrCode = "QR_AUTO",
            loggerId = "LOG_AUTO",
            origin = "Origin",
            destination = "Dest",
            workerId = "WORKER_AUTO",
            status = ShipmentStatus.DISPATCHED
        )
        val event = TemperatureEvent(
            id = "evt-auto-sync-1",
            shipmentId = "ship-auto-sync-1",
            loggerId = "LOG_AUTO",
            timestamp = 6000L,
            temperature = 4.2,
            previousHash = "PREV_H",
            currentHash = "CURR_H",
            syncStatus = SyncStatus.PENDING
        )

        // 1. Pending records exist while offline
        shipmentRepository.saveShipment(shipment)
        telemetryRepository.saveTemperature(event)

        assertEquals(1, shipmentDao.getPendingShipments().size)
        assertEquals(1, temperatureEventDao.getPendingEvents().size)
        val storedEventBefore = telemetryRepository.getTemperatures("ship-auto-sync-1").first().first()
        assertEquals(SyncStatus.PENDING, storedEventBefore.syncStatus)
        assertTrue(testRemote.uploadedShipments.isEmpty())
        assertTrue(testRemote.uploadedEvents.isEmpty())

        // 2. Network changes from false -> true
        offlineMonitor.setOnline(true)

        // 3. Automatic sync happens WITHOUT calling syncPendingData() manually
        var attempts = 0
        while ((testRemote.uploadedShipments.isEmpty() || testRemote.uploadedEvents.isEmpty()) && attempts < 50) {
            delay(50)
            attempts++
        }
        testScope.cancel()

        // 4. The record becomes SYNCED and the fake remote receives it
        assertEquals(0, shipmentDao.getPendingShipments().size)
        assertEquals(0, temperatureEventDao.getPendingEvents().size)
        val storedEventAfter = telemetryRepository.getTemperatures("ship-auto-sync-1").first().first()
        assertEquals(SyncStatus.SYNCED, storedEventAfter.syncStatus)
        assertEquals(SyncStatus.SYNCED, autoSyncService.getSyncStatus().first())

        assertEquals(1, testRemote.uploadedShipments.size)
        assertEquals(1, testRemote.uploadedEvents.size)
        val remoteShipment = testRemote.uploadedShipments["ship-auto-sync-1"]
        val remoteEvent = testRemote.uploadedEvents["evt-auto-sync-1"]
        assertNotNull(remoteShipment)
        assertNotNull(remoteEvent)
        assertEquals("ship-auto-sync-1", remoteShipment?.id)
        assertEquals("evt-auto-sync-1", remoteEvent?.id)
    }

    @Test
    fun test10_repositoryProviderResetCancelsSyncScope() {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        RepositoryProvider.syncScope = testScope
        assertTrue(testScope.isActive)

        RepositoryProvider.reset()

        assertNull(RepositoryProvider.syncScope)
        assertFalse(testScope.isActive)
    }
}


// ---------------------------------------------------------------------------
// Fake In-Memory Implementations for Testing
// ---------------------------------------------------------------------------

class FakeNetworkMonitor(isInitiallyOnline: Boolean = true) : NetworkMonitor {
    private var online: Boolean = isInitiallyOnline
    private val _flow = MutableSharedFlow<Boolean>(replay = 1)

    init {
        _flow.tryEmit(isInitiallyOnline)
    }

    suspend fun setOnline(online: Boolean) {
        this.online = online
        _flow.emit(online)
    }

    override fun isOnline(): Boolean = online

    override fun observeNetworkState(): Flow<Boolean> = _flow
}


class FakeSupabaseRemoteDataSource : SupabaseRemoteDataSource() {
    val uploadedShipments = LinkedHashMap<String, RemoteShipmentDto>()
    val uploadedEvents = LinkedHashMap<String, RemoteTemperatureEventDto>()
    val uploadedAlerts = LinkedHashMap<String, RemoteAlertDto>()
    val uploadedHandovers = LinkedHashMap<String, RemoteHandoverDto>()

    var shouldFail: Boolean = false
    var remoteDelayMs: Long = 0L

    override suspend fun upsertShipments(shipments: List<RemoteShipmentDto>): Result<Unit> {
        if (remoteDelayMs > 0) delay(remoteDelayMs)
        if (shouldFail) return Result.failure(Exception("Simulated 500 Server Error"))
        shipments.forEach { uploadedShipments[it.id] = it }
        return Result.success(Unit)
    }

    override suspend fun upsertTemperatureEvents(events: List<RemoteTemperatureEventDto>): Result<Unit> {
        if (remoteDelayMs > 0) delay(remoteDelayMs)
        if (shouldFail) return Result.failure(Exception("Simulated 500 Server Error"))
        // Upsert by logical ID
        events.forEach { uploadedEvents[it.id] = it }
        return Result.success(Unit)
    }

    override suspend fun upsertAlerts(alerts: List<RemoteAlertDto>): Result<Unit> {
        if (remoteDelayMs > 0) delay(remoteDelayMs)
        if (shouldFail) return Result.failure(Exception("Simulated 500 Server Error"))
        alerts.forEach { uploadedAlerts[it.id] = it }
        return Result.success(Unit)
    }

    override suspend fun upsertHandover(handover: RemoteHandoverDto): Result<Unit> {
        if (remoteDelayMs > 0) delay(remoteDelayMs)
        if (shouldFail) return Result.failure(Exception("Simulated 500 Server Error"))
        uploadedHandovers[handover.id] = handover
        return Result.success(Unit)
    }
}
