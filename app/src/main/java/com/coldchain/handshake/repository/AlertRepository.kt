package com.coldchain.handshake.repository

import com.coldchain.handshake.models.Alert
import kotlinx.coroutines.flow.Flow

/**
 * Shared repository contract for alerts.
 */
interface AlertRepository {
    suspend fun saveAlert(alert: Alert): Result<Unit>
    fun getActiveAlerts(shipmentId: String): Flow<List<Alert>>
    fun getAllAlerts(shipmentId: String): Flow<List<Alert>> = getActiveAlerts(shipmentId)
}
