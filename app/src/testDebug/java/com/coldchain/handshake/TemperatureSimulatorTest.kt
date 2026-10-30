package com.coldchain.handshake

import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.impl.InMemoryChaosEngineService
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TemperatureSimulatorTest {

    private lateinit var telemetryRepository: InMemoryTelemetryRepository
    private lateinit var chaosEngineService: InMemoryChaosEngineService
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var simulator: TemperatureSimulator

    private val testShipment = Shipment(
        id = "SHIP-SIM-01",
        qrCode = "CCH:SHIP:SIM-01:LOG-902",
        loggerId = "LOG-902",
        origin = "Central Cold Hub",
        destination = "St. Jude Pharmacy",
        workerId = "W-14",
        status = ShipmentStatus.IN_TRANSIT
    )

    @Before
    fun setUp() {
        telemetryRepository = InMemoryTelemetryRepository()
        chaosEngineService = InMemoryChaosEngineService()
        simulator = TemperatureSimulator(
            telemetryRepository = telemetryRepository,
            chaosEngineService = chaosEngineService,
            dispatcher = testDispatcher
        )
    }

    @Test
    fun testNormalTemperatureReadings() = runTest(testDispatcher) {
        val startTime = 1700000000000L
        simulator.attachShipment(testShipment, startTimestamp = startTime)
        simulator.setHeatSpikeMode(false)

        for (i in 1..10) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull("Event must be generated", event)
            event?.let {
                assertEquals(testShipment.id, it.shipmentId)
                assertEquals(testShipment.loggerId, it.loggerId)
                assertEquals(startTime + (i * 60_000L), it.timestamp)
                // Verify normal operating range: 4°C–6°C
                assertTrue("Temperature ${it.temperature}°C must be >= 4.0", it.temperature >= 4.0)
                assertTrue("Temperature ${it.temperature}°C must be <= 6.0", it.temperature <= 6.0)
            }
        }

        // Verify events in telemetry repository
        val storedEvents = telemetryRepository.getTemperatures(testShipment.id).first()
        assertEquals(10, storedEvents.size)
    }

    @Test
    fun testHeatSpikeTemperatureReadings() = runTest(testDispatcher) {
        val startTime = 1700000000000L
        simulator.attachShipment(testShipment, startTimestamp = startTime)
        simulator.setHeatSpikeMode(true)

        for (i in 1..10) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull("Event must be generated", event)
            event?.let {
                assertEquals(testShipment.id, it.shipmentId)
                assertEquals(testShipment.loggerId, it.loggerId)
                assertEquals(startTime + (i * 60_000L), it.timestamp)
                // Verify heat spike operating range: 9°C–11°C (>8°C excursion)
                assertTrue("Temperature ${it.temperature}°C must be >= 9.0", it.temperature >= 9.0)
                assertTrue("Temperature ${it.temperature}°C must be <= 11.0", it.temperature <= 11.0)
                assertTrue("Must exceed safe threshold of 8.0°C", it.temperature > 8.0)
            }
        }

        // Verify events in telemetry repository
        val storedEvents = telemetryRepository.getTemperatures(testShipment.id).first()
        assertEquals(10, storedEvents.size)
    }

    @Test
    fun testDeterministicTimestampAdvancement() = runTest(testDispatcher) {
        val startTime = 1000_000L
        simulator.attachShipment(testShipment, startTimestamp = startTime)

        val e1 = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
        assertEquals(1000_000L + 60_000L, e1?.timestamp)

        val e2 = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
        assertEquals(1000_000L + 120_000L, e2?.timestamp)

        val e3 = simulator.tickOnce(simulatedStepDurationSeconds = 120L)
        assertEquals(1000_000L + 240_000L, e3?.timestamp)
    }

    @Test
    fun testChaosEngineHeatSpikeActivation() = runTest(testDispatcher) {
        simulator.attachShipment(testShipment)
        advanceUntilIdle()

        // Inject heat spike into shared chaos engine service
        chaosEngineService.injectScenario(ChaosScenarioType.HEAT_SPIKE)
        advanceUntilIdle()

        assertTrue("Simulator must switch to heat spike mode", simulator.isHeatSpikeMode.value)

        val event = simulator.tickOnce()
        assertNotNull(event)
        assertTrue("Event must be in heat spike range (>= 9.0°C)", (event?.temperature ?: 0.0) >= 9.0)

        // Reset scenario
        chaosEngineService.resetScenario(ChaosScenarioType.HEAT_SPIKE)
        advanceUntilIdle()

        val resetEvent = simulator.tickOnce()
        assertNotNull(resetEvent)
    }
}
