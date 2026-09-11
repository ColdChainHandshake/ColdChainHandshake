package com.coldchain.handshake.data.remote

import com.coldchain.handshake.data.network.NetworkMonitor
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
import com.coldchain.handshake.repository.FakeAlertDao
import com.coldchain.handshake.repository.FakeHandoverDao
import com.coldchain.handshake.repository.FakeShipmentDao
import com.coldchain.handshake.repository.FakeTemperatureEventDao
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LIVE Integration Test Suite executing REAL cloud writes and reads
 * against the live Supabase instance using production SupabaseRemoteDataSource.
 */
class LiveSupabaseIntegrationTest {

    private lateinit var realDataSource: SupabaseRemoteDataSource
    private val testPrefix = "live-test-"

    private val testShipmentId = "${testPrefix}ship-001"
    private val testEventId = "${testPrefix}evt-001"
    private val testAlertId = "${testPrefix}alt-001"
    private val testHandoverId = "${testPrefix}hnd-001"

    private val testAutoShipmentId = "${testPrefix}ship-auto-001"
    private val testAutoEventId = "${testPrefix}evt-auto-001"
    private val testFailShipmentId = "${testPrefix}ship-fail-001"

    @Before
    fun setup() {
        realDataSource = SupabaseRemoteDataSource()
        cleanupTestData()
    }

    @After
    fun tearDown() {
        cleanupTestData()
    }

    private fun cleanupTestData() = runBlocking {
        // Delete all temporary test records from live Supabase
        realDataSource.deleteTemperatureEvent(testEventId)
        realDataSource.deleteTemperatureEvent(testAutoEventId)
        realDataSource.deleteAlert(testAlertId)
        realDataSource.deleteHandover(testHandoverId)
        realDataSource.deleteShipment(testShipmentId)
        realDataSource.deleteShipment(testAutoShipmentId)
        realDataSource.deleteShipment(testFailShipmentId)
    }

    @Test
    fun test1_verifySelectOnAllFourTables() = runBlocking {
        // SELECT on public.shipments
        val shipmentsResult = realDataSource.getShipments()
        assertTrue("SELECT shipments should succeed", shipmentsResult.isSuccess)

        // SELECT on public.temperature_events
        val eventsResult = realDataSource.getTemperatureEvents("non-existent-id")
        assertTrue("SELECT temperature_events should succeed", eventsResult.isSuccess)

        // SELECT on public.alerts
        val alertsResult = realDataSource.getAlerts("non-existent-id")
        assertTrue("SELECT alerts should succeed", alertsResult.isSuccess)

        // SELECT on public.handovers
        val handoverResult = realDataSource.getHandover("non-existent-id")
        assertTrue("SELECT handovers should succeed", handoverResult.isSuccess)
    }

    @Test
    fun test2_liveShipmentUpsertAndReadBack() = runBlocking {
        val shipmentDto = RemoteShipmentDto(
            id = testShipmentId,
            qrCode = "QR_LIVE_SHIP_001",
            loggerId = "LOG_LIVE_001",
            origin = "Central Cold Store",
            destination = "Regional Clinic 5",
            workerId = "WORKER_LIVE_99",
            status = ShipmentStatus.DISPATCHED.name
        )

        // 1. Write to live Supabase
        val upsertRes = realDataSource.upsertShipment(shipmentDto)
        assertTrue("Live shipment upsert should succeed", upsertRes.isSuccess)

        // 2. Read back from live Supabase
        val readList = realDataSource.getShipments().getOrThrow()
        val found = readList.find { it.id == testShipmentId }
        assertNotNull("Inserted shipment should be found in live Supabase", found)
        assertEquals(testShipmentId, found?.id)
        assertEquals("QR_LIVE_SHIP_001", found?.qrCode)
        assertEquals("Central Cold Store", found?.origin)
        assertEquals("Regional Clinic 5", found?.destination)
        assertEquals("DISPATCHED", found?.status)
    }

    @Test
    fun test3_liveTemperatureEventUpsertAndReadBack_preservesExactTelemetryFields() = runBlocking {
        val exactTimestamp = 1719999123456L
        val exactPreviousHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val exactCurrentHash = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

        val eventDto = RemoteTemperatureEventDto(
            id = testEventId,
            shipmentId = testShipmentId,
            loggerId = "LOG_LIVE_001",
            timestamp = exactTimestamp,
            temperature = 4.35,
            previousHash = exactPreviousHash,
            currentHash = exactCurrentHash,
            syncStatus = "SYNCED"
        )

        // Write to live Supabase
        val upsertRes = realDataSource.upsertTemperatureEvent(eventDto)
        assertTrue("Live temperature event upsert should succeed", upsertRes.isSuccess)

        // Read back from live Supabase
        val events = realDataSource.getTemperatureEvents(testShipmentId).getOrThrow()
        val found = events.find { it.id == testEventId }
        assertNotNull("Inserted temperature event should be found in live Supabase", found)
        assertEquals(exactTimestamp, found?.timestamp)
        assertEquals(4.35, found?.temperature ?: 0.0, 0.0001)
        assertEquals(exactPreviousHash, found?.previousHash)
        assertEquals(exactCurrentHash, found?.currentHash)
    }

    @Test
    fun test4_liveAlertUpsertAndReadBack() = runBlocking {
        val alertDto = RemoteAlertDto(
            id = testAlertId,
            shipmentId = testShipmentId,
            type = AlertType.TEMPERATURE_BREACH.name,
            message = "Live thermal excursion breach alert",
            timestamp = 1719999200000L,
            escalationLevel = EscalationLevel.SUPERVISOR.name,
            acknowledged = false
        )

        val upsertRes = realDataSource.upsertAlert(alertDto)
        assertTrue("Live alert upsert should succeed", upsertRes.isSuccess)

        val alerts = realDataSource.getAlerts(testShipmentId).getOrThrow()
        val found = alerts.find { it.id == testAlertId }
        assertNotNull("Inserted alert should be found in live Supabase", found)
        assertEquals(testAlertId, found?.id)
        assertEquals("TEMPERATURE_BREACH", found?.type)
        assertEquals("SUPERVISOR", found?.escalationLevel)
        assertEquals(false, found?.acknowledged)
    }

    @Test
    fun test5_liveHandoverUpsertAndReadBack() = runBlocking {
        val handoverDto = RemoteHandoverDto(
            id = testHandoverId,
            shipmentId = testShipmentId,
            workerSigned = true,
            pharmacistSigned = true,
            integrityVerified = true,
            temperaturePassed = true,
            verdict = HandoverVerdict.PASS.name,
            timestamp = 1719999300000L
        )

        val upsertRes = realDataSource.upsertHandover(handoverDto)
        assertTrue("Live handover upsert should succeed", upsertRes.isSuccess)

        val found = realDataSource.getHandover(testShipmentId).getOrThrow()
        assertNotNull("Inserted handover should be found in live Supabase", found)
        assertEquals(testHandoverId, found?.id)
        assertEquals(testShipmentId, found?.shipmentId)
        assertEquals("PASS", found?.verdict)
        assertEquals(true, found?.workerSigned)
        assertEquals(true, found?.integrityVerified)
    }

    @Test
    fun test6_retryExactSameIds_doesNotDuplicateRecords() = runBlocking {
        val shipmentDto = RemoteShipmentDto(
            id = testShipmentId,
            qrCode = "QR_LIVE_SHIP_001",
            loggerId = "LOG_LIVE_001",
            origin = "Origin",
            destination = "Destination",
            workerId = "WRK",
            status = "CREATED"
        )

        // Upsert 1
        realDataSource.upsertShipment(shipmentDto)

        // Upsert 2 (Retry with same ID)
        val retryRes = realDataSource.upsertShipment(shipmentDto)
        assertTrue("Retry upsert should succeed", retryRes.isSuccess)

        // Verify count of rows with this ID is exactly 1
        val all = realDataSource.getShipments().getOrThrow()
        val matching = all.filter { it.id == testShipmentId }
        assertEquals("Idempotent upsert must not create duplicate records", 1, matching.size)
    }

    @Test
    fun test7_realOfflineAndReconnectAutomaticSync() = runBlocking {
        val shipmentDao = FakeShipmentDao()
        val temperatureDao = FakeTemperatureEventDao()
        val alertDao = FakeAlertDao()
        val handoverDao = FakeHandoverDao()

        val shipmentRepo = ShipmentRepositoryImpl(shipmentDao)
        val telemetryRepo = TelemetryRepositoryImpl(temperatureDao)

        // Start offline
        val offlineMonitor = LiveTestNetworkMonitor(isInitiallyOnline = false)
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val syncService = SyncServiceImpl(
            shipmentDao = shipmentDao,
            temperatureEventDao = temperatureDao,
            alertDao = alertDao,
            handoverDao = handoverDao,
            remoteDataSource = realDataSource, // REAL live Supabase datasource!
            networkMonitor = offlineMonitor,
            coroutineScope = backgroundScope
        )

        // 1. Save shipment and temperature event locally while offline
        val shipment = Shipment(
            id = testAutoShipmentId,
            qrCode = "QR_AUTO",
            loggerId = "LOG_AUTO",
            origin = "Station 1",
            destination = "Station 2",
            workerId = "WORKER_AUTO",
            status = ShipmentStatus.DISPATCHED
        )
        val event = TemperatureEvent(
            id = testAutoEventId,
            shipmentId = testAutoShipmentId,
            loggerId = "LOG_AUTO",
            timestamp = 1719999555000L,
            temperature = 3.9,
            previousHash = "0000",
            currentHash = "1111",
            syncStatus = SyncStatus.PENDING
        )

        shipmentRepo.saveShipment(shipment)
        telemetryRepo.saveTemperature(event)

        assertEquals(1, shipmentDao.getPendingShipments().size)
        assertEquals(1, temperatureDao.getPendingEvents().size)

        // 2. Restore network connectivity
        offlineMonitor.setOnline(true)

        // 3. Await automatic reconnect sync without manual syncPendingData() call
        var attempts = 0
        var remoteConfirmed = false
        while (!remoteConfirmed && attempts < 50) {
            delay(200)
            attempts++
            val shipments = realDataSource.getShipments().getOrNull()
            if (shipments?.any { it.id == testAutoShipmentId } == true) {
                remoteConfirmed = true
            }
        }
        backgroundScope.cancel()

        assertTrue("Real Supabase must receive data via automatic reconnect sync", remoteConfirmed)
        assertEquals(0, shipmentDao.getPendingShipments().size)
        assertEquals(0, temperatureDao.getPendingEvents().size)
        assertEquals(SyncStatus.SYNCED, telemetryRepo.getTemperatures(testAutoShipmentId).first().first().syncStatus)
    }

    @Test
    fun test8_realFailureAndRetryBehavior() = runBlocking {
        val shipmentDao = FakeShipmentDao()
        val temperatureDao = FakeTemperatureEventDao()
        val alertDao = FakeAlertDao()
        val handoverDao = FakeHandoverDao()

        val shipmentRepo = ShipmentRepositoryImpl(shipmentDao)
        val offlineMonitor = LiveTestNetworkMonitor(isInitiallyOnline = false)

        val syncService = SyncServiceImpl(
            shipmentDao = shipmentDao,
            temperatureEventDao = temperatureDao,
            alertDao = alertDao,
            handoverDao = handoverDao,
            remoteDataSource = realDataSource,
            networkMonitor = offlineMonitor
        )

        val shipment = Shipment(
            id = testFailShipmentId,
            qrCode = "QR_FAIL",
            loggerId = "LOG_FAIL",
            origin = "Origin",
            destination = "Dest",
            workerId = "WRK",
            status = ShipmentStatus.CREATED
        )
        shipmentRepo.saveShipment(shipment)

        // Offline attempt fails
        val failResult = syncService.syncPendingData()
        assertTrue("Sync while offline should fail gracefully", failResult.isFailure)
        assertEquals(1, shipmentDao.getPendingShipments().size)

        // Online retry succeeds
        offlineMonitor.setOnline(true)
        val retryResult = syncService.syncPendingData()
        assertTrue("Retry when online should succeed against real Supabase", retryResult.isSuccess)
        assertEquals(0, shipmentDao.getPendingShipments().size)

        // Confirm real Supabase has it
        val remoteList = realDataSource.getShipments().getOrThrow()
        assertTrue(remoteList.any { it.id == testFailShipmentId })
    }
}

class LiveTestNetworkMonitor(isInitiallyOnline: Boolean = true) : NetworkMonitor {
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
