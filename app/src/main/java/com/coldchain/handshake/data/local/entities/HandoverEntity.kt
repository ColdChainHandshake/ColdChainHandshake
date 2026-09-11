package com.coldchain.handshake.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.SyncStatus

/**
 * Room entity representing custody handover verification.
 */
@Entity(
    tableName = "handovers",
    indices = [
        Index(value = ["shipmentId"], unique = true)
    ]
)
data class HandoverEntity(
    @PrimaryKey
    val id: String,
    val shipmentId: String,
    val workerSigned: Boolean,
    val pharmacistSigned: Boolean,
    val integrityVerified: Boolean,
    val temperaturePassed: Boolean,
    val verdict: HandoverVerdict,
    val timestamp: Long,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

fun HandoverEntity.toDomain(): Handover = Handover(
    id = id,
    shipmentId = shipmentId,
    workerSigned = workerSigned,
    pharmacistSigned = pharmacistSigned,
    integrityVerified = integrityVerified,
    temperaturePassed = temperaturePassed,
    verdict = verdict,
    timestamp = timestamp
)

fun Handover.toEntity(syncStatus: SyncStatus = SyncStatus.PENDING): HandoverEntity = HandoverEntity(
    id = id,
    shipmentId = shipmentId,
    workerSigned = workerSigned,
    pharmacistSigned = pharmacistSigned,
    integrityVerified = integrityVerified,
    temperaturePassed = temperaturePassed,
    verdict = verdict,
    timestamp = timestamp,
    syncStatus = syncStatus,
    lastModifiedTimestamp = System.currentTimeMillis()
)
