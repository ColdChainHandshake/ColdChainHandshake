package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.TemperatureEventDao
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Concrete implementation of TelemetryRepository backed by Room persistence.
 * Enforces append-only storage and exact preservation of timestamps and cryptographic hashes.
 */
class TelemetryRepositoryImpl(
    private val temperatureEventDao: TemperatureEventDao
) : TelemetryRepository {

    override suspend fun saveTemperature(event: TemperatureEvent): Result<Unit> = runCatching {
        requireReadable(event)
        // Enforce append-only insertion preserving incoming ID, timestamp, and hash-chain attributes.
        temperatureEventDao.insertEvent(event.toEntity())
    }

    override suspend fun saveTemperatures(events: List<TemperatureEvent>): Result<Unit> = runCatching {
        if (events.isEmpty()) return@runCatching
        events.forEach(::requireReadable)
        temperatureEventDao.insertEvents(events.map { it.toEntity() })
        Unit
    }

    /**
     * Room stores NaN as NULL, so the insert would be skipped while the save still reported success.
     */
    private fun requireReadable(event: TemperatureEvent) {
        require(event.temperature.isFinite()) { "Reading ${event.id} has no usable temperature." }
    }

    override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
        return temperatureEventDao.getTemperatures(shipmentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }
}
