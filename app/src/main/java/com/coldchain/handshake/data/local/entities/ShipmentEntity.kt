package com.coldchain.handshake.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus

/**
 * Room entity representing a cold-chain shipment.
 * Stores core domain fields plus local synchronization metadata.
 */
@Entity(tableName = "shipments")
data class ShipmentEntity(
    @PrimaryKey
    val id: String,
    val qrCode: String,
    val loggerId: String,
    val origin: String,
    val destination: String,
    val workerId: String,
    val status: ShipmentStatus,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

fun ShipmentEntity.toDomain(): Shipment = Shipment(
    id = id,
    qrCode = qrCode,
    loggerId = loggerId,
    origin = origin,
    destination = destination,
    workerId = workerId,
    status = status
)

fun Shipment.toEntity(syncStatus: SyncStatus = SyncStatus.PENDING): ShipmentEntity = ShipmentEntity(
    id = id,
    qrCode = qrCode,
    loggerId = loggerId,
    origin = origin,
    destination = destination,
    workerId = workerId,
    status = status,
    syncStatus = syncStatus,
    lastModifiedTimestamp = System.currentTimeMillis()
)
