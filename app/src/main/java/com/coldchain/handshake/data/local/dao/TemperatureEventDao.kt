package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coldchain.handshake.data.local.entities.TemperatureEventEntity
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TemperatureEventDao {

    /**
     * Observable stream of all temperature events for a shipment, sorted chronologically.
     */
    @Query("SELECT * FROM temperature_events WHERE shipmentId = :shipmentId ORDER BY timestamp ASC, id ASC")
    fun getTemperatures(shipmentId: String): Flow<List<TemperatureEventEntity>>

    /**
     * Direct snapshot read for verification logic.
     */
    @Query("SELECT * FROM temperature_events WHERE shipmentId = :shipmentId ORDER BY timestamp ASC, id ASC")
    suspend fun getTemperaturesDirect(shipmentId: String): List<TemperatureEventEntity>

    /**
     * Append-only insert. Ignores duplicates if the ID already exists,
     * protecting hash-chain and telemetry history from accidental overwrites.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: TemperatureEventEntity): Long

    /**
     * Atomic batch append-only insert within a single database transaction.
     * Prevents UI invalidation storms and ensures imported histories are committed together.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<TemperatureEventEntity>): List<Long>

    /**
     * Retrieve all pending or failed events that need cloud synchronization.
     */
    @Query("SELECT * FROM temperature_events WHERE syncStatus != 'SYNCED' ORDER BY timestamp ASC")
    suspend fun getPendingEvents(): List<TemperatureEventEntity>

    /**
     * Isolated sync status update.
     */
    @Query("UPDATE temperature_events SET syncStatus = :syncStatus WHERE id = :id")
    suspend fun updateSyncStatus(id: String, syncStatus: SyncStatus)

    /**
     * Batch isolated sync status update.
     */
    @Query("UPDATE temperature_events SET syncStatus = :syncStatus WHERE id IN (:ids)")
    suspend fun updateSyncStatuses(ids: List<String>, syncStatus: SyncStatus)

    /**
     * DEMO/CHAOS ONLY: Mutates a protected telemetry field without recomputing hashes
     * to simulate malicious in-transit tampering or data corruption for integrity verification testing.
     */
    @Query("UPDATE temperature_events SET temperature = :corruptedTemperature WHERE id = :id")
    suspend fun corruptEventTemperatureForDemo(id: String, corruptedTemperature: Double)
}
