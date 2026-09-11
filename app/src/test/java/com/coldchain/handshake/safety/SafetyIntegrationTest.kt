package com.coldchain.handshake.safety

import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SafetyIntegrationTest {

    private lateinit var fakeAlertRepo: FakeAlertRepository
    private lateinit var fakeShipmentRepo: FakeShipmentRepository
    private lateinit var safetyEngine: SafetyEngine

    private val sampleShipment = Shipment(
        id = "SHIP-777",
        qrCode = "QR-777-DATA",
        loggerId = "LOG-777",
        origin = "Regional Distribution Center",
        destination = "St. Jude Clinic",
        workerId = "WORKER-42",
        status = ShipmentStatus.IN_TRANSIT
    )

    @Before
    fun setUp() {
        fakeAlertRepo = FakeAlertRepository()
        fakeShipmentRepo = FakeShipmentRepository()
        safetyEngine = SafetyEngine(
            alertRepository = fakeAlertRepo,
            shipmentRepository = fakeShipmentRepo
        )
    }

    private fun createEvent(
        id: String,
        timestamp: Long,
        temperature: Double,
        shipmentId: String = "SHIP-777"
    ): TemperatureEvent {
        return TemperatureEvent(
            id = id,
            shipmentId = shipmentId,
            loggerId = "LOG-777",
            timestamp = timestamp,
            temperature = temperature,
            previousHash = "prev-hash",
            currentHash = "curr-hash",
            syncStatus = SyncStatus.SYNCED
        )
    }

    @Test
    fun confirmedBreachSavesAlertAndQuarantinesShipmentPreservingFields() = runBlocking {
        fakeShipmentRepo.saveShipment(sampleShipment)

        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.2),
            createEvent("e2", baseTime + 301_000L, 5.0) // 5m 1s hot excursion
        )

        val decision = safetyEngine.processTelemetryAndEnforceSafety(sampleShipment, events)

        // 1. Verify breach decision
        assertTrue(decision.evaluation.hasBreach)
        assertFalse(decision.evaluation.temperaturePassed)
        assertTrue(decision.shouldQuarantine)
        assertFalse("Persistence succeeded without failure", decision.hasPersistenceFailure)

        // 2. Verify alert persisted to repository
        assertEquals(1, fakeAlertRepo.savedAlerts.size)
        val alert = fakeAlertRepo.savedAlerts.first()
        assertEquals("SHIP-777", alert.shipmentId)
        assertEquals(AlertType.TEMPERATURE_BREACH, alert.type)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse("Alert must initially be unacknowledged", alert.acknowledged)

        // 3. Verify active alerts flow returns this alert
        val activeAlerts = fakeAlertRepo.getActiveAlerts("SHIP-777").first()
        assertEquals(1, activeAlerts.size)

        // 4. Verify shipment transitioned to QUARANTINED in repository
        val updatedShipment = fakeShipmentRepo.getShipment("SHIP-777").first()
        assertEquals(ShipmentStatus.QUARANTINED, updatedShipment?.status)

        // 5. Verify all other shipment fields were strictly preserved
        assertEquals(sampleShipment.id, updatedShipment?.id)
        assertEquals(sampleShipment.qrCode, updatedShipment?.qrCode)
        assertEquals(sampleShipment.loggerId, updatedShipment?.loggerId)
        assertEquals(sampleShipment.origin, updatedShipment?.origin)
        assertEquals(sampleShipment.destination, updatedShipment?.destination)
        assertEquals(sampleShipment.workerId, updatedShipment?.workerId)
    }

    @Test
    fun alertPersistenceFailureIsSurfacedNotSwallowed() = runBlocking {
        fakeShipmentRepo.saveShipment(sampleShipment)
        fakeAlertRepo.shouldFail = true
        fakeAlertRepo.failureException = IllegalStateException("Disk I/O error writing alert")

        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.5),
            createEvent("e2", baseTime + 305_000L, 5.0)
        )

        val decision = safetyEngine.processTelemetryAndEnforceSafety(sampleShipment, events)

        assertTrue("Breach must still be detected", decision.evaluation.hasBreach)
        assertTrue("Persistence failure must be flagged", decision.hasPersistenceFailure)
        assertNotNull("Alert save result must be recorded", decision.alertSaveResult)
        assertTrue("Alert save result must be failure", decision.alertSaveResult!!.isFailure)
        assertEquals("Disk I/O error writing alert", decision.alertSaveResult!!.exceptionOrNull()?.message)

        // Also test escalation persistence failure throws rather than silently swallowing
        val testAlert = AlertEscalationEngine.createBreachAlert("SHIP-777", decision.evaluation)
        try {
            safetyEngine.escalateAlert(testAlert)
            fail("Expected exception when alert repository fails to save")
        } catch (e: IllegalStateException) {
            assertEquals("Disk I/O error writing alert", e.message)
        }
    }

    @Test
    fun quarantinePersistenceFailureIsSurfacedNotSwallowed() = runBlocking {
        fakeShipmentRepo.saveShipment(sampleShipment)
        fakeShipmentRepo.shouldFail = true
        fakeShipmentRepo.failureException = IllegalStateException("Database locked")

        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.5),
            createEvent("e2", baseTime + 305_000L, 5.0)
        )

        val decision = safetyEngine.processTelemetryAndEnforceSafety(sampleShipment, events)

        assertTrue(decision.evaluation.hasBreach)
        assertTrue("Quarantine persistence failure must be surfaced", decision.hasPersistenceFailure)
        assertNotNull(decision.quarantineSaveResult)
        assertTrue(decision.quarantineSaveResult!!.isFailure)
        assertEquals("Database locked", decision.quarantineSaveResult!!.exceptionOrNull()?.message)

        // Also verify QuarantineManager.quarantineAndPersist returns Result.failure
        val qResult = QuarantineManager.quarantineAndPersist(sampleShipment, decision.evaluation, fakeShipmentRepo)
        assertTrue(qResult.isFailure)
        assertEquals("Database locked", qResult.exceptionOrNull()?.message)
    }

    @Test
    fun safeTelemetryDoesNotTriggerAlertOrQuarantinePersistence() = runBlocking {
        fakeShipmentRepo.saveShipment(sampleShipment)

        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 5.0),
            createEvent("e2", baseTime + 600_000L, 5.5)
        )

        val decision = safetyEngine.processTelemetryAndEnforceSafety(sampleShipment, events)

        assertFalse(decision.evaluation.hasBreach)
        assertTrue(decision.evaluation.temperaturePassed)
        assertFalse(decision.shouldQuarantine)
        assertFalse(decision.hasPersistenceFailure)

        // No alerts should be saved
        assertTrue(fakeAlertRepo.savedAlerts.isEmpty())

        // Shipment status must remain IN_TRANSIT
        val currentShipment = fakeShipmentRepo.getShipment("SHIP-777").first()
        assertEquals(ShipmentStatus.IN_TRANSIT, currentShipment?.status)
    }

    @Test
    fun singleIsolatedHotEventDoesNotTriggerAlertOrQuarantine() = runBlocking {
        fakeShipmentRepo.saveShipment(sampleShipment)

        // Single isolated spike at 10.5°C, but no subsequent event proving duration > 5m
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 10.5)
        )

        val decision = safetyEngine.processTelemetryAndEnforceSafety(sampleShipment, events)

        assertFalse("Single event must not trigger breach", decision.evaluation.hasBreach)
        assertTrue(decision.evaluation.temperaturePassed)
        assertFalse(decision.shouldQuarantine)
        assertTrue("No alert should be created or persisted", fakeAlertRepo.savedAlerts.isEmpty())
        assertEquals(ShipmentStatus.IN_TRANSIT, fakeShipmentRepo.getShipment("SHIP-777").first()?.status)
    }

    @Test
    fun networkDropoutDoesNotTriggerFalseBreachOrQuarantine() = runBlocking {
        fakeShipmentRepo.saveShipment(sampleShipment)

        // Normal readings before and after 10-minute network dropout
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 5.0),
            createEvent("e2", baseTime + 120_000L, 9.0), // 2m hot spike
            createEvent("e3", baseTime + 240_000L, 5.0), // back in range (hot duration = 2m)
            createEvent("e4", baseTime + 840_000L, 5.0)  // 10 minutes later after dropout
        )

        val decision = safetyEngine.processTelemetryAndEnforceSafety(sampleShipment, events)

        assertFalse("10-minute dropout must not create false breach", decision.evaluation.hasBreach)
        assertTrue(decision.evaluation.temperaturePassed)
        assertFalse(decision.shouldQuarantine)
        assertTrue(fakeAlertRepo.savedAlerts.isEmpty())
        assertEquals(ShipmentStatus.IN_TRANSIT, fakeShipmentRepo.getShipment("SHIP-777").first()?.status)
    }

    @Test
    fun deterministicEscalationPersistsUpdatesViaAlertRepository() = runBlocking {
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.5),
            createEvent("e2", baseTime + 310_000L, 5.0)
        )
        safetyEngine.processTelemetryAndEnforceSafety(sampleShipment, events)
        val initialAlert = fakeAlertRepo.savedAlerts.first()
        assertEquals(EscalationLevel.WORKER, initialAlert.escalationLevel)

        // Step 1: Escalate WORKER -> SUPERVISOR
        val supervisorAlert = safetyEngine.escalateAlert(initialAlert)
        assertEquals(EscalationLevel.SUPERVISOR, supervisorAlert.escalationLevel)
        assertEquals(1, fakeAlertRepo.savedAlerts.size)
        assertEquals(EscalationLevel.SUPERVISOR, fakeAlertRepo.savedAlerts.first().escalationLevel)

        // Step 2: Escalate SUPERVISOR -> PHARMACIST
        val pharmacistAlert = safetyEngine.escalateAlert(supervisorAlert)
        assertEquals(EscalationLevel.PHARMACIST, pharmacistAlert.escalationLevel)
        assertEquals(EscalationLevel.PHARMACIST, fakeAlertRepo.savedAlerts.first().escalationLevel)

        // Step 3: Terminal tier check - must not invent a fourth level
        val terminalAlert = safetyEngine.escalateAlert(pharmacistAlert)
        assertEquals(EscalationLevel.PHARMACIST, terminalAlert.escalationLevel)
        assertEquals(EscalationLevel.PHARMACIST, fakeAlertRepo.savedAlerts.first().escalationLevel)

        // Step 4: Acknowledgment
        val acknowledgedAlert = safetyEngine.acknowledgeAlert(terminalAlert)
        assertTrue(acknowledgedAlert.acknowledged)
        assertTrue(fakeAlertRepo.savedAlerts.first().acknowledged)

        // When acknowledged, active alerts flow should become empty
        val activeAlerts = fakeAlertRepo.getActiveAlerts("SHIP-777").first()
        assertTrue(activeAlerts.isEmpty())
    }

    @Test
    fun alreadyQuarantinedShipmentIsNotOverwrittenOrReSaved() = runBlocking {
        val quarantinedShipment = sampleShipment.copy(status = ShipmentStatus.QUARANTINED)
        fakeShipmentRepo.saveShipment(quarantinedShipment)

        val evaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 350_000L,
            temperaturePassed = false
        )

        val result = QuarantineManager.quarantineAndPersist(quarantinedShipment, evaluation, fakeShipmentRepo)
        assertTrue(result.isSuccess)
        assertEquals(ShipmentStatus.QUARANTINED, result.getOrNull()?.status)
    }

    @Test
    fun replacementRequestedShipmentIsNotRevertedToQuarantined() = runBlocking {
        val replacementShipment = sampleShipment.copy(status = ShipmentStatus.REPLACEMENT_REQUESTED)
        fakeShipmentRepo.saveShipment(replacementShipment)

        val evaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 400_000L,
            temperaturePassed = false
        )

        val result = QuarantineManager.quarantineAndPersist(replacementShipment, evaluation, fakeShipmentRepo)
        assertTrue(result.isSuccess)
        assertEquals(ShipmentStatus.REPLACEMENT_REQUESTED, result.getOrNull()?.status)
    }

    @Test
    fun explicitLoggerDisconnectSavesToAlertRepository() = runBlocking {
        val timestamp = 1_700_000_123_456L
        val alert = safetyEngine.handleLoggerDisconnect(
            shipmentId = "SHIP-777",
            loggerId = "LOG-777",
            reason = "Sensor hardware dropped connection",
            timestamp = timestamp
        )

        assertEquals(AlertType.LOGGER_DISCONNECT, alert.type)
        assertEquals("SHIP-777", alert.shipmentId)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse(alert.acknowledged)
        assertEquals(timestamp, alert.timestamp)

        // Verify persisted in repository
        assertEquals(1, fakeAlertRepo.savedAlerts.size)
        assertEquals(alert.id, fakeAlertRepo.savedAlerts.first().id)
    }

    @Test
    fun temperaturePassedAdheresStrictlyToHandoverRules() {
        val baseTime = 1_700_000_000_000L

        // 1. Safe history -> true
        val safeEvents = listOf(
            createEvent("e1", baseTime, 4.5),
            createEvent("e2", baseTime + 60_000L, 5.0)
        )
        assertTrue("Safe history must pass handover check",
            safetyEngine.evaluateHandoverTemperatureSafety(safeEvents))

        // 2. Exactly 8.0°C -> true
        val exact8Events = listOf(
            createEvent("e1", baseTime, 8.0),
            createEvent("e2", baseTime + 600_000L, 8.0)
        )
        assertTrue("8.0°C exact must pass handover check",
            safetyEngine.evaluateHandoverTemperatureSafety(exact8Events))

        // 3. Exactly 5:00 (300,000 ms) hot duration -> true
        val exact5MinHotEvents = listOf(
            createEvent("e1", baseTime, 9.0),
            createEvent("e2", baseTime + 300_000L, 5.0)
        )
        assertTrue("Exactly 5:00 hot must pass handover check",
            safetyEngine.evaluateHandoverTemperatureSafety(exact5MinHotEvents))

        // 4. >5:00 hot duration (300,001 ms) -> false
        val breachEvents = listOf(
            createEvent("e1", baseTime, 9.0),
            createEvent("e2", baseTime + 300_001L, 5.0)
        )
        assertFalse(">5:00 hot duration must fail handover check",
            safetyEngine.evaluateHandoverTemperatureSafety(breachEvents))
    }

    @Test
    fun pureEvaluationWorksWithoutRepositories() {
        // Pure evaluation engine constructed with no repositories
        val pureEngine = SafetyEngine()

        // 1. evaluateShipment on safe telemetry
        val safeEvents = listOf(
            createEvent("e1", 1_700_000_000_000L, 5.0),
            createEvent("e2", 1_700_000_060_000L, 5.5)
        )
        val safeDecision = pureEngine.evaluateShipment(sampleShipment, safeEvents)
        assertFalse(safeDecision.evaluation.hasBreach)
        assertTrue(safeDecision.evaluation.temperaturePassed)
        assertFalse(safeDecision.shouldQuarantine)
        assertEquals("00:00", safeDecision.breachDurationFormatted)
        assertNull(safeDecision.alertSaveResult)
        assertNull(safeDecision.quarantineSaveResult)
        assertFalse(safeDecision.hasPersistenceFailure)

        // 2. evaluateShipment on breached telemetry
        val breachEvents = listOf(
            createEvent("e1", 1_700_000_000_000L, 9.5),
            createEvent("e2", 1_700_000_305_000L, 5.0)
        )
        val breachDecision = pureEngine.evaluateShipment(sampleShipment, breachEvents)
        assertTrue(breachDecision.evaluation.hasBreach)
        assertFalse(breachDecision.evaluation.temperaturePassed)
        assertTrue(breachDecision.shouldQuarantine)
        assertEquals("05:05", breachDecision.breachDurationFormatted)
        assertNull(breachDecision.alertSaveResult)
        assertNull(breachDecision.quarantineSaveResult)
        assertFalse(breachDecision.hasPersistenceFailure)

        // 3. evaluateHandoverTemperatureSafety
        assertTrue(pureEngine.evaluateHandoverTemperatureSafety(safeEvents))
        assertFalse(pureEngine.evaluateHandoverTemperatureSafety(breachEvents))

        // 4. Pure factory helpers
        assertNull(pureEngine.createBreachAlertIfViolated(sampleShipment, safeEvents))
        val alert = pureEngine.createBreachAlertIfViolated(sampleShipment, breachEvents)
        assertNotNull(alert)
        assertEquals(EscalationLevel.WORKER, alert!!.escalationLevel)

        // 5. Pure quarantine transition helpers
        assertEquals(ShipmentStatus.IN_TRANSIT, pureEngine.applyQuarantineIfBreached(sampleShipment, safeEvents).status)
        assertEquals(ShipmentStatus.QUARANTINED, pureEngine.applyQuarantineIfBreached(sampleShipment, breachEvents).status)
    }

    @Test
    fun enforcementCannotSilentlySucceedWithoutRequiredRepositories() = runBlocking {
        // Enforcing operations attempted on an engine with no repositories
        val pureEngine = SafetyEngine()

        val baseTime = 1_700_000_000_000L
        val breachEvents = listOf(
            createEvent("e1", baseTime, 9.5),
            createEvent("e2", baseTime + 305_000L, 5.0)
        )

        // 1. processTelemetryAndEnforceSafety must NOT silently succeed without repositories
        val decision = pureEngine.processTelemetryAndEnforceSafety(sampleShipment, breachEvents)
        assertTrue("Breach must be detected", decision.evaluation.hasBreach)
        assertTrue("Enforcement without repositories must flag persistence failure", decision.hasPersistenceFailure)

        assertNotNull("Alert save result must be recorded as failure", decision.alertSaveResult)
        assertTrue("Alert save result must fail", decision.alertSaveResult!!.isFailure)
        assertTrue(decision.alertSaveResult!!.exceptionOrNull() is IllegalStateException)
        assertEquals("AlertRepository is required to persist breach alerts", decision.alertSaveResult!!.exceptionOrNull()?.message)

        assertNotNull("Quarantine save result must be recorded as failure", decision.quarantineSaveResult)
        assertTrue("Quarantine save result must fail", decision.quarantineSaveResult!!.isFailure)
        assertTrue(decision.quarantineSaveResult!!.exceptionOrNull() is IllegalStateException)
        assertEquals("ShipmentRepository is required to persist shipment quarantine", decision.quarantineSaveResult!!.exceptionOrNull()?.message)

        // 2. escalateAlert must throw IllegalStateException rather than silently succeed
        val alert = AlertEscalationEngine.createBreachAlert(sampleShipment.id, decision.evaluation)
        try {
            pureEngine.escalateAlert(alert)
            fail("Expected IllegalStateException when calling escalateAlert without AlertRepository")
        } catch (e: IllegalStateException) {
            assertEquals("AlertRepository is required to persist escalated alert", e.message)
        }

        // 3. acknowledgeAlert must throw IllegalStateException rather than silently succeed
        try {
            pureEngine.acknowledgeAlert(alert)
            fail("Expected IllegalStateException when calling acknowledgeAlert without AlertRepository")
        } catch (e: IllegalStateException) {
            assertEquals("AlertRepository is required to persist acknowledged alert", e.message)
        }

        // 4. handleLoggerDisconnect must throw IllegalStateException rather than silently succeed
        try {
            pureEngine.handleLoggerDisconnect(
                shipmentId = sampleShipment.id,
                loggerId = sampleShipment.loggerId
            )
            fail("Expected IllegalStateException when calling handleLoggerDisconnect without AlertRepository")
        } catch (e: IllegalStateException) {
            assertEquals("AlertRepository is required to persist disconnect alert", e.message)
        }
    }
}
