package com.coldchain.handshake.audit

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.safety.BreachDetector
import com.coldchain.handshake.safety.FakeAlertRepository
import com.coldchain.handshake.safety.FakeShipmentRepository
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.safety.TemperatureVerdict
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests written during the code review. Each one passes when the weakness it
 * describes is PRESENT in the current code, so they document behaviour rather
 * than guard it. Flip the assertions once the corresponding fix lands.
 */
class AuditFindingsTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun rawEvents(temps: List<Double>, stepMs: Long = minute): List<TemperatureEvent> =
        temps.mapIndexed { i, t ->
            TemperatureEvent(
                id = "TE-%03d".format(i),
                shipmentId = "SHIP-AUDIT",
                loggerId = "LOG-1",
                timestamp = t0 + i * stepMs,
                temperature = t,
                previousHash = "",
                currentHash = "",
                syncStatus = SyncStatus.PENDING
            )
        }

    @Test
    fun finding1_truncatingTheTailOfTheChainStillVerifies() {
        // 6 safe readings followed by 8 hot readings (a real >5 min breach)
        val chain = HashChainService.buildChain(rawEvents(List(6) { 5.0 } + List(8) { 10.5 }))
        assertTrue(BreachDetector.evaluateTelemetry(chain).hasBreach)

        // Drop the hot tail: nothing commits to the chain length or head hash
        val truncated = chain.take(6)
        assertTrue("truncated chain verifies", HashChainService.verifyChain(truncated).valid)
        assertTrue("and the breach is gone", BreachDetector.evaluateTelemetry(truncated).temperaturePassed)
    }

    @Test
    fun finding2_rewritingAndReHashingTheWholeChainVerifies() {
        val original = HashChainService.buildChain(rawEvents(List(6) { 5.0 } + List(8) { 10.5 }))
        // Anyone holding the data can edit values and recompute every hash: no key is involved
        val forged = HashChainService.buildChain(original.map { it.copy(temperature = 5.0) })
        assertTrue(HashChainService.verifyChain(forged).valid)
        assertEquals(original.map { it.id }, forged.map { it.id })
        assertTrue(BreachDetector.evaluateTelemetry(forged).temperaturePassed)
    }

    @Test
    fun finding3_freezingFailsAFreezeSensitiveProduct() {
        // Three hours at -3 C: catastrophic for freeze-sensitive vaccines
        val frozen = HashChainService.buildChain(rawEvents(List(181) { -3.0 }))
        val eval = BreachDetector.evaluateTelemetry(frozen)
        assertEquals(TemperatureVerdict.FAIL, eval.verdict)
        assertFalse("freeze excursion must not pass", eval.temperaturePassed)
        assertEquals(180 * minute, eval.cumulativeBreachDurationMs)
    }

    @Test
    fun finding4_dataGapsAreUnknownNotSafe() {
        // 5 C, then the logger is silent for 6 hours, then one hot reading, then safe again
        val events = HashChainService.buildChain(
            listOf(
                rawEvents(listOf(5.0))[0],
                rawEvents(listOf(5.0))[0].copy(id = "TE-B", timestamp = t0 + 6 * 60 * minute, temperature = 12.0),
                rawEvents(listOf(5.0))[0].copy(id = "TE-C", timestamp = t0 + 6 * 60 * minute + minute, temperature = 5.0)
            )
        )
        val eval = BreachDetector.evaluateTelemetry(events)
        assertEquals(TemperatureVerdict.UNKNOWN, eval.verdict)
        assertFalse("six unexplained hours must not pass", eval.temperaturePassed)
        assertEquals(minute, eval.cumulativeBreachDurationMs)
    }

    @Test
    fun finding5_everyTickAfterABreachCreatesAnotherBreachAlert() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val alerts = FakeAlertRepository()
        val shipments = FakeShipmentRepository()
        val shipment = Shipment("SHIP-STORM", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT)
        shipments.saveShipment(shipment)

        val sim = TemperatureSimulator(
            telemetryRepository = InMemoryTelemetryRepository(),
            chaosEngineService = null,
            dispatcher = dispatcher,
            safetyEngine = SafetyEngine(alerts, shipments)
        )
        sim.attachShipment(shipment, startTimestamp = t0)

        sim.setHeatSpikeModeSync(true)
        repeat(8) { sim.tickOnce(60) }          // 8 hot minutes -> breach
        sim.setHeatSpikeModeSync(false)
        repeat(20) { sim.tickOnce(60) }         // 20 normal minutes after cooling down

        val breachAlerts = alerts.savedAlerts.count { it.type == AlertType.TEMPERATURE_BREACH }
        println("TEMPERATURE_BREACH alerts created for one excursion: $breachAlerts")
        assertTrue("one excursion produced $breachAlerts alerts", breachAlerts > 15)
    }
}
