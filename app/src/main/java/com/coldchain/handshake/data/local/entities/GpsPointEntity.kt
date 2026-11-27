package com.coldchain.handshake.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.recording.RecordedLocation
import com.coldchain.handshake.repository.GpsPoint

/**
 * Where the shipment was, recorded by the phone that records it and kept here until the server has it. The ID is
 * made on the phone, so a point sent twice is stored once on the server.
 */
@Entity(
    tableName = "gps_points",
    indices = [Index(value = ["shipmentId"]), Index(value = ["syncStatus"])]
)
data class GpsPointEntity(
    @PrimaryKey
    val id: String,
    val shipmentId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val deviceTimestamp: Long,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)

fun GpsPointEntity.toDomain(): GpsPoint = GpsPoint(id, shipmentId, latitude, longitude, accuracyMeters, deviceTimestamp)

fun GpsPointEntity.toRecordedLocation(): RecordedLocation =
    RecordedLocation(latitude, longitude, accuracyMeters, deviceTimestamp, sent = syncStatus == SyncStatus.SYNCED)

fun GpsPoint.toEntity(syncStatus: SyncStatus = SyncStatus.PENDING): GpsPointEntity =
    GpsPointEntity(id, shipmentId, latitude, longitude, accuracyMeters, deviceTimestamp, syncStatus)
