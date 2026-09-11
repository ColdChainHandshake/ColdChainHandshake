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
        // Enforce append-only insertion preserving incoming ID, timestamp, and hash-chain attributes.
        temperatureEventDao.insertEvent(event.toEntity())
    }

    override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
        return temperatureEventDao.getTemperatures(shipmentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }
}
