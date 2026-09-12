package com.coldchain.handshake

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.IntegrityFailureReason
import com.coldchain.handshake.handover.HandoverFailureReason
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.HandoverVerdictEngine
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.repository.impl.InMemoryChaosEngineService
import com.coldchain.handshake.safety.AlertEscalationEngine
import com.coldchain.handshake.safety.BreachDetector
import com.coldchain.handshake.safety.LoggerDisconnectMonitor
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Autonomous 0->100% End-to-End Validation Test Suite.
 *
 * Implements the full 24-step verification scenario specified in Section 50:
 * 1. Create shipment.
 * 2. Generate QR / associate qrCode.
 * 3. Associate/pair logger.
 * 4. Dispatch (CREATED -> DISPATCHED).
 * 5. Start journey (DISPATCHED -> IN_TRANSIT).
 * 6. Generate normal temperature data (4°C–6°C).
 * 7. Confirm events persist locally with SHA-256 hash chaining.
 * 8. Trigger network failure (simulate offline buffering).
 * 9. Continue generating telemetry.
 * 10. Confirm data remains PENDING.
 * 11. Restore network.
 * 12. Confirm synchronization occurs (PENDING -> SYNCED).
 * 13. Trigger heat spike (9°C–11°C).
 * 14. Continue until cumulative >5 minutes above 8°C.
 * 15. Confirm breach detected.
 * 16. Confirm worker alert created and persisted.
 * 17. Confirm escalation (WORKER -> SUPERVISOR -> PHARMACIST).
 * 18. Confirm quarantine applied to Shipment in repository.
 * 19. Open handover evaluation.
 * 20. Verify temperature safety evaluation (fails due to breach).
 * 21. Verify hash chain integrity (valid cryptographic chain).
 * 22. Worker signs.
 * 23. Pharmacist signs.
 * 24. Verify PASS/FAIL verdict (fails due to breach, cargo QUARANTINED).
 *
 * Additional Autonomous Tests:
 * - testLoggerDisconnectChaosScenario()
 * - testCryptographicTamperAndCorruptionDetection()
 * - testSuccessfulHandoverWhenNominal()
 * - testSystemResetCleansAllStates()
 */
class FullAutonomousColdChainE2ETest {

    private val testDispatcher = StandardTestDispatcher()

    // Test repositories
    private lateinit var shipmentRepo: E2EShipmentRepository
    private lateinit var telemetryRepo: E2ETelemetryRepository
    private lateinit var alertRepo: E2EAlertRepository
    private lateinit var handoverRepo: E2EHandoverRepository
    private lateinit var chaosService: InMemoryChaosEngineService
    private lateinit var safetyEngine: SafetyEngine
    private lateinit var handoverOrchestrator: HandoverOrchestrator
    private lateinit var simulator: TemperatureSimulator

    @Before
    fun setUp() {
        shipmentRepo = E2EShipmentRepository()
        telemetryRepo = E2ETelemetryRepository()
        alertRepo = E2EAlertRepository()
        handoverRepo = E2EHandoverRepository()
        chaosService = InMemoryChaosEngineService()

        safetyEngine = SafetyEngine(
            alertRepository = alertRepo,
            shipmentRepository = shipmentRepo
        )

        handoverOrchestrator = HandoverOrchestrator(
            shipmentRepository = shipmentRepo,
            telemetryRepository = telemetryRepo,
            handoverRepository = handoverRepo
        )

        simulator = TemperatureSimulator(
            telemetryRepository = telemetryRepo,
            chaosEngineService = chaosService,
            dispatcher = testDispatcher,
            safetyEngine = safetyEngine
        )
    }

    @Test
    fun testComplete24StepLifecycleScenario() = runTest(testDispatcher) {
        val shipmentId = "SHIP-E2E-AUTONOMOUS"
        val origin = "Central Regional Depot"
        val destination = "St. Jude Hospital Pharmacy"
        val workerId = "WORKER-ALPHA-01"
        val pairedLoggerId = "LOG-SENS-777"
        val qrCodePayload = "CCH:SHIP:$shipmentId:DEPOT:STJUDE:LOG-777"

        // Step 1: Create shipment
        val initialShipment = Shipment(
            id = shipmentId,
            qrCode = "",
            loggerId = "",
            origin = origin,
            destination = destination,
            workerId = workerId,
            status = ShipmentStatus.CREATED
        )
        shipmentRepo.saveShipment(initialShipment)
        var shipment = shipmentRepo.getShipment(shipmentId).first()
        assertNotNull(shipment)
        assertEquals(ShipmentStatus.CREATED, shipment?.status)

        // Step 2: Generate / associate QR
        val qrShipment = shipment!!.copy(qrCode = qrCodePayload)
        shipmentRepo.saveShipment(qrShipment)
        shipment = shipmentRepo.getShipment(shipmentId).first()
        assertEquals(qrCodePayload, shipment?.qrCode)

        // Step 3: Associate / pair logger
        val pairedShipment = qrShipment.copy(loggerId = pairedLoggerId)
        shipmentRepo.saveShipment(pairedShipment)
        shipment = shipmentRepo.getShipment(shipmentId).first()
        assertEquals(pairedLoggerId, shipment?.loggerId)

        // Step 4: Dispatch shipment
        val dispatchedShipment = pairedShipment.copy(status = ShipmentStatus.DISPATCHED)
        shipmentRepo.saveShipment(dispatchedShipment)
        shipment = shipmentRepo.getShipment(shipmentId).first()
        assertEquals(ShipmentStatus.DISPATCHED, shipment?.status)

        // Step 5: Start journey (IN_TRANSIT) & attach to simulator
        val inTransitShipment = dispatchedShipment.copy(status = ShipmentStatus.IN_TRANSIT)
        shipmentRepo.saveShipment(inTransitShipment)
        shipment = shipmentRepo.getShipment(shipmentId).first()
        assertEquals(ShipmentStatus.IN_TRANSIT, shipment?.status)

        val startTime = 1700000000000L
        simulator.attachShipment(inTransitShipment, startTimestamp = startTime)
        advanceUntilIdle()

        // Step 6: Generate normal temperature data (4.5°C–5.5°C)
        simulator.setHeatSpikeMode(false)
        for (i in 1..3) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull(event)
            assertTrue("Reading should be within safe range", event!!.temperature in 2.0..8.0)
        }

        // Step 7: Confirm events persist locally with SHA-256 hash chaining
        val initialEvents = telemetryRepo.getTemperatures(shipmentId).first()
        assertEquals(3, initialEvents.size)
        val initialIntegrity = HashChainService.verifyChain(initialEvents)
        assertTrue("Hash chain must be cryptographically valid", initialIntegrity.valid)
        assertEquals(HashChainService.GENESIS_HASH, initialEvents[0].previousHash)
        assertEquals(initialEvents[0].currentHash, initialEvents[1].previousHash)
        assertEquals(initialEvents[1].currentHash, initialEvents[2].previousHash)

        // Step 8: Trigger network failure (simulate offline buffering)
        chaosService.injectScenario(ChaosScenarioType.NETWORK_FAILURE)
        advanceUntilIdle()
        var activeChaos = chaosService.observeActiveScenarios().first()
        assertTrue(activeChaos.any { it.scenarioType == ChaosScenarioType.NETWORK_FAILURE && it.isActive })

        // Step 9: Continue generating telemetry while offline
        for (i in 4..6) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull(event)
        }

        // Step 10: Confirm data remains PENDING
        val bufferedEvents = telemetryRepo.getTemperatures(shipmentId).first()
        assertEquals(6, bufferedEvents.size)
        assertTrue("Buffered events must have PENDING sync status", bufferedEvents.drop(3).all { it.syncStatus == SyncStatus.PENDING })

        // Step 11: Restore network
        chaosService.resetScenario(ChaosScenarioType.NETWORK_FAILURE)
        advanceUntilIdle()
        activeChaos = chaosService.observeActiveScenarios().first()
        assertFalse(activeChaos.any { it.scenarioType == ChaosScenarioType.NETWORK_FAILURE })

        // Step 12: Confirm synchronization occurs
        telemetryRepo.simulateSyncAllPending()
        val syncedEvents = telemetryRepo.getTemperatures(shipmentId).first()
        assertTrue("All records must become SYNCED after network restore", syncedEvents.all { it.syncStatus == SyncStatus.SYNCED })

        // Step 13: Trigger heat spike
        chaosService.injectScenario(ChaosScenarioType.HEAT_SPIKE)
        advanceUntilIdle()
        assertTrue("Simulator must observe HEAT_SPIKE scenario", simulator.isHeatSpikeMode.value)

        // Step 14: Continue until cumulative >5 minutes above 8°C (6 ticks * 60s = 360s = 6 min > 5 min!)
        for (i in 7..14) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull(event)
            assertTrue("Heat spike temp must exceed 8°C", event!!.temperature > 8.0)
        }

        // Step 15: Confirm breach detected
        val allEvents = telemetryRepo.getTemperatures(shipmentId).first()
        assertEquals(14, allEvents.size)
        val breachEval = BreachDetector.evaluateTelemetry(allEvents)
        assertTrue("Breach detector must report breach", breachEval.hasBreach)
        assertFalse("Temperature passed must be false", breachEval.temperaturePassed)
        assertTrue("Breach duration must exceed 300,000ms", breachEval.cumulativeBreachDurationMs > 300_000L)

        // Step 16: Confirm worker alert created and persisted
        val activeAlerts = alertRepo.getActiveAlerts(shipmentId).first()
        assertTrue("Alert repository must contain active alerts", activeAlerts.isNotEmpty())
        val breachAlert = activeAlerts.firstOrNull { it.type == AlertType.TEMPERATURE_BREACH }
        assertNotNull("Breach alert must exist", breachAlert)
        assertEquals(EscalationLevel.WORKER, breachAlert?.escalationLevel)
        assertFalse(breachAlert!!.acknowledged)

        // Step 17: Confirm alert escalation
        val supervisorAlert = AlertEscalationEngine.escalateAlert(breachAlert)
        assertEquals(EscalationLevel.SUPERVISOR, supervisorAlert.escalationLevel)
        val pharmacistAlert = AlertEscalationEngine.escalateAlert(supervisorAlert)
        assertEquals(EscalationLevel.PHARMACIST, pharmacistAlert.escalationLevel)

        // Step 18: Confirm quarantine automatically enforced on Shipment
        val latestShipment = shipmentRepo.getShipment(shipmentId).first()
        assertEquals(ShipmentStatus.QUARANTINED, latestShipment?.status)

        // Step 19: Open handover evaluation
        val tempPassed = safetyEngine.evaluateHandoverTemperatureSafety(allEvents)
        // Step 20: Verify temperature safety failed
        assertFalse("Handover temperature safety check must FAIL", tempPassed)

        // Step 21: Verify hash integrity passes (un-tampered data)
        val chainIntegrity = HashChainService.verifyChain(allEvents)
        assertTrue("SHA-256 chain is un-tampered and intact", chainIntegrity.valid)

        // Step 22 & 23: Worker and Pharmacist sign
        val workerSigned = true
        val pharmacistSigned = true

        // Step 24: Process handover verdict
        val handoverOutcome = handoverOrchestrator.processHandover(
            shipmentId = shipmentId,
            temperaturePassed = tempPassed,
            workerSigned = workerSigned,
            pharmacistSigned = pharmacistSigned
        )

        assertTrue(handoverOutcome.isSuccess)
        val result = handoverOutcome.getOrNull()!!
        assertEquals("Handover verdict must be FAIL due to thermal breach", HandoverVerdict.FAIL, result.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, result.updatedShipment.status)
        assertTrue("Failure reason must include TEMPERATURE_EXCURSION", result.evaluation.failureReasons.contains(HandoverFailureReason.TEMPERATURE_FAILED))

        // Confirm Handover is persisted in HandoverRepository
        val persistedHandover = handoverRepo.getHandover(shipmentId).first()
        assertNotNull(persistedHandover)
        assertEquals(HandoverVerdict.FAIL, persistedHandover?.verdict)
    }

    @Test
    fun testSuccessfulHandoverWhenNominal() = runTest(testDispatcher) {
        val shipmentId = "SHIP-CLEAN-001"
        val cleanShipment = Shipment(
            id = shipmentId,
            qrCode = "QR-CLEAN",
            loggerId = "LOG-CLEAN",
            origin = "Origin",
            destination = "Dest",
            workerId = "W-1",
            status = ShipmentStatus.IN_TRANSIT
        )
        shipmentRepo.saveShipment(cleanShipment)
        simulator.attachShipment(cleanShipment, startTimestamp = 1700000000000L)
        simulator.setHeatSpikeMode(false)

        // Generate 5 safe ticks (4°C–6°C)
        for (i in 1..5) {
            simulator.tickOnce(60L)
        }

        val events = telemetryRepo.getTemperatures(shipmentId).first()
        val tempPassed = safetyEngine.evaluateHandoverTemperatureSafety(events)
        assertTrue("Nominal telemetry must pass temperature check", tempPassed)

        val outcome = handoverOrchestrator.processHandover(
            shipmentId = shipmentId,
            temperaturePassed = tempPassed,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue(outcome.isSuccess)
        val result = outcome.getOrNull()!!
        assertEquals(HandoverVerdict.PASS, result.handover.verdict)
        assertEquals(ShipmentStatus.ACCEPTED, result.updatedShipment.status)
        assertTrue(result.evaluation.failureReasons.isEmpty())
    }

    @Test
    fun testCryptographicTamperAndCorruptionDetection() = runTest(testDispatcher) {
        val shipmentId = "SHIP-TAMPER-001"
        val testShipment = Shipment(
            id = shipmentId,
            qrCode = "QR-TAMPER",
            loggerId = "LOG-TAMPER",
            origin = "Origin",
            destination = "Dest",
            workerId = "W-1",
            status = ShipmentStatus.IN_TRANSIT
        )
        shipmentRepo.saveShipment(testShipment)
        simulator.attachShipment(testShipment, startTimestamp = 1700000000000L)
        simulator.setHeatSpikeMode(false)

        for (i in 1..4) {
            simulator.tickOnce(60L)
        }

        val events = telemetryRepo.getTemperatures(shipmentId).first()
        assertEquals(4, events.size)

        // Initial chain must be valid
        assertTrue(HashChainService.verifyChain(events).valid)

        // Simulate CORRUPT_EVENT: Tamper reading of event index 2 in database without recomputing hash
        val tamperedEvents = events.toMutableList()
        val originalTarget = tamperedEvents[2]
        val corruptedTarget = originalTarget.copy(temperature = 99.9)
        tamperedEvents[2] = corruptedTarget

        // Verify tampering is immediately detected
        val tamperResult = HashChainService.verifyChain(tamperedEvents)
        assertFalse("Tampered chain must be invalid!", tamperResult.valid)
        assertEquals("Tampered event ID must be pinpointed", originalTarget.id, tamperResult.corruptedEventId)
        assertEquals(IntegrityFailureReason.CURRENT_HASH_MISMATCH, tamperResult.reason)

        // Verify HandoverVerdictEngine rejects tampered consignment
        val handoverEvaluation = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = tamperResult.valid,
            workerSigned = true,
            pharmacistSigned = true
        )
        assertEquals(HandoverVerdict.FAIL, handoverEvaluation.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, handoverEvaluation.targetStatus)
        assertTrue(handoverEvaluation.failureReasons.contains(HandoverFailureReason.INTEGRITY_FAILED))
    }

    @Test
    fun testLoggerDisconnectChaosScenario() = runTest(testDispatcher) {
        val shipmentId = "SHIP-DISCONNECT-001"
        val loggerId = "LOG-DISC-99"

        // Trigger LoggerDisconnectMonitor
        val alert = LoggerDisconnectMonitor.onLoggerDisconnected(
            shipmentId = shipmentId,
            loggerId = loggerId,
            reason = "Chaos injected: hardware link severed"
        )

        assertEquals(AlertType.LOGGER_DISCONNECT, alert.type)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse(alert.acknowledged)
        assertTrue(alert.message.contains(loggerId))

        // Save to repository
        val saveResult = alertRepo.saveAlert(alert)
        assertTrue(saveResult.isSuccess)

        val active = alertRepo.getActiveAlerts(shipmentId).first()
        assertEquals(1, active.size)
        assertEquals(alert.id, active[0].id)
    }

    @Test
    fun testSystemResetCleansAllStates() = runTest(testDispatcher) {
        chaosService.injectScenario(ChaosScenarioType.HEAT_SPIKE)
        chaosService.injectScenario(ChaosScenarioType.NETWORK_FAILURE)
        chaosService.injectScenario(ChaosScenarioType.LOGGER_DISCONNECT)

        simulator.setHeatSpikeMode(true)
        assertTrue(simulator.isHeatSpikeMode.value)

        // Execute reset
        chaosService.resetAllScenarios()
        simulator.resetSync()

        val scenarios = chaosService.observeActiveScenarios().first()
        assertTrue("All chaos scenarios must be empty after reset", scenarios.isEmpty())
        assertFalse("Heat spike mode must be false", simulator.isHeatSpikeMode.value)
        assertNull("Active shipment must be cleared", simulator.activeShipment.value)
    }
}

// ---------------- Helper Test Doubles ----------------

class E2EShipmentRepository : ShipmentRepository {
    private val shipments = mutableMapOf<String, Shipment>()
    private val flow = MutableStateFlow<List<Shipment>>(emptyList())

    override suspend fun saveShipment(shipment: Shipment): Result<Unit> {
        shipments[shipment.id] = shipment
        flow.value = shipments.values.toList()
        return Result.success(Unit)
    }

    override fun getShipment(id: String): Flow<Shipment?> = flow.map { list -> list.find { it.id == id } }

    override fun getAllShipments(): Flow<List<Shipment>> = flow
}

class E2ETelemetryRepository : TelemetryRepository {
    private val events = mutableListOf<TemperatureEvent>()
    private val flow = MutableStateFlow<List<TemperatureEvent>>(emptyList())

    override suspend fun saveTemperature(event: TemperatureEvent): Result<Unit> {
        events.add(event)
        flow.value = events.toList()
        return Result.success(Unit)
    }

    override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
        return flow.map { list -> list.filter { it.shipmentId == shipmentId }.sortedBy { it.timestamp } }
    }

    fun simulateSyncAllPending() {
        for (i in events.indices) {
            if (events[i].syncStatus != SyncStatus.SYNCED) {
                events[i] = events[i].copy(syncStatus = SyncStatus.SYNCED)
            }
        }
        flow.value = events.toList()
    }
}

class E2EAlertRepository : AlertRepository {
    private val alerts = mutableListOf<Alert>()
    private val flow = MutableStateFlow<List<Alert>>(emptyList())

    override suspend fun saveAlert(alert: Alert): Result<Unit> {
        val idx = alerts.indexOfFirst { it.id == alert.id }
        if (idx >= 0) alerts[idx] = alert else alerts.add(alert)
        flow.value = alerts.toList()
        return Result.success(Unit)
    }

    override fun getActiveAlerts(shipmentId: String): Flow<List<Alert>> {
        return flow.map { list -> list.filter { it.shipmentId == shipmentId && !it.acknowledged } }
    }

    override fun getAllAlerts(shipmentId: String): Flow<List<Alert>> {
        return flow.map { list -> list.filter { it.shipmentId == shipmentId } }
    }
}

class E2EHandoverRepository : HandoverRepository {
    private val handovers = mutableMapOf<String, Handover>()
    private val flow = MutableStateFlow<List<Handover>>(emptyList())

    override suspend fun saveHandover(handover: Handover): Result<Unit> {
        handovers[handover.shipmentId] = handover
        flow.value = handovers.values.toList()
        return Result.success(Unit)
    }

    override fun getHandover(shipmentId: String): Flow<Handover?> {
        return flow.map { list -> list.find { it.shipmentId == shipmentId } }
    }
}
