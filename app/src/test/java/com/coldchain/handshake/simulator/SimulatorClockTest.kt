package com.coldchain.handshake.simulator

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.safety.BreachDetector
import com.coldchain.handshake.safety.FakeAlertRepository
import com.coldchain.handshake.safety.FakeShipmentRepository
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.safety.TemperatureVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorClockTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private var now = t0

    private val shipment = Shipment(
        "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id, t0
    )

    private val telemetry = InMemoryTelemetryRepository()
    private val shipments = FakeShipmentRepository().apply { runBlocking { saveShipment(shipment) } }
    private val simulator = TemperatureSimulator(
        telemetryRepository = telemetry,
        dispatcher = Dispatchers.Unconfined,
        safetyEngine = SafetyEngine(FakeAlertRepository(), shipments),
        wallClock = { now }
    ).apply { attachShipment(shipment, startTimestamp = t0) }

    private suspend fun readings() = telemetry.getTemperatures("SHIP-1").first()

    @Test
    fun aReadingIsDatedByTheClock() = runBlocking {
        now = t0 + 5 * minute

        assertEquals(t0 + 5 * minute, simulator.tickOnce(60L)?.timestamp)
    }

    @Test
    fun noReadingIsEverDatedInTheFuture() = runBlocking {
        repeat(20) {
            simulator.tickOnce(60L)
            now += 1_500L
        }

        assertTrue(readings().all { it.timestamp <= now })
    }

    @Test
    fun ticksCloserThanOneStepAddNothing() = runBlocking {
        assertNotNull(simulator.tickOnce(60L))

        now += minute - 1

        assertNull(simulator.tickOnce(60L))
        assertEquals(1, readings().size)
    }

    @Test
    fun readingsStayAValidChainAtTheClocksPace() = runBlocking {
        repeat(5) {
            simulator.tickOnce(60L)
            now += minute
        }

        assertTrue(HashChainService.verifyChain(readings(), HashChainService.UNBOUND_GENESIS).valid)
    }

    @Test
    fun aLoggerThatStoppedLeavesARealGap() = runBlocking {
        simulator.tickOnce(60L)

        now += 60 * minute
        simulator.tickOnce(60L)

        val verdict = BreachDetector.evaluateTelemetry(readings(), ProductProfiles.DEFAULT).verdict
        assertEquals(TemperatureVerdict.UNKNOWN, verdict)
    }

    @Test
    fun aHandoverStopsTheLoggerForThatShipment() = runBlocking {
        simulator.tickOnce(60L)

        simulator.onHandedOver(shipment.copy(status = ShipmentStatus.ACCEPTED))

        assertEquals(ShipmentStatus.ACCEPTED, simulator.activeShipment.value?.status)
        assertTrue(simulator.isMonitorOnly.value)
    }

    @Test
    fun aHandoverOfAnotherShipmentLeavesThisOneRunning() {
        simulator.onHandedOver(shipment.copy(id = "SHIP-2", status = ShipmentStatus.ACCEPTED))

        assertEquals(ShipmentStatus.IN_TRANSIT, simulator.activeShipment.value?.status)
    }

    @Test
    fun aQuarantineThatCouldNotBeSavedIsReportedAndNotShownAsDone() = runBlocking {
        simulator.setHeatSpikeModeSync(true)
        shipments.shouldFail = true

        repeat(8) {
            simulator.tickOnce(60L)
            now += minute
        }

        assertEquals(ShipmentStatus.IN_TRANSIT, simulator.activeShipment.value?.status)
        assertNotNull(simulator.lastPersistenceError.value)
    }
}
