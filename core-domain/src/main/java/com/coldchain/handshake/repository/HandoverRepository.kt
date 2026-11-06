package com.coldchain.handshake.repository

import com.coldchain.handshake.models.Handover
import kotlinx.coroutines.flow.Flow

/**
 * Shared repository contract for custody handover.
 */
interface HandoverRepository {
    suspend fun saveHandover(handover: Handover): Result<Unit>

    /**
     * Keeps this device's copy of a confirmation the server already holds, which can be repeated on
     * newer history, so nothing is left to send.
     */
    suspend fun saveConfirmedHandover(handover: Handover): Result<Unit> = saveHandover(handover)
    fun getHandover(shipmentId: String): Flow<Handover?>
}
