package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.ColdChainRepositoryProvider
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ColdChainRepositoryTest {

    private lateinit var telemetryRepository: TelemetryRepository
    private lateinit var shipmentRepository: ShipmentRepository
    private lateinit var handoverRepository: HandoverRepository

    private val testShipmentId = "SHP-VCC-TEST-2024"

    @Before
    fun setUp() {
        ColdChainRepositoryProvider.resetForTesting()
        telemetryRepository = ColdChainRepositoryProvider.telemetryRepository
        shipmentRepository = ColdChainRepositoryProvider.shipmentRepository
        handoverRepository = ColdChainRepositoryProvider.handoverRepository
    }

    /**
     * TEST 22:
     * Save one TemperatureEvent.
     * Read it back.
     * Verify every field is identical.
     */
    @Test
    fun test22_saveOneTemperatureEventPreservesEveryField() = runBlocking {
        val original = TemperatureEvent(
            id = "TE-001",
            shipmentId = testShipmentId,
            loggerId = "LOG-902",
            timestamp = 1700000000000L,
            temperature = 4.5432,
            previousHash = "GENESIS",
            currentHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
            syncStatus = SyncStatus.PENDING
        )

        val saveResult = telemetryRepository.saveTemperature(original)
        assertTrue("Saving temperature must succeed", saveResult.isSuccess)

        val retrievedList = telemetryRepository.getTemperatures(testShipmentId).first()
        assertEquals(1, retrievedList.size)

        val retrieved = retrievedList.first()
        assertEquals(original.id, retrieved.id)
        assertEquals(original.shipmentId, retrieved.shipmentId)
        assertEquals(original.loggerId, retrieved.loggerId)
        assertEquals(original.timestamp, retrieved.timestamp)
        assertEquals(original.temperature, retrieved.temperature, 0.00001)
        assertEquals(original.previousHash, retrieved.previousHash)
        assertEquals(original.currentHash, retrieved.currentHash)
        assertEquals(original.syncStatus, retrieved.syncStatus)
    }

    /**
     * TEST 23:
     * Save multiple TemperatureEvents out of insertion order.
     * getTemperatures(shipmentId) must return them sorted by timestamp ASC.
     */
    @Test
    fun test23_multipleEventsReturnedSortedByTimestampAsc() = runBlocking {
        val eventT3 = TemperatureEvent(
            id = "TE-3",
            shipmentId = testShipmentId,
            loggerId = "LOG-902",
            timestamp = 1700000120000L,
            temperature = 5.2,
            previousHash = "hash2",
            currentHash = "hash3",
            syncStatus = SyncStatus.SYNCED
        )
        val eventT1 = TemperatureEvent(
            id = "TE-1",
            shipmentId = testShipmentId,
            loggerId = "LOG-902",
            timestamp = 1700000000000L,
            temperature = 4.5,
            previousHash = "GENESIS",
            currentHash = "hash1",
            syncStatus = SyncStatus.SYNCED
        )
        val eventT2 = TemperatureEvent(
            id = "TE-2",
            shipmentId = testShipmentId,
            loggerId = "LOG-902",
            timestamp = 1700000060000L,
            temperature = 4.8,
            previousHash = "hash1",
            currentHash = "hash2",
            syncStatus = SyncStatus.SYNCED
        )

        // Insert out of chronological order: T3, then T1, then T2
        telemetryRepository.saveTemperature(eventT3)
        telemetryRepository.saveTemperature(eventT1)
        telemetryRepository.saveTemperature(eventT2)

        val retrievedList = telemetryRepository.getTemperatures(testShipmentId).first()
        assertEquals(3, retrievedList.size)

        // Mandatory: Chronological timestamp ASC order
        assertEquals("TE-1", retrievedList[0].id)
        assertEquals(1700000000000L, retrievedList[0].timestamp)

        assertEquals("TE-2", retrievedList[1].id)
        assertEquals(1700000060000L, retrievedList[1].timestamp)

        assertEquals("TE-3", retrievedList[2].id)
        assertEquals(1700000120000L, retrievedList[2].timestamp)
    }

    /**
     * TEST 24:
     * Save a valid hash chain.
     * Read it back through TelemetryRepository.
     * Run the REAL HashChainService.verifyChain().
     * Expected: valid=true.
     */
    @Test
    fun test24_saveValidChainAndVerifyWithRealHashChainService() = runBlocking {
        val baseTimestamp = 1700000000000L
        val raw = listOf(
            TemperatureEvent("TE-1", testShipmentId, "LOG-902", baseTimestamp, 4.2, "", "", SyncStatus.PENDING),
            TemperatureEvent("TE-2", testShipmentId, "LOG-902", baseTimestamp + 120_000L, 4.6, "", "", SyncStatus.PENDING),
            TemperatureEvent("TE-3", testShipmentId, "LOG-902", baseTimestamp + 240_000L, 5.0, "", "", SyncStatus.PENDING)
        )
        val validHashedChain = HashChainService.buildChain(raw)

        // Persist events
        for (event in validHashedChain) {
            telemetryRepository.saveTemperature(event)
        }

        // Read back
        val persistedEvents = telemetryRepository.getTemperatures(testShipmentId).first()
        assertEquals(3, persistedEvents.size)

        // Run REAL HashChainService
        val verification = HashChainService.verifyChain(persistedEvents)
        assertTrue("Real HashChainService must confirm chain integrity", verification.valid)
        assertEquals(null, verification.corruptedEventId)
    }

    /**
     * TEST 25:
     * Persist a tampered event.
     * Run REAL HashChainService.verifyChain().
     * Expected: valid=false.
     */
    @Test
    fun test25_tamperedEventFailsRealHashChainServiceVerification() = runBlocking {
        val baseTimestamp = 1700000000000L
        val raw = listOf(
            TemperatureEvent("TE-1", testShipmentId, "LOG-902", baseTimestamp, 4.0, "", "", SyncStatus.PENDING),
            TemperatureEvent("TE-2", testShipmentId, "LOG-902", baseTimestamp + 120_000L, 4.5, "", "", SyncStatus.PENDING)
        )
        val validChain = HashChainService.buildChain(raw)

        // Save event 1 normally
        telemetryRepository.saveTemperature(validChain[0])

        // Tamper event 2's temperature without updating hashes
        val tamperedEvent2 = validChain[1].copy(temperature = 22.5)
        telemetryRepository.saveTemperature(tamperedEvent2)

        val retrievedEvents = telemetryRepository.getTemperatures(testShipmentId).first()

        // Real HashChainService must detect tampering
        val verification = HashChainService.verifyChain(retrievedEvents)
        assertFalse("Real HashChainService must detect tampered event", verification.valid)
        assertEquals("TE-2", verification.corruptedEventId)
    }

    /**
     * TEST 26:
     * Save a Handover.
     * Read it back.
     * Verify every existing Handover field is preserved.
     */
    @Test
    fun test26_saveHandoverPreservesEveryField() = runBlocking {
        val handover = Handover(
            id = "HND-2024-001",
            shipmentId = testShipmentId,
            workerSigned = true,
            pharmacistSigned = true,
            integrityVerified = true,
            temperaturePassed = true,
            verdict = HandoverVerdict.PASS,
            timestamp = 1700000500000L
        )

        val saveResult = handoverRepository.saveHandover(handover)
        assertTrue(saveResult.isSuccess)

        val retrieved = handoverRepository.getHandover(testShipmentId).first()
        assertNotNull("Handover must be retrieved", retrieved)
        assertEquals("HND-2024-001", retrieved?.id)
        assertEquals(testShipmentId, retrieved?.shipmentId)
        assertEquals(true, retrieved?.workerSigned)
        assertEquals(true, retrieved?.pharmacistSigned)
        assertEquals(true, retrieved?.integrityVerified)
        assertEquals(true, retrieved?.temperaturePassed)
        assertEquals(HandoverVerdict.PASS, retrieved?.verdict)
        assertEquals(1700000500000L, retrieved?.timestamp)
    }

    /**
     * TEST 27:
     * Save a Shipment with status ACCEPTED.
     * Read it back.
     * Verify status is ACCEPTED.
     */
    @Test
    fun test27_saveShipmentAcceptedStatusPreserved() = runBlocking {
        val shipment = Shipment(
            id = testShipmentId,
            qrCode = "COLDCHAIN:$testShipmentId:LOG-902",
            loggerId = "LOG-902",
            origin = "Central Cold Hub",
            destination = "Metro Care Pharmacy",
            workerId = "WORKER-07",
            status = ShipmentStatus.ACCEPTED
        )

        val saveResult = shipmentRepository.saveShipment(shipment)
        assertTrue(saveResult.isSuccess)

        val retrieved = shipmentRepository.getShipment(testShipmentId).first()
        assertNotNull("Shipment must be retrieved", retrieved)
        assertEquals(ShipmentStatus.ACCEPTED, retrieved?.status)
        assertEquals("Metro Care Pharmacy", retrieved?.destination)
    }

    /**
     * TEST 28:
     * Create two consumers of the repository/data provider.
     * Verify they access the SAME underlying data source rather than independent empty stores.
     */
    @Test
    fun test28_twoConsumersAccessSameUnderlyingDataSource() = runBlocking {
        // Consumer A (e.g. Person 2's BLE Logger simulation)
        val consumerA_telemetry = ColdChainRepositoryProvider.telemetryRepository
        val consumerA_shipment = ColdChainRepositoryProvider.shipmentRepository

        // Consumer B (e.g. Person 4's HandoverOrchestrator)
        val consumerB_telemetry = ColdChainRepositoryProvider.telemetryRepository
        val consumerB_shipment = ColdChainRepositoryProvider.shipmentRepository

        // Consumer A writes a temperature reading and updates shipment
        val reading = TemperatureEvent(
            id = "TE-SHARED-1",
            shipmentId = testShipmentId,
            loggerId = "LOG-902",
            timestamp = 1700000010000L,
            temperature = 4.4,
            previousHash = "GENESIS",
            currentHash = "hash-shared",
            syncStatus = SyncStatus.PENDING
        )
        consumerA_telemetry.saveTemperature(reading)

        val shipment = Shipment(
            id = testShipmentId,
            qrCode = "QR-$testShipmentId",
            loggerId = "LOG-902",
            origin = "Depot A",
            destination = "Clinic B",
            workerId = "W-1",
            status = ShipmentStatus.IN_TRANSIT
        )
        consumerA_shipment.saveShipment(shipment)

        // Consumer B immediately reads from its repository reference
        val consumerB_readings = consumerB_telemetry.getTemperatures(testShipmentId).first()
        val consumerB_shipmentData = consumerB_shipment.getShipment(testShipmentId).first()

        assertEquals("Consumer B must see Consumer A's temperature event", 1, consumerB_readings.size)
        assertEquals("TE-SHARED-1", consumerB_readings.first().id)

        assertNotNull("Consumer B must see Consumer A's shipment", consumerB_shipmentData)
        assertEquals(ShipmentStatus.IN_TRANSIT, consumerB_shipmentData?.status)
    }
}
