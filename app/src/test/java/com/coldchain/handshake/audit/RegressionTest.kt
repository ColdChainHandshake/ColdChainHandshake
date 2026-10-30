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
import org.junit.Test

/**
 * Bugs found in the code review, now fixed. These started in [AuditFindingsTest], asserting that each
 * weakness was present, and were flipped when the fix landed. Numbers match the review's findings.
 */
class RegressionTest {

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
    fun finding3_freezingFailsAFreezeSensitiveProduct() {
        // Three hours at -3 C: catastrophic for freeze-sensitive vaccines
        val frozen = HashChainService.buildChain(rawEvents(List(181) { -3.0 }))
        val eval = BreachDetector.evaluateTelemetry(frozen)
        assertEquals(TemperatureVerdict.FAIL, eval.verdict)
        assertFalse("freeze excursion must not pass", eval.temperaturePassed)
        assertEquals(180 * minute, eval.cumulativeBreachDurationMs)
    }

    @Test
    fun finding6_dataGapsAreUnknownNotSafe() {
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
    fun finding7_oneExcursionCreatesOneAlert() = runTest {
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

        assertEquals(1, alerts.savedAlerts.count { it.type == AlertType.TEMPERATURE_BREACH })
    }
}
