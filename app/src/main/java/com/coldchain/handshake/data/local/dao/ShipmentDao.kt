package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.coldchain.handshake.data.local.entities.ShipmentEntity
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ShipmentDao {

    @Query("SELECT * FROM shipments WHERE id = :id LIMIT 1")
    fun getShipment(id: String): Flow<ShipmentEntity?>

    @Query("SELECT * FROM shipments WHERE id = :id LIMIT 1")
    suspend fun getShipmentDirect(id: String): ShipmentEntity?

    @Query("SELECT * FROM shipments ORDER BY lastModifiedTimestamp DESC")
    fun getAllShipments(): Flow<List<ShipmentEntity>>

    @Upsert
    suspend fun upsertShipment(shipment: ShipmentEntity)

    @Query("SELECT * FROM shipments WHERE syncStatus != 'SYNCED'")
    suspend fun getPendingShipments(): List<ShipmentEntity>

    @Query("UPDATE shipments SET syncStatus = :syncStatus, lastModifiedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateSyncStatus(
        id: String,
        syncStatus: SyncStatus,
        timestamp: Long = System.currentTimeMillis()
    )
}
