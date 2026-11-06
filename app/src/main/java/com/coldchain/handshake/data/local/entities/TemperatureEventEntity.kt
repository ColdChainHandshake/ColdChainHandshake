package com.coldchain.handshake.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent

/**
 * Room entity representing one reading in a shipment's chain. Readings are only ever added.
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
    val syncStatus: SyncStatus,
    @ColumnInfo(defaultValue = "0")
    val seq: Long = 0L,
    val receivedAt: Long? = null
)

fun TemperatureEventEntity.toDomain(): TemperatureEvent = TemperatureEvent(
    id = id,
    shipmentId = shipmentId,
    loggerId = loggerId,
    timestamp = timestamp,
    temperature = temperature,
    previousHash = previousHash,
    currentHash = currentHash,
    syncStatus = syncStatus,
    seq = seq,
    receivedAt = receivedAt
)

fun TemperatureEvent.toEntity(): TemperatureEventEntity = TemperatureEventEntity(
    id = id,
    shipmentId = shipmentId,
    loggerId = loggerId,
    timestamp = timestamp,
    temperature = temperature,
    previousHash = previousHash,
    currentHash = currentHash,
    syncStatus = syncStatus,
    seq = seq,
    receivedAt = receivedAt
)
