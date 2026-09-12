package com.coldchain.handshake.repository

import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.flow.Flow

/**
 * Shared repository contract for temperature telemetry.
 */
interface TelemetryRepository {
    suspend fun saveTemperature(event: TemperatureEvent): Result<Unit>
    suspend fun saveTemperatures(events: List<TemperatureEvent>): Result<Unit>
    fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>>
}
