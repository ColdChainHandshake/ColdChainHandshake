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
            if (existingList.any { it.id == event.id }) {
                return Result.success(Unit)
            }
            val updatedList = (existingList + event).sortedWith(compareBy({ it.timestamp }, { it.id }))
            telemetryFlow.value = currentMap + (event.shipmentId to updatedList)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveTemperatures(events: List<TemperatureEvent>): Result<Unit> {
        return try {
            if (events.isEmpty()) return Result.success(Unit)
            val currentMap = telemetryFlow.value.toMutableMap()
            val byShipment = events.groupBy { it.shipmentId }
            for ((shipmentId, newEvents) in byShipment) {
                val existing = currentMap[shipmentId] ?: emptyList()
                val existingIds = existing.map { it.id }.toSet()
                val deduplicatedNew = newEvents.filter { it.id !in existingIds }
                val merged = (existing + deduplicatedNew).sortedWith(compareBy({ it.timestamp }, { it.id }))
                currentMap[shipmentId] = merged
            }
            telemetryFlow.value = currentMap
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
        return telemetryFlow.map { map -> map[shipmentId] ?: emptyList() }
    }
}
