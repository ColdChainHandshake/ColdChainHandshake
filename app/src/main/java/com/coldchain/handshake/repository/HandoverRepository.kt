package com.coldchain.handshake.repository

import com.coldchain.handshake.models.Handover
import kotlinx.coroutines.flow.Flow

/**
 * Shared repository contract for custody handover.
 */
interface HandoverRepository {
    suspend fun saveHandover(handover: Handover): Result<Unit>
    fun getHandover(shipmentId: String): Flow<Handover?>
}
