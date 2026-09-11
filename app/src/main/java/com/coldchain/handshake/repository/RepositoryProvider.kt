package com.coldchain.handshake.repository

import com.coldchain.handshake.repository.impl.InMemoryChaosEngineService
import com.coldchain.handshake.repository.impl.InMemoryShipmentRepository
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator

/**
 * Provides access to shared repository instances across the application.
 * Allows Person 1's Room/Supabase implementations or test doubles to be injected if needed.
 */
object RepositoryProvider {

    var shipmentRepository: ShipmentRepository = InMemoryShipmentRepository()
    var telemetryRepository: TelemetryRepository = InMemoryTelemetryRepository()
    var chaosEngineService: ChaosEngineService = InMemoryChaosEngineService()

    var temperatureSimulator: TemperatureSimulator = TemperatureSimulator(telemetryRepository, chaosEngineService)

    /**
     * Resets repositories and simulator to fresh in-memory instances (useful for testing).
     */
    fun resetForTesting() {
        shipmentRepository = InMemoryShipmentRepository()
        telemetryRepository = InMemoryTelemetryRepository()
        chaosEngineService = InMemoryChaosEngineService()
        temperatureSimulator = TemperatureSimulator(telemetryRepository, chaosEngineService)
    }
}
