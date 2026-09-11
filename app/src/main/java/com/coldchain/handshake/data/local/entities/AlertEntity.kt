package com.coldchain.handshake.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.SyncStatus

/**
 * Room entity representing an operational or breach alert.
 */
@Entity(
    tableName = "alerts",
    indices = [
        Index(value = ["shipmentId"]),
        Index(value = ["acknowledged"])
    ]
)
data class AlertEntity(
    @PrimaryKey
    val id: String,
    val shipmentId: String,
    val type: AlertType,
    val message: String,
    val timestamp: Long,
    val escalationLevel: EscalationLevel,
    val acknowledged: Boolean,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
)

fun AlertEntity.toDomain(): Alert = Alert(
    id = id,
    shipmentId = shipmentId,
    type = type,
    message = message,
    timestamp = timestamp,
    escalationLevel = escalationLevel,
    acknowledged = acknowledged
)

fun Alert.toEntity(syncStatus: SyncStatus = SyncStatus.PENDING): AlertEntity = AlertEntity(
    id = id,
    shipmentId = shipmentId,
    type = type,
    message = message,
    timestamp = timestamp,
    escalationLevel = escalationLevel,
    acknowledged = acknowledged,
    syncStatus = syncStatus,
    lastModifiedTimestamp = System.currentTimeMillis()
)
