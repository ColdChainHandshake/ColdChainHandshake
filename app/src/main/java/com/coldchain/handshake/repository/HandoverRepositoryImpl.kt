package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.HandoverDao
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Concrete implementation of HandoverRepository backed by Room persistence.
 * Persists custody handover verification records locally first with PENDING sync status.
 */
class HandoverRepositoryImpl(
    private val handoverDao: HandoverDao
) : HandoverRepository {

    override suspend fun saveHandover(handover: Handover): Result<Unit> = runCatching {
        handoverDao.insertHandover(handover.toEntity(SyncStatus.PENDING))
    }

    override suspend fun saveConfirmedHandover(handover: Handover): Result<Unit> = runCatching {
        handoverDao.replaceHandover(handover.toEntity(SyncStatus.SYNCED))
    }

    override fun getHandover(shipmentId: String): Flow<Handover?> {
        return handoverDao.getHandover(shipmentId).map { it?.toDomain() }
    }
}
