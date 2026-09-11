package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.repository.AlertRepository

/**
 * Compatible boundary for handling explicit logger disconnection events.
 */
object LoggerDisconnectMonitor {

    /**
     * Directly generates a [AlertType.LOGGER_DISCONNECT] alert in response to an explicit disconnect event
     * (e.g. from the chaos simulator or sensor disconnect callback).
     *
     * Starts deterministically at [EscalationLevel.WORKER] tier and unacknowledged.
     */
    fun onLoggerDisconnected(
        shipmentId: String,
        loggerId: String,
        timestamp: Long = System.currentTimeMillis()
    ): Alert {
        return AlertEscalationEngine.createDisconnectAlert(
            shipmentId = shipmentId,
            loggerId = loggerId,
            reason = "Sensor connection dropped",
            timestamp = timestamp
        )
    }

    /**
     * Directly generates a [AlertType.LOGGER_DISCONNECT] alert with a specific disconnect reason.
     */
    fun onLoggerDisconnected(
        shipmentId: String,
        loggerId: String,
        reason: String,
        timestamp: Long = System.currentTimeMillis()
    ): Alert {
        return AlertEscalationEngine.createDisconnectAlert(
            shipmentId = shipmentId,
            loggerId = loggerId,
            reason = reason,
            timestamp = timestamp
        )
    }

    /**
     * Generates a [AlertType.LOGGER_DISCONNECT] alert and persists it via [AlertRepository].
     */
    suspend fun onLoggerDisconnectedAndPersist(
        shipmentId: String,
        loggerId: String,
        repository: AlertRepository,
        reason: String = "Sensor connection dropped",
        timestamp: Long = System.currentTimeMillis()
    ): Result<Alert> {
        val alert = onLoggerDisconnected(shipmentId, loggerId, reason, timestamp)
        return repository.saveAlert(alert).map { alert }
    }
}
