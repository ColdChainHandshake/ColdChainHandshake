package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.AlertDao
import com.coldchain.handshake.data.local.dao.HandoverDao
import com.coldchain.handshake.data.local.dao.ShipmentDao
import com.coldchain.handshake.data.local.dao.TemperatureEventDao
import com.coldchain.handshake.data.local.entities.AlertEntity
import com.coldchain.handshake.data.local.entities.HandoverEntity
import com.coldchain.handshake.data.local.entities.ShipmentEntity
import com.coldchain.handshake.data.local.entities.TemperatureEventEntity
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepositoryTest {

    private lateinit var shipmentDao: FakeShipmentDao
    private lateinit var temperatureEventDao: FakeTemperatureEventDao
    private lateinit var alertDao: FakeAlertDao
    private lateinit var handoverDao: FakeHandoverDao

    private lateinit var shipmentRepository: ShipmentRepository
    private lateinit var telemetryRepository: TelemetryRepository
    private lateinit var alertRepository: AlertRepository
    private lateinit var handoverRepository: HandoverRepository

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
    }

    @Test
    fun testShipmentSaveAndRead() = runTest {
        val shipment = Shipment(
            id = "ship-001",
            qrCode = "QR_PAYLOAD_001",
            loggerId = "LOGGER-7",
            origin = "Munich Distribution Hub",
            destination = "Clinic Station 4",
            workerId = "WRK-007",
            status = ShipmentStatus.DISPATCHED
        )

        val saveResult = shipmentRepository.saveShipment(shipment)
        assertTrue(saveResult.isSuccess)

        val retrieved = shipmentRepository.getShipment("ship-001").first()
        assertNotNull(retrieved)
        assertEquals(shipment.id, retrieved?.id)
        assertEquals(shipment.qrCode, retrieved?.qrCode)
        assertEquals(shipment.loggerId, retrieved?.loggerId)
        assertEquals(shipment.origin, retrieved?.origin)
        assertEquals(shipment.destination, retrieved?.destination)
        assertEquals(shipment.workerId, retrieved?.workerId)
        assertEquals(ShipmentStatus.DISPATCHED, retrieved?.status)
    }

    @Test
    fun testShipmentList() = runTest {
        val shipment1 = Shipment(
            id = "ship-101",
            qrCode = "QR_101",
            loggerId = "LOG_A",
            origin = "Hub A",
            destination = "Hub B",
            workerId = "W1",
            status = ShipmentStatus.CREATED
        )
        val shipment2 = Shipment(
            id = "ship-102",
            qrCode = "QR_102",
            loggerId = "LOG_B",
            origin = "Hub C",
            destination = "Hub D",
            workerId = "W2",
            status = ShipmentStatus.IN_TRANSIT
        )

        shipmentRepository.saveShipment(shipment1)
        shipmentRepository.saveShipment(shipment2)

        val list = shipmentRepository.getAllShipments().first()
        assertEquals(2, list.size)
        assertTrue(list.any { it.id == "ship-101" })
        assertTrue(list.any { it.id == "ship-102" })
    }

    @Test
    fun testTemperatureSaveAndRead() = runTest {
        val event1 = TemperatureEvent(
            id = "evt-1",
            shipmentId = "ship-200",
            loggerId = "LOG-200",
            timestamp = 1000L,
            temperature = 4.2,
            previousHash = "GENESIS",
            currentHash = "HASH-1",
            syncStatus = SyncStatus.PENDING
        )
        val event2 = TemperatureEvent(
            id = "evt-2",
            shipmentId = "ship-200",
            loggerId = "LOG-200",
            timestamp = 2000L,
            temperature = 4.8,
            previousHash = "HASH-1",
            currentHash = "HASH-2",
            syncStatus = SyncStatus.PENDING
        )

        val res1 = telemetryRepository.saveTemperature(event1)
        val res2 = telemetryRepository.saveTemperature(event2)
        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)

        val events = telemetryRepository.getTemperatures("ship-200").first()
        assertEquals(2, events.size)
        assertEquals("evt-1", events[0].id)
        assertEquals(4.2, events[0].temperature, 0.001)
        assertEquals("evt-2", events[1].id)
        assertEquals(4.8, events[1].temperature, 0.001)
    }

    @Test
    fun testTemperatureAppendOnly_doesNotOverwriteExistingEvent() = runTest {
        val originalEvent = TemperatureEvent(
            id = "evt-immutable",
            shipmentId = "ship-300",
            loggerId = "LOG-300",
            timestamp = 5000L,
            temperature = 5.0,
            previousHash = "PREV-A",
            currentHash = "CURR-A",
            syncStatus = SyncStatus.PENDING
        )
        telemetryRepository.saveTemperature(originalEvent)

        // Attempt to maliciously or accidentally overwrite the existing event with higher temp and different hash
        val corruptedAttempt = TemperatureEvent(
            id = "evt-immutable",
            shipmentId = "ship-300",
            loggerId = "LOG-300",
            timestamp = 9999L,
            temperature = 25.0,
            previousHash = "FORGED-PREV",
            currentHash = "FORGED-CURR",
            syncStatus = SyncStatus.FAILED
        )
        val attemptResult = telemetryRepository.saveTemperature(corruptedAttempt)
        assertTrue(attemptResult.isSuccess)

        // Verify history was NOT corrupted or duplicated
        val events = telemetryRepository.getTemperatures("ship-300").first()
        assertEquals(1, events.size)
        assertEquals(5.0, events[0].temperature, 0.001)
        assertEquals(5000L, events[0].timestamp)
        assertEquals("PREV-A", events[0].previousHash)
        assertEquals("CURR-A", events[0].currentHash)
    }

    @Test
    fun testIdAndTimestampPreservation() = runTest {
        val exactTimestamp = 1718900000123L
        val exactPreviousHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val exactCurrentHash = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"

        val event = TemperatureEvent(
            id = "evt-strict-id-999",
            shipmentId = "ship-strict",
            loggerId = "LOGGER-X",
            timestamp = exactTimestamp,
            temperature = 3.75,
            previousHash = exactPreviousHash,
            currentHash = exactCurrentHash,
            syncStatus = SyncStatus.PENDING
        )

        telemetryRepository.saveTemperature(event)

        val stored = telemetryRepository.getTemperatures("ship-strict").first().first()
        assertEquals("evt-strict-id-999", stored.id)
        assertEquals(exactTimestamp, stored.timestamp)
        assertEquals(exactPreviousHash, stored.previousHash)
        assertEquals(exactCurrentHash, stored.currentHash)
        assertEquals(3.75, stored.temperature, 0.0001)
    }

    @Test
    fun testAlertSaveAndReadActive() = runTest {
        val alert1 = Alert(
            id = "alt-1",
            shipmentId = "ship-alert-test",
            type = AlertType.TEMPERATURE_BREACH,
            message = "Excursion above 8C",
            timestamp = 1000L,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false
        )
        val alert2 = Alert(
            id = "alt-2",
            shipmentId = "ship-alert-test",
            type = AlertType.OPERATIONAL_WARNING,
            message = "Battery low",
            timestamp = 2000L,
            escalationLevel = EscalationLevel.SUPERVISOR,
            acknowledged = true // Already acknowledged
        )

        alertRepository.saveAlert(alert1)
        alertRepository.saveAlert(alert2)

        val active = alertRepository.getActiveAlerts("ship-alert-test").first()
        assertEquals(1, active.size)
        assertEquals("alt-1", active[0].id)
        assertEquals(AlertType.TEMPERATURE_BREACH, active[0].type)
        assertEquals(false, active[0].acknowledged)
    }

    @Test
    fun testHandoverSaveAndRead() = runTest {
        val handover = Handover(
            id = "hnd-100",
            shipmentId = "ship-handover-test",
            workerSigned = true,
            pharmacistSigned = true,
            integrityVerified = true,
            temperaturePassed = true,
            verdict = HandoverVerdict.PASS,
            timestamp = 1718905555000L
        )

        val saveRes = handoverRepository.saveHandover(handover)
        assertTrue(saveRes.isSuccess)

        val read = handoverRepository.getHandover("ship-handover-test").first()
        assertNotNull(read)
        assertEquals("hnd-100", read?.id)
        assertEquals(true, read?.workerSigned)
        assertEquals(true, read?.pharmacistSigned)
        assertEquals(true, read?.integrityVerified)
        assertEquals(true, read?.temperaturePassed)
        assertEquals(HandoverVerdict.PASS, read?.verdict)
        assertEquals(1718905555000L, read?.timestamp)

        // Non-existent shipment handover should return null
        val empty = handoverRepository.getHandover("non-existent").first()
        assertNull(empty)
    }
}

// ---------------------------------------------------------------------------
// In-Memory Fake DAOs implementing the Room DAOs for pure JVM unit testing
// ---------------------------------------------------------------------------

class FakeShipmentDao : ShipmentDao {
    private val shipments = LinkedHashMap<String, ShipmentEntity>()
    private val flow = MutableStateFlow<List<ShipmentEntity>>(emptyList())

    override fun getShipment(id: String): Flow<ShipmentEntity?> {
        return flow.map { list -> list.find { it.id == id } }
    }

    override suspend fun getShipmentDirect(id: String): ShipmentEntity? {
        return shipments[id]
    }

    override fun getAllShipments(): Flow<List<ShipmentEntity>> {
        return flow
    }

    override suspend fun upsertShipment(shipment: ShipmentEntity) {
        shipments[shipment.id] = shipment
        flow.value = shipments.values.toList()
    }

    override suspend fun getPendingShipments(): List<ShipmentEntity> {
        return shipments.values.filter { it.syncStatus != SyncStatus.SYNCED }
    }

    override suspend fun updateSyncStatus(id: String, syncStatus: SyncStatus, timestamp: Long) {
        shipments[id]?.let {
            shipments[id] = it.copy(syncStatus = syncStatus, lastModifiedTimestamp = timestamp)
            flow.value = shipments.values.toList()
        }
    }
}

class FakeTemperatureEventDao : TemperatureEventDao {
    private val events = ArrayList<TemperatureEventEntity>()
    private val flow = MutableStateFlow<List<TemperatureEventEntity>>(emptyList())

    override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEventEntity>> {
        return flow.map { list -> list.filter { it.shipmentId == shipmentId }.sortedBy { it.timestamp } }
    }

    override suspend fun getTemperaturesDirect(shipmentId: String): List<TemperatureEventEntity> {
        return events.filter { it.shipmentId == shipmentId }.sortedBy { it.timestamp }
    }

    override suspend fun insertEvent(event: TemperatureEventEntity): Long {
        // Enforce Room OnConflictStrategy.IGNORE: do not add if ID already exists
        if (events.any { it.id == event.id }) {
            return -1L
        }
        events.add(event)
        flow.value = ArrayList(events)
        return events.size.toLong()
    }

    override suspend fun getPendingEvents(): List<TemperatureEventEntity> {
        return events.filter { it.syncStatus != SyncStatus.SYNCED }.sortedBy { it.timestamp }
    }

    override suspend fun updateSyncStatus(id: String, syncStatus: SyncStatus) {
        val index = events.indexOfFirst { it.id == id }
        if (index >= 0) {
            events[index] = events[index].copy(syncStatus = syncStatus)
            flow.value = ArrayList(events)
        }
    }

    override suspend fun updateSyncStatuses(ids: List<String>, syncStatus: SyncStatus) {
        for (i in events.indices) {
            if (events[i].id in ids) {
                events[i] = events[i].copy(syncStatus = syncStatus)
            }
        }
        flow.value = ArrayList(events)
    }
}

class FakeAlertDao : AlertDao {
    private val alerts = LinkedHashMap<String, AlertEntity>()
    private val flow = MutableStateFlow<List<AlertEntity>>(emptyList())

    override fun getActiveAlerts(shipmentId: String): Flow<List<AlertEntity>> {
        return flow.map { list ->
            list.filter { it.shipmentId == shipmentId && !it.acknowledged }.sortedByDescending { it.timestamp }
        }
    }

    override fun getAllAlerts(shipmentId: String): Flow<List<AlertEntity>> {
        return flow.map { list ->
            list.filter { it.shipmentId == shipmentId }.sortedByDescending { it.timestamp }
        }
    }

    override suspend fun getAlertDirect(id: String): AlertEntity? {
        return alerts[id]
    }

    override suspend fun upsertAlert(alert: AlertEntity) {
        alerts[alert.id] = alert
        flow.value = alerts.values.toList()
    }

    override suspend fun getPendingAlerts(): List<AlertEntity> {
        return alerts.values.filter { it.syncStatus != SyncStatus.SYNCED }
    }

    override suspend fun updateSyncStatus(id: String, syncStatus: SyncStatus, timestamp: Long) {
        alerts[id]?.let {
            alerts[id] = it.copy(syncStatus = syncStatus, lastModifiedTimestamp = timestamp)
            flow.value = alerts.values.toList()
        }
    }

    override suspend fun acknowledgeAlert(id: String, pendingStatus: SyncStatus, timestamp: Long) {
        alerts[id]?.let {
            alerts[id] = it.copy(acknowledged = true, syncStatus = pendingStatus, lastModifiedTimestamp = timestamp)
            flow.value = alerts.values.toList()
        }
    }
}

class FakeHandoverDao : HandoverDao {
    private val handovers = LinkedHashMap<String, HandoverEntity>()
    private val flow = MutableStateFlow<List<HandoverEntity>>(emptyList())

    override fun getHandover(shipmentId: String): Flow<HandoverEntity?> {
        return flow.map { list -> list.find { it.shipmentId == shipmentId } }
    }

    override suspend fun getHandoverDirect(shipmentId: String): HandoverEntity? {
        return handovers[shipmentId]
    }

    override suspend fun upsertHandover(handover: HandoverEntity) {
        handovers[handover.shipmentId] = handover
        flow.value = handovers.values.toList()
    }

    override suspend fun getPendingHandovers(): List<HandoverEntity> {
        return handovers.values.filter { it.syncStatus != SyncStatus.SYNCED }
    }

    override suspend fun updateSyncStatus(id: String, syncStatus: SyncStatus, timestamp: Long) {
        val entry = handovers.values.find { it.id == id }
        if (entry != null) {
            handovers[entry.shipmentId] = entry.copy(syncStatus = syncStatus, lastModifiedTimestamp = timestamp)
            flow.value = handovers.values.toList()
        }
    }
}
