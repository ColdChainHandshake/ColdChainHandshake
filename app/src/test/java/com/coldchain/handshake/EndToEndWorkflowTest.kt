package com.coldchain.handshake

import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.repository.impl.InMemoryChaosEngineService
import com.coldchain.handshake.repository.impl.InMemoryShipmentRepository
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndWorkflowTest {

    private lateinit var shipmentRepository: InMemoryShipmentRepository
    private lateinit var telemetryRepository: InMemoryTelemetryRepository
    private lateinit var chaosEngineService: InMemoryChaosEngineService
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var simulator: TemperatureSimulator

    @Before
    fun setUp() {
        shipmentRepository = InMemoryShipmentRepository()
        telemetryRepository = InMemoryTelemetryRepository()
        chaosEngineService = InMemoryChaosEngineService()
        simulator = TemperatureSimulator(
            telemetryRepository = telemetryRepository,
            chaosEngineService = chaosEngineService,
            dispatcher = testDispatcher
        )
    }

    /**
     * Complete End-to-End Validation:
     * 1. Create shipment
     * 2. QR scan & authoritative association
     * 3. Pair logger
     * 4. Dispatch
     * 5. Start Journey (IN_TRANSIT)
     * 6. Normal telemetry generation (4°C–6°C)
     * 7. Heat spike excursion (9°C–11°C)
     * 8. Verify all telemetry stored in TelemetryRepository
     * 9. Verify persistence failure handling
     * 10. Verify reset clears HEAT_SPIKE and prevents immediate reactivation
     */
    @Test
    fun testCompletePerson2EndToEndWorkflow() = runTest(testDispatcher) {
        val shipmentId = "SHIP-E2E-100"
        val origin = "Central Cold Distribution Hub"
        val destination = "Metropolitan Oncology Pharmacy"
        val workerId = "COURIER-77"
        val pairedLoggerId = "LOG-E2E-888"

        // Step 1: Create Shipment (initial CREATED state)
        val initialShipment = Shipment(
            id = shipmentId,
            qrCode = "", // initially unassociated
            loggerId = "",
            origin = origin,
            destination = destination,
            workerId = workerId,
            status = ShipmentStatus.CREATED
        )
        shipmentRepository.saveShipment(initialShipment)

        var loaded = shipmentRepository.getShipment(shipmentId).first()
        assertNotNull(loaded)
        assertEquals(ShipmentStatus.CREATED, loaded?.status)

        // Step 2: QR Scanning — scan barcode/QR and associate directly with Shipment.qrCode
        val scannedQrPayload = "PKG-VACCINE-BATCH-9942-COLDCHAIN"
        val qrAssociatedShipment = loaded!!.copy(qrCode = scannedQrPayload)
        shipmentRepository.saveShipment(qrAssociatedShipment)

        loaded = shipmentRepository.getShipment(shipmentId).first()
        assertEquals(scannedQrPayload, loaded?.qrCode)

        // Also verify lookup by QR code from repository
        val allShipments = shipmentRepository.getAllShipments().first()
        val foundByQr = allShipments.firstOrNull { it.qrCode == scannedQrPayload }
        assertNotNull("Must find shipment by authoritative qrCode", foundByQr)
        assertEquals(shipmentId, foundByQr?.id)

        // Step 3: Pair Logger — associate simulated loggerId to Shipment
        val pairedShipment = qrAssociatedShipment.copy(loggerId = pairedLoggerId)
        shipmentRepository.saveShipment(pairedShipment)

        loaded = shipmentRepository.getShipment(shipmentId).first()
        assertEquals(pairedLoggerId, loaded?.loggerId)

        // Step 4: Dispatch Shipment — seal cargo and transition to DISPATCHED
        val dispatchedShipment = pairedShipment.copy(status = ShipmentStatus.DISPATCHED)
        shipmentRepository.saveShipment(dispatchedShipment)

        loaded = shipmentRepository.getShipment(shipmentId).first()
        assertEquals(ShipmentStatus.DISPATCHED, loaded?.status)

        // Step 5: Start Journey — transition to IN_TRANSIT & attach to simulator
        val inTransitShipment = dispatchedShipment.copy(status = ShipmentStatus.IN_TRANSIT)
        shipmentRepository.saveShipment(inTransitShipment)

        loaded = shipmentRepository.getShipment(shipmentId).first()
        assertEquals(ShipmentStatus.IN_TRANSIT, loaded?.status)

        val startTime = 1700000000000L
        simulator.attachShipment(inTransitShipment, startTimestamp = startTime)
        assertEquals(inTransitShipment.id, simulator.activeShipment.value?.id)
        assertEquals(pairedLoggerId, simulator.activeShipment.value?.loggerId)

        // Step 6: Normal Telemetry Stream (4°C–6°C)
        simulator.setHeatSpikeMode(false)
        for (step in 1..5) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull("Normal event must be successfully persisted", event)
            event?.let {
                assertEquals(shipmentId, it.shipmentId)
                assertEquals(pairedLoggerId, it.loggerId)
                assertEquals(startTime + (step * 60_000L), it.timestamp)
                // Verify strictly in normal cold-chain operating band
                assertTrue("Temp ${it.temperature}°C must be >= 4.0°C", it.temperature >= 4.0)
                assertTrue("Temp ${it.temperature}°C must be <= 6.0°C", it.temperature <= 6.0)
            }
        }

        // Verify normal telemetry records in shared TelemetryRepository
        var telemetry = telemetryRepository.getTemperatures(shipmentId).first()
        assertEquals(5, telemetry.size)

        // Step 7: Heat Spike Excursion (9°C–11°C)
        // Trigger via ChaosEngineService to verify end-to-end chaos engine integration
        chaosEngineService.injectScenario(ChaosScenarioType.HEAT_SPIKE)
        advanceUntilIdle()

        assertTrue("Simulator must be in heat spike mode", simulator.isHeatSpikeMode.value)

        for (step in 6..11) {
            val event = simulator.tickOnce(simulatedStepDurationSeconds = 60L)
            assertNotNull("Heat spike event must be successfully persisted", event)
            event?.let {
                assertEquals(shipmentId, it.shipmentId)
                assertEquals(pairedLoggerId, it.loggerId)
                assertEquals(startTime + (step * 60_000L), it.timestamp)
                // Verify strictly in excursion heat spike band
                assertTrue("Temp ${it.temperature}°C must be >= 9.0°C", it.temperature >= 9.0)
                assertTrue("Temp ${it.temperature}°C must be <= 11.0°C", it.temperature <= 11.0)
                assertTrue("Temp ${it.temperature}°C must exceed safe 8°C boundary", it.temperature > 8.0)
            }
        }

        // Step 8: Verify all 11 readings (5 normal + 6 heat spike) exist in TelemetryRepository
        telemetry = telemetryRepository.getTemperatures(shipmentId).first()
        assertEquals(11, telemetry.size)
        // First 5 safe
        assertTrue(telemetry.take(5).all { it.temperature in 4.0..6.0 })
        // Next 6 excursions (> 5 minutes excursion duration!)
        assertTrue(telemetry.drop(5).all { it.temperature in 9.0..11.0 })

        // Step 9: Reset Simulator — verify HEAT_SPIKE is cleared and does not immediately reactivate
        simulator.resetSync()
        advanceUntilIdle()

        assertNull("Active shipment must be null after reset", simulator.activeShipment.value)
        assertFalse("Heat spike mode must be false after reset", simulator.isHeatSpikeMode.value)

        // Verify chaos engine active scenarios are also cleared
        val activeChaos = chaosEngineService.observeActiveScenarios().first()
        assertFalse("HEAT_SPIKE must not remain active after reset", activeChaos.any { it.scenarioType == ChaosScenarioType.HEAT_SPIKE })

        // Advance dispatcher to confirm it doesn't spontaneously reactivate
        advanceUntilIdle()
        assertFalse("Heat spike must not spontaneously reactivate", simulator.isHeatSpikeMode.value)
    }

    /**
     * Verify persistence failure handling:
     * When TelemetryRepository.saveTemperature fails, tickOnce must NOT report success.
     */
    @Test
    fun testPersistenceFailureIsNotReportedAsSuccess() = runTest(testDispatcher) {
        val failingRepo = object : TelemetryRepository {
            override suspend fun saveTemperature(event: TemperatureEvent): Result<Unit> {
                return Result.failure(IOException("Simulated local database write lock failure"))
            }

            override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
                return telemetryRepository.getTemperatures(shipmentId)
            }
        }

        val simulatorWithFailingRepo = TemperatureSimulator(
            telemetryRepository = failingRepo,
            chaosEngineService = chaosEngineService,
            dispatcher = testDispatcher
        )

        val testShipment = Shipment(
            id = "SHIP-FAIL-01",
            qrCode = "QR-FAIL",
            loggerId = "LOG-FAIL",
            origin = "Origin",
            destination = "Dest",
            workerId = "W-1",
            status = ShipmentStatus.IN_TRANSIT
        )

        simulatorWithFailingRepo.attachShipment(testShipment)
        val event = simulatorWithFailingRepo.tickOnce()

        // MUST return null, not reporting success!
        assertNull("Failed persistence must return null", event)
        assertNotNull("Persistence error must be recorded", simulatorWithFailingRepo.lastPersistenceError.value)
        assertTrue(
            "Error must mention database failure",
            simulatorWithFailingRepo.lastPersistenceError.value!!.contains("Simulated local database write lock failure")
        )
    }
}
