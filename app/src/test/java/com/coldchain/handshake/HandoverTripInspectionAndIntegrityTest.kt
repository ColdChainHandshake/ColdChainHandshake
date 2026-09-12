package com.coldchain.handshake

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.IntegrityFailureReason
import com.coldchain.handshake.handover.HandoverVerdictEngine
import com.coldchain.handshake.handover.HandoverFailureReason
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.safety.BreachDetector
import com.coldchain.handshake.safety.FakeAlertRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Validates:
 * 1. CASE A: Clean shipment produces valid hash chain and passes integrity.
 * 2. CASE B: Corrupted shipment fails integrity with TAMPER DETECTED.
 * 3. Simulator re-attach preserves chronological ordering and hash continuity without rewind.
 * 4. Handover verdict requires all 4 criteria (temperature, integrity, worker, pharmacist).
 * 5. Historical trip metrics calculation (min, max, cumulative breach duration).
 * 6. Alert logs are strictly shipment-scoped.
 */
class HandoverTripInspectionAndIntegrityTest {

    private val testShipment = Shipment(
        id = "SHIP-INSPECT-01",
        qrCode = "CCH:SHIP:SHIP-INSPECT-01:Hub:Dest:LOG-100",
        origin = "Central Cold Hub",
        destination = "Regional Pharmacy",
        workerId = "W-COURIER-01",
        loggerId = "LOG-100",
        status = ShipmentStatus.IN_TRANSIT
    )

    @Test
    fun `testCaseA_cleanShipment_verifiesHashChainIntegrity`() = runBlocking {
        val telemetryRepo = InMemoryTelemetryRepository()
        val simulator = TemperatureSimulator(telemetryRepository = telemetryRepo)

        simulator.attachShipment(testShipment, startTimestamp = 1700000000000L)

        // Generate 5 normal temperature events
        for (i in 1..5) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull("Event $i should be generated", event)
        }

        val events = telemetryRepo.getTemperatures(testShipment.id).first()
        assertEquals(5, events.size)

        // Verify SHA-256 hash chain
        val integrityResult = HashChainService.verifyChain(events)
        assertTrue("Clean shipment must verify successfully", integrityResult.valid)
        assertNull("Corrupted event ID must be null on clean chain", integrityResult.corruptedEventId)
        assertNull("Failure reason must be null on clean chain", integrityResult.reason)
        assertTrue("Verification message should indicate success", integrityResult.message.contains("verified successfully"))
    }

    @Test
    fun `testCaseB_tamperedEvent_detectedAsTamper`() = runBlocking {
        val telemetryRepo = InMemoryTelemetryRepository()
        val simulator = TemperatureSimulator(telemetryRepository = telemetryRepo)

        simulator.attachShipment(testShipment, startTimestamp = 1700000000000L)

        for (i in 1..5) {
            simulator.tickOnce(simulatedStepDurationSeconds = 60L)
        }

        val originalEvents = telemetryRepo.getTemperatures(testShipment.id).first()
        assertEquals(5, originalEvents.size)

        // Mutate the 3rd event's temperature without recomputing hashes (simulating tamper)
        val tamperedEvents = originalEvents.mapIndexed { index, event ->
            if (index == 2) event.copy(temperature = 99.9) else event
        }

        val integrityResult = HashChainService.verifyChain(tamperedEvents)
        assertFalse("Tampered chain must fail verification", integrityResult.valid)
        assertEquals("Tampered event must be identified", tamperedEvents[2].id, integrityResult.corruptedEventId)
        assertEquals(IntegrityFailureReason.CURRENT_HASH_MISMATCH, integrityResult.reason)
        assertTrue(integrityResult.message.contains("Cryptographic integrity failure"))
    }

    @Test
    fun `testSimulatorReattach_doesNotRewindTimestampOrBreakHashChain`() = runBlocking {
        val telemetryRepo = InMemoryTelemetryRepository()
        val simulator = TemperatureSimulator(telemetryRepository = telemetryRepo)

        val initialTime = 1700000000000L
        simulator.attachShipment(testShipment, startTimestamp = initialTime)

        // Generate 3 readings: timestamps will be T+60s, T+120s, T+180s
        for (i in 1..3) {
            simulator.tickOnce(simulatedStepDurationSeconds = 60L)
        }

        val firstBatch = telemetryRepo.getTemperatures(testShipment.id).first()
        assertEquals(3, firstBatch.size)
        val lastTsFirstBatch = firstBatch.last().timestamp

        // Simulate re-attaching shipment (e.g. user toggles Dispatch -> Start Journey)
        // startTimestamp is earlier than the latest event (e.g. real wall-clock time)
        simulator.attachShipment(testShipment, startTimestamp = initialTime + 10_000L)

        // Generate 2 more readings
        for (i in 1..2) {
            simulator.tickOnce(simulatedStepDurationSeconds = 60L)
        }

        val fullBatch = telemetryRepo.getTemperatures(testShipment.id).first()
        assertEquals(5, fullBatch.size)

        // Assert strictly monotonic timestamps
        for (i in 1 until fullBatch.size) {
            assertTrue(
                "Event timestamp must be strictly greater than preceding event",
                fullBatch[i].timestamp > fullBatch[i - 1].timestamp
            )
        }

        // Verify the entire chain is valid
        val integrityResult = HashChainService.verifyChain(fullBatch)
        assertTrue("Hash chain must remain valid across re-attachments", integrityResult.valid)
    }

    @Test
    fun `testHandoverVerdict_requiresAllFourConditions`() {
        // All 4 pass -> PASS & ACCEPTED
        val passEval = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )
        assertEquals(HandoverVerdict.PASS, passEval.verdict)
        assertEquals(ShipmentStatus.ACCEPTED, passEval.targetStatus)
        assertTrue(passEval.failureReasons.isEmpty())

        // Integrity failed -> FAIL & QUARANTINED
        val tamperEval = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = false,
            workerSigned = true,
            pharmacistSigned = true
        )
        assertEquals(HandoverVerdict.FAIL, tamperEval.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, tamperEval.targetStatus)
        assertTrue(tamperEval.failureReasons.contains(HandoverFailureReason.INTEGRITY_FAILED))

        // Temperature failed -> FAIL & QUARANTINED
        val breachEval = HandoverVerdictEngine.evaluate(
            temperaturePassed = false,
            integrityPassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )
        assertEquals(HandoverVerdict.FAIL, breachEval.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, breachEval.targetStatus)
        assertTrue(breachEval.failureReasons.contains(HandoverFailureReason.TEMPERATURE_FAILED))

        // Signature missing -> FAIL
        val sigEval = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = true,
            workerSigned = false,
            pharmacistSigned = true
        )
        assertEquals(HandoverVerdict.FAIL, sigEval.verdict)
        assertTrue(sigEval.failureReasons.contains(HandoverFailureReason.WORKER_SIGNATURE_MISSING))
    }

    @Test
    fun `testHistoricalTripSummary_derivedMetricsCorrectness`() {
        val baseTs = 1700000000000L
        val events = listOf(
            TemperatureEvent("TE-1", "SHIP-01", "LOG-1", baseTs, 4.5, "GENESIS", "H1", SyncStatus.SYNCED),
            TemperatureEvent("TE-2", "SHIP-01", "LOG-1", baseTs + 60_000L, 5.2, "H1", "H2", SyncStatus.SYNCED),
            TemperatureEvent("TE-3", "SHIP-01", "LOG-1", baseTs + 120_000L, 9.8, "H2", "H3", SyncStatus.SYNCED), // 1 min excursion
            TemperatureEvent("TE-4", "SHIP-01", "LOG-1", baseTs + 180_000L, 4.8, "H3", "H4", SyncStatus.SYNCED)
        )

        val minTemp = events.minOf { it.temperature }
        val maxTemp = events.maxOf { it.temperature }
        assertEquals(4.5, minTemp, 0.001)
        assertEquals(9.8, maxTemp, 0.001)

        val breachEval = BreachDetector.evaluateTelemetry(events)
        // Excursion was from T+120s to T+180s (60 seconds = 1 min). Less than 5 min threshold.
        assertEquals(60_000L, breachEval.cumulativeBreachDurationMs)
        assertTrue("Excursion under 5 min must pass temperature safety", breachEval.temperaturePassed)
        assertFalse("Should not be breached under 5 min", breachEval.hasBreach)
        assertEquals("01:00", BreachDetector.formatDuration(breachEval.cumulativeBreachDurationMs))
    }

    @Test
    fun `testAlertHistory_strictlyScopedToShipment`() = runBlocking {
        val alertRepo = FakeAlertRepository()

        val alertA = Alert(
            id = "ALT-A1",
            shipmentId = "SHIP-ALPHA",
            type = AlertType.TEMPERATURE_BREACH,
            message = "Breach on Alpha",
            timestamp = 1700000010000L,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false
        )
        val alertB = Alert(
            id = "ALT-B1",
            shipmentId = "SHIP-BETA",
            type = AlertType.LOGGER_DISCONNECT,
            message = "Disconnect on Beta",
            timestamp = 1700000020000L,
            escalationLevel = EscalationLevel.SUPERVISOR,
            acknowledged = true
        )

        alertRepo.saveAlert(alertA)
        alertRepo.saveAlert(alertB)

        val alphaAlerts = alertRepo.getAllAlerts("SHIP-ALPHA").first()
        assertEquals(1, alphaAlerts.size)
        assertEquals("ALT-A1", alphaAlerts[0].id)
        assertEquals("SHIP-ALPHA", alphaAlerts[0].shipmentId)

        val betaAlerts = alertRepo.getAllAlerts("SHIP-BETA").first()
        assertEquals(1, betaAlerts.size)
        assertEquals("ALT-B1", betaAlerts[0].id)
        assertEquals("SHIP-BETA", betaAlerts[0].shipmentId)

        val gammaAlerts = alertRepo.getAllAlerts("SHIP-GAMMA").first()
        assertTrue("Unrelated shipment must have empty alert history", gammaAlerts.isEmpty())
    }
}
