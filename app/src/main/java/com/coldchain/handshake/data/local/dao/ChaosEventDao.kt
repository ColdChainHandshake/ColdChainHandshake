package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coldchain.handshake.data.local.entities.ChaosEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChaosEventDao {

    @Query("SELECT * FROM chaos_events WHERE shipmentId = :shipmentId ORDER BY timestamp DESC")
    fun getChaosEvents(shipmentId: String): Flow<List<ChaosEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChaosEvent(event: ChaosEventEntity)

    @Query("SELECT * FROM chaos_events ORDER BY timestamp DESC")
    fun getAllChaosEvents(): Flow<List<ChaosEventEntity>>
}
