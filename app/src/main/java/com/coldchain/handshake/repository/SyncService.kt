package com.coldchain.handshake.repository

import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * Shared service contract for offline synchronization.
 */
interface SyncService {
    suspend fun syncPendingData(): Result<Unit>
    fun isOnline(): Boolean
    fun getSyncStatus(): Flow<SyncStatus>
}
