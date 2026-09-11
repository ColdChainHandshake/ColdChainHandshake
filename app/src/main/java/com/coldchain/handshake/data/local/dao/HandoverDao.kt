package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.coldchain.handshake.data.local.entities.HandoverEntity
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface HandoverDao {

    @Query("SELECT * FROM handovers WHERE shipmentId = :shipmentId LIMIT 1")
    fun getHandover(shipmentId: String): Flow<HandoverEntity?>

    @Query("SELECT * FROM handovers WHERE shipmentId = :shipmentId LIMIT 1")
    suspend fun getHandoverDirect(shipmentId: String): HandoverEntity?

    @Upsert
    suspend fun upsertHandover(handover: HandoverEntity)

    @Query("SELECT * FROM handovers WHERE syncStatus != 'SYNCED' ORDER BY timestamp ASC")
    suspend fun getPendingHandovers(): List<HandoverEntity>

    @Query("UPDATE handovers SET syncStatus = :syncStatus, lastModifiedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateSyncStatus(
        id: String,
        syncStatus: SyncStatus,
        timestamp: Long = System.currentTimeMillis()
    )
}
