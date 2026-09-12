package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.AlertDao
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Concrete implementation of AlertRepository backed by Room persistence.
 * Persists operational and breach alerts locally first with PENDING sync status.
 */
class AlertRepositoryImpl(
    private val alertDao: AlertDao
) : AlertRepository {

    override suspend fun saveAlert(alert: Alert): Result<Unit> = runCatching {
        alertDao.upsertAlert(alert.toEntity(SyncStatus.PENDING))
    }

    override fun getActiveAlerts(shipmentId: String): Flow<List<Alert>> {
        return alertDao.getActiveAlerts(shipmentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getAllAlerts(shipmentId: String): Flow<List<Alert>> {
        return alertDao.getAllAlerts(shipmentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }
}
