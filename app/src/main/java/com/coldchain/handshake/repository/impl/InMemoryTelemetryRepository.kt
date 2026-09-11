package com.coldchain.handshake.repository.impl

import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Thread-safe in-memory implementation of [TelemetryRepository].
 * Serves as the baseline shared repository for temperature telemetry events.
 */
class InMemoryTelemetryRepository : TelemetryRepository {

    private val telemetryFlow = MutableStateFlow<Map<String, List<TemperatureEvent>>>(emptyMap())

    override suspend fun saveTemperature(event: TemperatureEvent): Result<Unit> {
        return try {
            val currentMap = telemetryFlow.value
            val existingList = currentMap[event.shipmentId] ?: emptyList()
            val updatedList = existingList + event
            telemetryFlow.value = currentMap + (event.shipmentId to updatedList)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
        return telemetryFlow.map { map -> map[shipmentId] ?: emptyList() }
    }
}
