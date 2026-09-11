package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.repository.AlertRepository
import java.util.UUID

/**
 * Engine for creating alerts and deterministically escalating them across responder tiers.
 *
 * Escalation flow:
 * WORKER -> SUPERVISOR -> PHARMACIST
 */
object AlertEscalationEngine {

    /**
     * Creates a new [Alert] of type [AlertType.TEMPERATURE_BREACH].
     * Starts at the initial [EscalationLevel.WORKER] tier and unacknowledged.
     */
    fun createBreachAlert(
        shipmentId: String,
        breachEvaluation: BreachEvaluation,
        timestamp: Long = System.currentTimeMillis(),
        id: String = "alert-breach-${UUID.randomUUID().toString().take(8)}"
    ): Alert {
        val durationFormatted = BreachDetector.formatDuration(breachEvaluation.cumulativeBreachDurationMs)
        val peakInfo = breachEvaluation.maxExcursionTemperature?.let { " (Peak: %.1f°C)".format(it) } ?: ""
        val message = "Thermal breach detected: cumulative excursion > 5 mins (Elapsed: $durationFormatted$peakInfo)."

        return Alert(
            id = id,
            shipmentId = shipmentId,
            type = AlertType.TEMPERATURE_BREACH,
            message = message,
            timestamp = timestamp,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false
        )
    }

    /**
     * Creates an operational warning alert for logger disconnections.
     */
    fun createDisconnectAlert(
        shipmentId: String,
        loggerId: String,
        reason: String = "Sensor connection dropped",
        timestamp: Long = System.currentTimeMillis(),
        id: String = "alert-disconnect-${UUID.randomUUID().toString().take(8)}"
    ): Alert {
        val message = "Logger disconnect warning: $reason for logger $loggerId."
        return Alert(
            id = id,
            shipmentId = shipmentId,
            type = AlertType.LOGGER_DISCONNECT,
            message = message,
            timestamp = timestamp,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false
        )
    }

    /**
     * Creates a general operational warning alert.
     */
    fun createOperationalWarning(
        shipmentId: String,
        message: String,
        timestamp: Long = System.currentTimeMillis(),
        id: String = "alert-op-${UUID.randomUUID().toString().take(8)}"
    ): Alert {
        return Alert(
            id = id,
            shipmentId = shipmentId,
            type = AlertType.OPERATIONAL_WARNING,
            message = message,
            timestamp = timestamp,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false
        )
    }

    /**
     * Returns the next escalation level in the deterministic hierarchy:
     * WORKER -> SUPERVISOR -> PHARMACIST (terminal).
     */
    fun getNextEscalationLevel(current: EscalationLevel): EscalationLevel {
        return when (current) {
            EscalationLevel.WORKER -> EscalationLevel.SUPERVISOR
            EscalationLevel.SUPERVISOR -> EscalationLevel.PHARMACIST
            EscalationLevel.PHARMACIST -> EscalationLevel.PHARMACIST
        }
    }

    /**
     * Escalates an alert to the next tier if not already at the terminal tier.
     */
    fun escalateAlert(alert: Alert): Alert {
        val nextLevel = getNextEscalationLevel(alert.escalationLevel)
        return alert.copy(escalationLevel = nextLevel)
    }

    /**
     * Escalates an alert and persists the updated alert via [AlertRepository].
     */
    suspend fun escalateAndPersist(alert: Alert, repository: AlertRepository): Result<Alert> {
        val escalated = escalateAlert(alert)
        return repository.saveAlert(escalated).map { escalated }
    }

    /**
     * Acknowledges an active alert.
     */
    fun acknowledgeAlert(alert: Alert): Alert {
        return alert.copy(acknowledged = true)
    }

    /**
     * Acknowledges an alert and persists the updated alert via [AlertRepository].
     */
    suspend fun acknowledgeAndPersist(alert: Alert, repository: AlertRepository): Result<Alert> {
        val acknowledged = acknowledgeAlert(alert)
        return repository.saveAlert(acknowledged).map { acknowledged }
    }
}
