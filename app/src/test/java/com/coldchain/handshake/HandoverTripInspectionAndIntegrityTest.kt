package com.coldchain.handshake

import com.coldchain.handshake.crypto.ShipmentIntegrityEvaluator
import com.coldchain.handshake.crypto.IntegrityVerificationState
import com.coldchain.handshake.safety.FakeShipmentRepository
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.models.Handover
import kotlinx.coroutines.flow.map

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

    // =========================================================================
    // AUTHORITATIVE REGRESSION TESTS FOR A -> B HANDOFF FALSE TAMPER FIX
    // =========================================================================

    /**
     * TEST 1: Complete valid chain verifies.
     */
    @Test
    fun `test1_completeValidChain_verifiesSuccessfully`() {
        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-04", testShipment.id, testShipment.loggerId, baseTs + 180_000L, 5.2, "", "", SyncStatus.SYNCED)
        )
        val validChain = HashChainService.buildChain(rawEvents)

        val eval = ShipmentIntegrityEvaluator.evaluate(validChain, isSyncComplete = true)
        assertEquals(IntegrityVerificationState.VERIFIED, eval.state)
        assertEquals("✓ HASH CHAIN VERIFIED", eval.title)
        assertTrue(eval.message.contains("4 immutable SHA-256 telemetry blocks verified"))
        assertTrue(eval.isEligibleForHandover)
        assertNotNull(eval.integrityResult)
        assertTrue(eval.integrityResult!!.valid)
    }

    /**
     * TEST 2: Actually corrupted complete chain produces TAMPER DETECTED.
     */
    @Test
    fun `test2_actuallyCorruptedCompleteChain_producesTamperDetected`() {
        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-04", testShipment.id, testShipment.loggerId, baseTs + 180_000L, 5.2, "", "", SyncStatus.SYNCED)
        )
        val validChain = HashChainService.buildChain(rawEvents)

        // Mutate TE-03 temperature without re-hashing
        val tamperedChain = validChain.mapIndexed { idx, ev ->
            if (idx == 2) ev.copy(temperature = 99.9) else ev
        }

        val eval = ShipmentIntegrityEvaluator.evaluate(tamperedChain, isSyncComplete = true)
        assertEquals(IntegrityVerificationState.TAMPER_DETECTED, eval.state)
        assertEquals("✗ TAMPER DETECTED", eval.title)
        assertTrue(eval.message.contains("CURRENT_HASH_MISMATCH"))
        assertTrue(eval.message.contains("TE-03"))
        assertFalse(eval.isEligibleForHandover)
    }

    /**
     * TEST 3: Partial suffix of a valid chain does NOT produce TAMPER DETECTED.
     * Full: E1 -> E2 -> E3 -> E4
     * Partial: E3 -> E4
     * Expected: INTEGRITY NOT VERIFIABLE / HISTORY SYNCING
     */
    @Test
    fun `test3_partialSuffixOfValidChain_doesNotProduceTamperDetected`() {
        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-04", testShipment.id, testShipment.loggerId, baseTs + 180_000L, 5.2, "", "", SyncStatus.SYNCED)
        )
        val fullChain = HashChainService.buildChain(rawEvents)

        // Phone B temporarily holds only suffix E3 -> E4
        val partialSuffix = fullChain.drop(2)
        assertEquals(2, partialSuffix.size)
        assertEquals("TE-03", partialSuffix[0].id)
        assertFalse("Suffix previousHash is not Genesis constant", partialSuffix[0].previousHash == HashChainService.GENESIS_HASH)

        // Must NEVER display TAMPER DETECTED
        val eval = ShipmentIntegrityEvaluator.evaluate(partialSuffix, isSyncComplete = false)
        assertEquals(IntegrityVerificationState.PENDING_SYNC, eval.state)
        assertFalse("Must never report TAMPER_DETECTED on incomplete suffix", eval.state == IntegrityVerificationState.TAMPER_DETECTED)
        assertEquals("INTEGRITY VERIFICATION PENDING", eval.title)
        assertTrue(eval.message.contains("Receiving complete shipment history from cloud (genesis block pending)"))
        assertFalse(eval.isEligibleForHandover)
    }

    /**
     * TEST 4: After E1 and E2 are imported, E1->E2->E3->E4 verifies successfully.
     */
    @Test
    fun `test4_afterMissingEventsImported_chainVerifiesSuccessfully`() {
        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-04", testShipment.id, testShipment.loggerId, baseTs + 180_000L, 5.2, "", "", SyncStatus.SYNCED)
        )
        val fullChain = HashChainService.buildChain(rawEvents)

        // 1. Incomplete suffix
        val partialSuffix = fullChain.drop(2)
        val pendingEval = ShipmentIntegrityEvaluator.evaluate(partialSuffix, isSyncComplete = false)
        assertEquals(IntegrityVerificationState.PENDING_SYNC, pendingEval.state)

        // 2. E1 and E2 are imported
        val completeImported = (fullChain.take(2) + partialSuffix).sortedBy { it.timestamp }
        val readyEval = ShipmentIntegrityEvaluator.evaluate(completeImported, isSyncComplete = true)
        assertEquals(IntegrityVerificationState.VERIFIED, readyEval.state)
        assertEquals("✓ HASH CHAIN VERIFIED", readyEval.title)
        assertTrue(readyEval.isEligibleForHandover)
    }

    /**
     * TEST 5: Phone B imports the same event multiple times.
     * Expected: No duplicates and chain remains valid.
     */
    @Test
    fun `test5_phoneBImportsSameEventMultipleTimes_noDuplicatesAndValid`() = runBlocking {
        val repo = InMemoryTelemetryRepository()
        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED)
        )
        val chain = HashChainService.buildChain(rawEvents)

        // Batch 1
        repo.saveTemperatures(chain)
        // Repeat batch (idempotent retry)
        repo.saveTemperatures(chain)
        // Single row retry
        repo.saveTemperature(chain[0])

        val stored = repo.getTemperatures(testShipment.id).first()
        assertEquals("Duplicate imports must be ignored without duplicate entities", 2, stored.size)

        val eval = ShipmentIntegrityEvaluator.evaluate(stored, isSyncComplete = true)
        assertEquals(IntegrityVerificationState.VERIFIED, eval.state)
    }

    /**
     * TEST 6: Phone B receives events in arbitrary remote response order.
     * Expected: Room stores deterministic chronological order and integrity remains valid.
     */
    @Test
    fun `test6_phoneBReceivesEventsInArbitraryOrder_storedDeterministicallyAndValid`() = runBlocking {
        val repo = InMemoryTelemetryRepository()
        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-04", testShipment.id, testShipment.loggerId, baseTs + 180_000L, 5.2, "", "", SyncStatus.SYNCED)
        )
        val chain = HashChainService.buildChain(rawEvents)

        // Replicate arbitrary remote response ordering: [E4, E1, E3, E2]
        val scrambled = listOf(chain[3], chain[0], chain[2], chain[1])
        repo.saveTemperatures(scrambled)

        val stored = repo.getTemperatures(testShipment.id).first()
        assertEquals(4, stored.size)
        assertEquals("TE-01", stored[0].id)
        assertEquals("TE-02", stored[1].id)
        assertEquals("TE-03", stored[2].id)
        assertEquals("TE-04", stored[3].id)

        val eval = ShipmentIntegrityEvaluator.evaluate(stored, isSyncComplete = true)
        assertEquals(IntegrityVerificationState.VERIFIED, eval.state)
    }

    /**
     * TEST 7: Phone A continues producing events while Phone B is polling.
     * Expected: B eventually converges to the same immutable telemetry history without generating its own competing events.
     */
    @Test
    fun `test7_phoneAContinuesProducingEvents_phoneBConvergesWithoutCompetingEvents`() = runBlocking {
        val repoA = InMemoryTelemetryRepository()
        val repoB = InMemoryTelemetryRepository()
        val simA = TemperatureSimulator(telemetryRepository = repoA)

        val baseTs = 1700000000000L
        simA.attachShipment(testShipment, startTimestamp = baseTs)

        // Phone A emits 3 readings
        for (i in 1..3) simA.tickOnce(60L)
        val batch1 = repoA.getTemperatures(testShipment.id).first()

        // Phone B polls batch 1
        repoB.saveTemperatures(batch1)

        // Phone A emits 2 more readings
        for (i in 1..2) simA.tickOnce(60L)
        val batch2 = repoA.getTemperatures(testShipment.id).first()

        // Phone B polls batch 2
        repoB.saveTemperatures(batch2)

        val finalA = repoA.getTemperatures(testShipment.id).first()
        val finalB = repoB.getTemperatures(testShipment.id).first()

        assertEquals(5, finalA.size)
        assertEquals(5, finalB.size)

        for (i in 0 until 5) {
            assertEquals("Event ID at index $i must match", finalA[i].id, finalB[i].id)
            assertEquals("Timestamp at index $i must match", finalA[i].timestamp, finalB[i].timestamp)
            assertEquals("Temperature at index $i must match", finalA[i].temperature, finalB[i].temperature, 0.0001)
            assertEquals("previousHash at index $i must match", finalA[i].previousHash, finalB[i].previousHash)
            assertEquals("currentHash at index $i must match", finalA[i].currentHash, finalB[i].currentHash)
        }

        val evalB = ShipmentIntegrityEvaluator.evaluate(finalB, isSyncComplete = true)
        assertEquals(IntegrityVerificationState.VERIFIED, evalB.state)
    }

    /**
     * TEST 8: B does not call the temperature simulator while in monitor-only mode.
     */
    @Test
    fun `test8_phoneBMonitorOnlyMode_doesNotCallSimulatorOrEmitEvents`() = runBlocking {
        val repoB = InMemoryTelemetryRepository()
        val simB = TemperatureSimulator(telemetryRepository = repoB)

        simB.attachShipmentForMonitoring(testShipment)
        assertTrue("Simulator must be in monitor-only mode", simB.isMonitorOnly.value)

        val tickResult = simB.tickOnce(60L)
        assertNull("tickOnce must return null in monitor mode", tickResult)

        simB.startContinuousSimulation(intervalMs = 50L)
        assertFalse("Continuous simulation must not activate in monitor mode", simB.isRunning.value)

        val stored = repoB.getTemperatures(testShipment.id).first()
        assertTrue("No telemetry should ever be generated by monitor device", stored.isEmpty())
    }

    /**
     * TEST 9: Handover opened while history is still loading.
     * Expected: "History syncing / integrity pending", NOT tamper.
     */
    @Test
    fun `test9_handoverOpenedWhileHistoryLoading_showsSyncPendingNotTamper`() {
        val evalEmpty = ShipmentIntegrityEvaluator.evaluate(emptyList(), isSyncComplete = false)
        assertEquals(IntegrityVerificationState.PENDING_SYNC, evalEmpty.state)
        assertEquals("INTEGRITY VERIFICATION PENDING", evalEmpty.title)
        assertFalse(evalEmpty.state == IntegrityVerificationState.TAMPER_DETECTED)

        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED)
        )
        val chain = HashChainService.buildChain(rawEvents)

        val evalSyncing = ShipmentIntegrityEvaluator.evaluate(chain, isSyncComplete = false)
        assertEquals(IntegrityVerificationState.PENDING_SYNC, evalSyncing.state)
        assertEquals("HISTORY SYNCING", evalSyncing.title)
        assertFalse(evalSyncing.state == IntegrityVerificationState.TAMPER_DETECTED)
    }

    /**
     * TEST 10: After history hydration completes, clean chain becomes HASH CHAIN VERIFIED.
     */
    @Test
    fun `test10_afterHistoryHydrationCompletes_cleanChainBecomesVerified`() {
        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED)
        )
        val chain = HashChainService.buildChain(rawEvents)

        val evalSyncing = ShipmentIntegrityEvaluator.evaluate(chain, isSyncComplete = false)
        assertEquals(IntegrityVerificationState.PENDING_SYNC, evalSyncing.state)

        val evalReady = ShipmentIntegrityEvaluator.evaluate(chain, isSyncComplete = true)
        assertEquals(IntegrityVerificationState.VERIFIED, evalReady.state)
        assertEquals("✓ HASH CHAIN VERIFIED", evalReady.title)
    }

    /**
     * TEST 11: Historical Handover for a clean shipment eventually reaches PASS.
     */
    @Test
    fun `test11_historicalHandoverForCleanShipment_reachesPass`() = runBlocking {
        val sRepo = FakeShipmentRepository()
        val tRepo = InMemoryTelemetryRepository()
        val hRepo = LocalTestHandoverRepository()

        sRepo.saveShipment(testShipment)

        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED)
        )
        val chain = HashChainService.buildChain(rawEvents)
        tRepo.saveTemperatures(chain)

        val orchestrator = HandoverOrchestrator(
            shipmentRepository = sRepo,
            telemetryRepository = tRepo,
            handoverRepository = hRepo
        )

        val result = orchestrator.processHandover(
            shipmentId = testShipment.id,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue("Clean shipment handover must succeed", result.isSuccess)
        val orchestratorResult = result.getOrNull()!!
        assertEquals(HandoverVerdict.PASS, orchestratorResult.handover.verdict)
        assertEquals(ShipmentStatus.ACCEPTED, orchestratorResult.updatedShipment.status)
    }

    /**
     * TEST 12: Historical Handover for a deliberately corrupted shipment still reaches FAIL / TAMPER DETECTED.
     */
    @Test
    fun `test12_historicalHandoverForCorruptedShipment_reachesFailTamperDetected`() = runBlocking {
        val sRepo = FakeShipmentRepository()
        val tRepo = InMemoryTelemetryRepository()
        val hRepo = LocalTestHandoverRepository()

        sRepo.saveShipment(testShipment)

        val baseTs = 1700000000000L
        val rawEvents = listOf(
            TemperatureEvent("TE-01", testShipment.id, testShipment.loggerId, baseTs, 4.5, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-02", testShipment.id, testShipment.loggerId, baseTs + 60_000L, 4.7, "", "", SyncStatus.SYNCED),
            TemperatureEvent("TE-03", testShipment.id, testShipment.loggerId, baseTs + 120_000L, 5.0, "", "", SyncStatus.SYNCED)
        )
        val chain = HashChainService.buildChain(rawEvents)
        val corruptedChain = chain.mapIndexed { idx, ev ->
            if (idx == 1) ev.copy(temperature = 99.9) else ev
        }
        tRepo.saveTemperatures(corruptedChain)

        val orchestrator = HandoverOrchestrator(
            shipmentRepository = sRepo,
            telemetryRepository = tRepo,
            handoverRepository = hRepo
        )

        val result = orchestrator.processHandover(
            shipmentId = testShipment.id,
            temperaturePassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertTrue(result.isSuccess)
        val orchestratorResult = result.getOrNull()!!
        assertEquals(HandoverVerdict.FAIL, orchestratorResult.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, orchestratorResult.updatedShipment.status)
        assertTrue(orchestratorResult.evaluation.failureReasons.contains(HandoverFailureReason.INTEGRITY_FAILED))
    }

    private class LocalTestHandoverRepository : HandoverRepository {
        private val handovers = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Handover>>(emptyMap())

        override suspend fun saveHandover(handover: Handover): Result<Unit> {
            handovers.value = handovers.value + (handover.shipmentId to handover)
            return Result.success(Unit)
        }

        override fun getHandover(shipmentId: String): kotlinx.coroutines.flow.Flow<Handover?> {
            return handovers.map { it[shipmentId] }
        }
    }
}
