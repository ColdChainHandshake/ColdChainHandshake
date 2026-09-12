package com.coldchain.handshake.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coldchain.handshake.models.SyncStatus

/**
 * Room entity representing an operational chaos injection event.
 * Backs the historical chaos log scoped to individual shipments.
 */
@Entity(
    tableName = "chaos_events",
    indices = [
        Index(value = ["shipmentId"]),
        Index(value = ["timestamp"])
    ]
)
data class ChaosEventEntity(
    @PrimaryKey
    val id: String,
    val shipmentId: String,
    val scenarioType: String,
    val timestamp: Long,
    val message: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED
)
