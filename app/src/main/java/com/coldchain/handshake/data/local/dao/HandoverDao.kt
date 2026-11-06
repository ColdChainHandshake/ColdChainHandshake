package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coldchain.handshake.data.local.entities.HandoverEntity
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface HandoverDao {

    @Query("SELECT * FROM handovers WHERE shipmentId = :shipmentId LIMIT 1")
    fun getHandover(shipmentId: String): Flow<HandoverEntity?>

    @Query("SELECT * FROM handovers WHERE shipmentId = :shipmentId LIMIT 1")
    suspend fun getHandoverDirect(shipmentId: String): HandoverEntity?

    /**
     * Fails rather than replacing, because a handover is a record of what someone said.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHandover(handover: HandoverEntity)

    /**
     * The server keeps every confirmation, so this phone's copy follows the latest one for the shipment.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceHandover(handover: HandoverEntity)

    @Query("SELECT * FROM handovers WHERE syncStatus IN ('PENDING', 'FAILED') ORDER BY timestamp ASC")
    suspend fun getPendingHandovers(): List<HandoverEntity>

    @Query("UPDATE handovers SET syncStatus = :syncStatus, lastModifiedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateSyncStatus(
        id: String,
        syncStatus: SyncStatus,
        timestamp: Long = System.currentTimeMillis()
    )
}
