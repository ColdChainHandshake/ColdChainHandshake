package com.coldchain.handshake.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.ProductProfiles
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
    val lastModifiedTimestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "vaccine-2-8-freeze-sensitive")
    val productProfileId: String = ProductProfiles.DEFAULT.id,
    val journeyStartedAt: Long? = null,
    val genesisHash: String? = null
)

fun ShipmentEntity.toDomain(): Shipment = Shipment(
    id = id,
    qrCode = qrCode,
    loggerId = loggerId,
    origin = origin,
    destination = destination,
    workerId = workerId,
    status = status,
    productProfileId = productProfileId,
    journeyStartedAt = journeyStartedAt,
    genesisHash = genesisHash
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
    lastModifiedTimestamp = System.currentTimeMillis(),
    productProfileId = productProfileId,
    journeyStartedAt = journeyStartedAt,
    genesisHash = genesisHash
)
