package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.coldchain.handshake.data.local.entities.AlertEntity
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    /**
     * Observe active (unacknowledged) alerts for a shipment.
     */
    @Query("SELECT * FROM alerts WHERE shipmentId = :shipmentId AND acknowledged = 0 ORDER BY timestamp DESC")
    fun getActiveAlerts(shipmentId: String): Flow<List<AlertEntity>>

    /**
     * Observe all alerts (both active and acknowledged) for a shipment.
     */
    @Query("SELECT * FROM alerts WHERE shipmentId = :shipmentId ORDER BY timestamp DESC")
    fun getAllAlerts(shipmentId: String): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE id = :id LIMIT 1")
    suspend fun getAlertDirect(id: String): AlertEntity?

    @Upsert
    suspend fun upsertAlert(alert: AlertEntity)

    @Query("SELECT * FROM alerts WHERE syncStatus != 'SYNCED' ORDER BY timestamp ASC")
    suspend fun getPendingAlerts(): List<AlertEntity>

    @Query("UPDATE alerts SET syncStatus = :syncStatus, lastModifiedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateSyncStatus(
        id: String,
        syncStatus: SyncStatus,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE alerts SET acknowledged = 1, syncStatus = :pendingStatus, lastModifiedTimestamp = :timestamp WHERE id = :id")
    suspend fun acknowledgeAlert(
        id: String,
        pendingStatus: SyncStatus = SyncStatus.PENDING,
        timestamp: Long = System.currentTimeMillis()
    )
}
