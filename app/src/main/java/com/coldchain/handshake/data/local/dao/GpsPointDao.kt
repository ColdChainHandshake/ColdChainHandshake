package com.coldchain.handshake.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coldchain.handshake.data.local.entities.GpsPointEntity
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow

/** The queue of locations recorded on this phone, oldest first, until the server has them. */
@Dao
interface GpsPointDao {

    /** A point already queued is kept as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: GpsPointEntity): Long

    /** Points still to send: never tried, or tried and failed for want of a connection. */
    @Query("SELECT * FROM gps_points WHERE syncStatus IN ('PENDING', 'FAILED') ORDER BY deviceTimestamp ASC")
    suspend fun getPending(): List<GpsPointEntity>

    @Query("UPDATE gps_points SET syncStatus = :status WHERE id IN (:ids)")
    suspend fun updateSyncStatuses(ids: List<String>, status: SyncStatus)

    /** Deletes [shipmentId]'s points that are settled: sent, or refused for good. Those still to send stay. */
    @Query("DELETE FROM gps_points WHERE shipmentId = :shipmentId AND syncStatus IN ('SYNCED', 'REJECTED')")
    suspend fun deleteSettled(shipmentId: String): Int

    /** The newest point this phone recorded for [shipmentId], sent or not. */
    @Query("SELECT * FROM gps_points WHERE shipmentId = :shipmentId ORDER BY deviceTimestamp DESC LIMIT 1")
    fun latest(shipmentId: String): Flow<GpsPointEntity?>
}
