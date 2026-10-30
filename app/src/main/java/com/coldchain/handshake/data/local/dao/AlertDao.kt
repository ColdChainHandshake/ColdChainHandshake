package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.coldchain.handshake.data.local.entities.AlertEntity
import com.coldchain.handshake.models.EscalationLevel
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

    /**
     * @return the new row ID, or -1 if an alert with this ID already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlertIfAbsent(alert: AlertEntity): Long

    @Query("SELECT * FROM alerts WHERE acknowledged = 0 ORDER BY timestamp ASC")
    suspend fun getOpenAlerts(): List<AlertEntity>

    /**
     * @return the number of alerts changed.
     */
    @Query(
        """
        UPDATE alerts SET escalationLevel = :level, syncStatus = :pendingStatus, lastModifiedTimestamp = :timestamp
        WHERE id = :id AND acknowledged = 0
        """
    )
    suspend fun escalateIfUnacknowledged(
        id: String,
        level: EscalationLevel,
        pendingStatus: SyncStatus = SyncStatus.PENDING,
        timestamp: Long = System.currentTimeMillis()
    ): Int

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
