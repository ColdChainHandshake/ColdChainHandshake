package com.coldchain.handshake.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent

/**
 * Room entity representing an immutable thermal logging event in the hash-chain.
 * Must remain append-only to preserve cryptographic integrity.
 */
@Entity(
    tableName = "temperature_events",
    indices = [
        Index(value = ["shipmentId"]),
        Index(value = ["timestamp"])
    ]
)
data class TemperatureEventEntity(
    @PrimaryKey
    val id: String,
    val shipmentId: String,
    val loggerId: String,
    val timestamp: Long,
    val temperature: Double,
    val previousHash: String,
    val currentHash: String,
    val syncStatus: SyncStatus
)

fun TemperatureEventEntity.toDomain(): TemperatureEvent = TemperatureEvent(
    id = id,
    shipmentId = shipmentId,
    loggerId = loggerId,
    timestamp = timestamp,
    temperature = temperature,
    previousHash = previousHash,
    currentHash = currentHash,
    syncStatus = syncStatus
)

fun TemperatureEvent.toEntity(): TemperatureEventEntity = TemperatureEventEntity(
    id = id,
    shipmentId = shipmentId,
    loggerId = loggerId,
    timestamp = timestamp,
    temperature = temperature,
    previousHash = previousHash,
    currentHash = currentHash,
    syncStatus = syncStatus
)
