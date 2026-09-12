package com.coldchain.handshake.handover

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HandoverOrchestratorTest {

    private lateinit var shipmentRepo: TestShipmentRepository
    private lateinit var telemetryRepo: TestTelemetryRepository
    private lateinit var handoverRepo: TestHandoverRepository
    private lateinit var orchestrator: HandoverOrchestrator

    private val testShipmentId = "SHP-VCC-2024-001"

    @Before
    fun setUp() {
        shipmentRepo = TestShipmentRepository()
        telemetryRepo = TestTelemetryRepository()
        handoverRepo = TestHandoverRepository()
        orchestrator = HandoverOrchestrator(shipmentRepo, telemetryRepo, handoverRepo)

        // Seed initial shipment in ARRIVED status
        runBlocking {
            shipmentRepo.saveShipment(
                Shipment(
                    id = testShipmentId,
                    qrCode = "COLDCHAIN:$testShipmentId:LOG-902",
                    loggerId = "LOG-902",
                    origin = "Central Cold Hub",
                    destination = "St. Jude Pharmacy",
                    workerId = "W-14",
                    status = ShipmentStatus.ARRIVED
                )
            )
        }
    }

    private fun createValidChain(baseTimestamp: Long = 1700000000000L): List<TemperatureEvent> {
        val raw = listOf(
            TemperatureEvent("TE-1", testShipmentId, "LOG-902", baseTimestamp, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-2", testShipmentId, "LOG-902", baseTimestamp + 60_000L, 4.8, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-3", testShipmentId, "LOG-902", baseTimestamp + 120_000L, 5.0, "", "", SyncStatus.SYNCED)
        )
        return HashChainService.buildChain(raw)
    }

    /**
     * TEST 15:
     * valid temperature, valid hash chain, worker signed, pharmacist signed
     * Expected: Handover PASS, integrityVerified=true, temperaturePassed=true, shipment ACCEPTED
     */
    @Test
    fun test15_validHandoverAcceptance() = runBlocking {
        telemetryRepo.setEvents(testShipmentId, createValidChain())

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue("Orchestration must succeed", result.isSuccess)
        val data = result.getOrThrow()

        // Verify Handover
        assertEquals(HandoverVerdict.PASS, data.handover.verdict)
        assertTrue(data.handover.integrityVerified)
        assertTrue(data.handover.temperaturePassed)
        assertTrue(data.handover.workerSigned)
        assertTrue(data.handover.pharmacistSigned)

        // Verify Shipment updated in repository
        assertEquals(ShipmentStatus.ACCEPTED, data.updatedShipment.status)
        val persistedShipment = shipmentRepo.getShipmentDirect(testShipmentId)
        assertEquals(ShipmentStatus.ACCEPTED, persistedShipment?.status)
    }

    /**
     * TEST 16:
     * temperaturePassed=false, valid hash chain, both signed
     * Expected: Handover FAIL, integrityVerified=true, shipment QUARANTINED
     */
    @Test
    fun test16_temperatureFailedQuarantinesShipment() = runBlocking {
        telemetryRepo.setEvents(testShipmentId, createValidChain())

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = false,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()

        assertEquals(HandoverVerdict.FAIL, data.handover.verdict)
        assertTrue(data.handover.integrityVerified)
        assertFalse(data.handover.temperaturePassed)
        assertEquals(ShipmentStatus.QUARANTINED, data.updatedShipment.status)

        val persistedShipment = shipmentRepo.getShipmentDirect(testShipmentId)
        assertEquals(ShipmentStatus.QUARANTINED, persistedShipment?.status)
    }

    /**
     * TEST 17:
     * temperaturePassed=true, tampered hash chain, both signed
     * Expected: Handover FAIL, integrityVerified=false, shipment QUARANTINED
     */
    @Test
    fun test17_tamperedHashChainQuarantinesShipment() = runBlocking {
        val validChain = createValidChain()
        // Tamper event 1's temperature without updating hashes
        val tamperedChain = validChain.toMutableList()
        tamperedChain[0] = tamperedChain[0].copy(temperature = 16.5)
        telemetryRepo.setEvents(testShipmentId, tamperedChain)

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()

        assertEquals(HandoverVerdict.FAIL, data.handover.verdict)
        assertFalse("Orchestrator must detect broken cryptographic integrity", data.handover.integrityVerified)
        assertTrue(data.handover.temperaturePassed)
        assertEquals(ShipmentStatus.QUARANTINED, data.updatedShipment.status)

        val persistedShipment = shipmentRepo.getShipmentDirect(testShipmentId)
        assertEquals(ShipmentStatus.QUARANTINED, persistedShipment?.status)
    }

    /**
     * TEST 18:
     * temperaturePassed=true, valid integrity, workerSigned=false, pharmacistSigned=true
     * Expected: FAIL, QUARANTINED
     */
    @Test
    fun test18_workerSignatureMissingQuarantinesShipment() = runBlocking {
        telemetryRepo.setEvents(testShipmentId, createValidChain())

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = false,
            pharmacistSigned = true
        )

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()

        assertEquals(HandoverVerdict.FAIL, data.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, data.updatedShipment.status)
        assertFalse(data.handover.workerSigned)
        assertTrue(data.evaluation.failureReasons.contains(HandoverFailureReason.WORKER_SIGNATURE_MISSING))
    }

    /**
     * TEST 19:
     * temperaturePassed=true, valid integrity, workerSigned=true, pharmacistSigned=false
     * Expected: FAIL, QUARANTINED
     */
    @Test
    fun test19_pharmacistSignatureMissingQuarantinesShipment() = runBlocking {
        telemetryRepo.setEvents(testShipmentId, createValidChain())

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = false
        )

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()

        assertEquals(HandoverVerdict.FAIL, data.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, data.updatedShipment.status)
        assertFalse(data.handover.pharmacistSigned)
        assertTrue(data.evaluation.failureReasons.contains(HandoverFailureReason.PHARMACIST_SIGNATURE_MISSING))
    }

    /**
     * TEST 20:
     * all four conditions pass.
     * Verify the persisted Handover contains the exact four authoritative fields:
     * workerSigned=true, pharmacistSigned=true, integrityVerified=true, temperaturePassed=true, verdict=PASS
     */
    @Test
    fun test20_persistedHandoverExactAuthoritativeFields() = runBlocking {
        telemetryRepo.setEvents(testShipmentId, createValidChain())

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue(result.isSuccess)

        val persisted = handoverRepo.getHandoverDirect(testShipmentId)
        assertNotNull("Handover must be persisted in repository", persisted)
        assertEquals(true, persisted?.workerSigned)
        assertEquals(true, persisted?.pharmacistSigned)
        assertEquals(true, persisted?.integrityVerified)
        assertEquals(true, persisted?.temperaturePassed)
        assertEquals(HandoverVerdict.PASS, persisted?.verdict)
    }

    /**
     * TEST 21:
     * tamper a temperature event after its hash was created.
     * The orchestrator must detect integrityPassed=false and produce FAIL, QUARANTINED.
     */
    @Test
    fun test21_tamperAfterHashCreationDetectedByOrchestrator() = runBlocking {
        // 1. Create a valid hash chain
        val validChain = createValidChain()

        // 2. Modify an event's temperature without updating currentHash
        val tamperedChain = validChain.toMutableList()
        tamperedChain[1] = tamperedChain[1].copy(temperature = 25.0)

        // 3. Give that corrupted chain to the orchestrator via telemetry repository
        telemetryRepo.setEvents(testShipmentId, tamperedChain)

        // 4. Orchestrator executes verification via real HashChainService
        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()

        // 5. Confirm the orchestrator rejects it with FAIL and QUARANTINED
        assertFalse("Orchestrator must detect tampering", data.handover.integrityVerified)
        assertEquals(HandoverVerdict.FAIL, data.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, data.updatedShipment.status)
        assertTrue(data.evaluation.failureReasons.contains(HandoverFailureReason.INTEGRITY_FAILED))
    }

    @Test
    fun testEmptyTelemetryChainQuarantinesShipment() = runBlocking {
        // Empty telemetry chain yields EMPTY_CHAIN integrity failure
        telemetryRepo.setEvents(testShipmentId, emptyList())

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertFalse("Empty chain fails integrity verification", data.handover.integrityVerified)
        assertEquals(HandoverVerdict.FAIL, data.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, data.updatedShipment.status)
        assertTrue(data.evaluation.failureReasons.contains(HandoverFailureReason.INTEGRITY_FAILED))
    }

    @Test
    fun testShipmentNotFoundReturnsFailure() = runBlocking {
        val result = orchestrator.processHandover(
            shipmentId = "NON-EXISTENT-ID",
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue("Should return failed Result when shipment not found", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Shipment not found") == true)
    }

    @Test
    fun testTelemetryCannotBeObtainedReturnsFailure() = runBlocking {
        // Simulate repository communication failure
        telemetryRepo.setThrowError(true)

        val result = orchestrator.processHandover(
            shipmentId = testShipmentId,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue("Should return failed Result when telemetry stream throws or fails", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Telemetry") == true)
    }

    // ==========================================================
    // Simple test fakes defined strictly inside this test file
    // ==========================================================

    private class TestShipmentRepository : ShipmentRepository {
        private val shipments = MutableStateFlow<Map<String, Shipment>>(emptyMap())

        override suspend fun saveShipment(shipment: Shipment): Result<Unit> {
            shipments.value = shipments.value + (shipment.id to shipment)
            return Result.success(Unit)
        }

        override fun getShipment(id: String): Flow<Shipment?> {
            return shipments.map { it[id] }
        }

        override fun getAllShipments(): Flow<List<Shipment>> {
            return shipments.map { it.values.toList() }
        }

        fun getShipmentDirect(id: String): Shipment? = shipments.value[id]
    }

    private class TestTelemetryRepository : TelemetryRepository {
        private val telemetry = MutableStateFlow<Map<String, List<TemperatureEvent>>>(emptyMap())
        private var shouldThrow = false

        fun setThrowError(value: Boolean) {
            shouldThrow = value
        }

        override suspend fun saveTemperature(event: TemperatureEvent): Result<Unit> {
            val list = telemetry.value[event.shipmentId] ?: emptyList()
            telemetry.value = telemetry.value + (event.shipmentId to (list + event))
            return Result.success(Unit)
        }

        override suspend fun saveTemperatures(events: List<TemperatureEvent>): Result<Unit> {
            events.forEach { saveTemperature(it) }
            return Result.success(Unit)
        }

        override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
            if (shouldThrow) {
                return flow {
                    throw IllegalStateException("Sensor network error: telemetry unreachable")
                }
            }
            return telemetry.map { it[shipmentId] ?: emptyList() }
        }

        fun setEvents(shipmentId: String, events: List<TemperatureEvent>) {
            telemetry.value = telemetry.value + (shipmentId to events)
        }
    }

    private class TestHandoverRepository : HandoverRepository {
        private val handovers = MutableStateFlow<Map<String, Handover>>(emptyMap())

        override suspend fun saveHandover(handover: Handover): Result<Unit> {
            handovers.value = handovers.value + (handover.shipmentId to handover)
            return Result.success(Unit)
        }

        override fun getHandover(shipmentId: String): Flow<Handover?> {
            return handovers.map { it[shipmentId] }
        }

        fun getHandoverDirect(shipmentId: String): Handover? = handovers.value[shipmentId]
    }
}
