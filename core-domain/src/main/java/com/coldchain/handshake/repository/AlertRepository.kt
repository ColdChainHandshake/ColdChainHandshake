package com.coldchain.handshake.repository

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.EscalationLevel
import kotlinx.coroutines.flow.Flow

/**
 * Shared repository contract for alerts.
 */
interface AlertRepository {
    suspend fun saveAlert(alert: Alert): Result<Unit>

    /**
     * Stores a new alert and leaves an existing one with the same ID untouched, so a repeat
     * evaluation can't undo an acknowledgement or an escalation.
     *
     * @return true if the alert was new.
     */
    suspend fun saveAlertIfAbsent(alert: Alert): Result<Boolean>

    /**
     * Raises the level of an alert that is still unacknowledged.
     *
     * @return true if the alert changed.
     */
    suspend fun escalateIfUnacknowledged(alertId: String, level: EscalationLevel): Result<Boolean>

    suspend fun getOpenAlerts(): List<Alert>
    fun getActiveAlerts(shipmentId: String): Flow<List<Alert>>
    fun getAllAlerts(shipmentId: String): Flow<List<Alert>> = getActiveAlerts(shipmentId)
}
