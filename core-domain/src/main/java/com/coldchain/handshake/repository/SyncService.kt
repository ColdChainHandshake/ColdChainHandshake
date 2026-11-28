package com.coldchain.handshake.repository

import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared service contract for offline synchronization.
 */
interface SyncService {
    suspend fun syncPendingData(): Result<Unit>
    fun isOnline(): Boolean
    fun getSyncStatus(): Flow<SyncStatus>

    /** Sends one shipment's readings now, waiting for any sync already running. */
    suspend fun uploadReadings(shipmentId: String): Result<Unit> = syncPendingData()

    /** Why the server last refused something, which retrying can't fix. */
    fun getLastRejection(): Flow<String?> = flowOf(null)

    /**
     * Deletes from this phone the location points of [shipmentId] that the server has, or refused for good, once the
     * trip is over here (Gate 5 review S8): a courier's route stays on their phone no longer than it has to.
     */
    suspend fun forgetSentLocation(shipmentId: String) {}
}
