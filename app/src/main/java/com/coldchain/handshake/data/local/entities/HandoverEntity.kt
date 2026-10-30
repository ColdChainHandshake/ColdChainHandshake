package com.coldchain.handshake.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.VvmStage

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
    val lastModifiedTimestamp: Long = System.currentTimeMillis(),
    val reason: String? = null,
    @ColumnInfo(defaultValue = "NOT_RECORDED")
    val vvmStage: VvmStage = VvmStage.NOT_RECORDED,
    @ColumnInfo(defaultValue = "NOT_DONE")
    val shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE
)

fun HandoverEntity.toDomain(): Handover = Handover(
    id = id,
    shipmentId = shipmentId,
    workerSigned = workerSigned,
    pharmacistSigned = pharmacistSigned,
    integrityVerified = integrityVerified,
    temperaturePassed = temperaturePassed,
    verdict = verdict,
    timestamp = timestamp,
    reason = reason,
    vvmStage = vvmStage,
    shakeTest = shakeTest
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
    lastModifiedTimestamp = System.currentTimeMillis(),
    reason = reason,
    vvmStage = vvmStage,
    shakeTest = shakeTest
)
