package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.repository.AlertRepository

/**
 * Engine for creating alerts and deterministically escalating them across responder tiers.
 *
 * Escalation flow:
 * WORKER -> SUPERVISOR -> PHARMACIST, by hand or when the timer runs out on an unacknowledged alert.
 */
object AlertEscalationEngine {

    /**
     * How long an unacknowledged alert waits at each level before moving to the next.
     */
    const val ESCALATION_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * Identifies one event, so evaluating the same history twice can never raise it twice.
     */
    fun alertId(shipmentId: String, type: AlertType, startTimestamp: Long): String =
        "$shipmentId:${type.name}:$startTimestamp"

    /**
     * Lists the alerts a shipment's evaluation calls for: one per breached excursion and one per
     * data gap. The result is the same on every evaluation, so callers save only what is new.
     */
    fun alertsFor(shipmentId: String, evaluation: BreachEvaluation, timestamp: Long): List<Alert> {
        val excursionAlerts = evaluation.excursions
            .filter { it.breached }
            .map { createExcursionAlert(shipmentId, it, timestamp) }
        val gapAlerts = evaluation.dataGaps.map { createDataGapAlert(shipmentId, it, timestamp) }
        return excursionAlerts + gapAlerts
    }

    private fun createExcursionAlert(shipmentId: String, excursion: Excursion, timestamp: Long): Alert {
        val type = when (excursion.type) {
            ExcursionType.HEAT -> AlertType.TEMPERATURE_BREACH
            ExcursionType.FREEZE -> AlertType.FREEZE_BREACH
        }
        val label = when (excursion.type) {
            ExcursionType.HEAT -> "Thermal breach detected"
            ExcursionType.FREEZE -> "Freeze breach detected"
        }
        return Alert(
            id = alertId(shipmentId, type, excursion.startTimestamp),
            shipmentId = shipmentId,
            type = type,
            message = "$label: reached %.1f°C (Elapsed: %s).".format(
                excursion.extremeTemperature,
                BreachDetector.formatDuration(excursion.durationMs)
            ),
            timestamp = timestamp,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false,
            excursionStart = excursion.startTimestamp
        )
    }

    private fun createDataGapAlert(shipmentId: String, gap: DataGap, timestamp: Long): Alert {
        val duration = BreachDetector.formatDuration(gap.endTimestamp - gap.startTimestamp)
        return Alert(
            id = alertId(shipmentId, AlertType.DATA_GAP, gap.startTimestamp),
            shipmentId = shipmentId,
            type = AlertType.DATA_GAP,
            message = "No readings for $duration. Temperature during this time is unknown.",
            timestamp = timestamp,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false,
            excursionStart = gap.startTimestamp
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
        id: String = alertId(shipmentId, AlertType.LOGGER_DISCONNECT, timestamp)
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
        id: String = alertId(shipmentId, AlertType.OPERATIONAL_WARNING, timestamp)
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
     * The level an alert should have reached by [now] if nobody has acknowledged it.
     */
    private fun levelDue(alert: Alert, now: Long): EscalationLevel {
        val intervals = (now - alert.timestamp) / ESCALATION_INTERVAL_MS
        return when {
            intervals >= 2 -> EscalationLevel.PHARMACIST
            intervals >= 1 -> EscalationLevel.SUPERVISOR
            else -> EscalationLevel.WORKER
        }
    }

    /**
     * Returns the alerts whose timer has run out, each at the level it should now hold.
     * The level comes from the elapsed time, so a late check can't leave an alert behind, and it
     * only ever goes up, so a level set by hand is kept.
     */
    fun dueEscalations(alerts: List<Alert>, now: Long): List<Alert> =
        alerts
            .filter { !it.acknowledged && levelDue(it, now) > it.escalationLevel }
            .map { it.copy(escalationLevel = levelDue(it, now)) }

    /**
     * Returns when the next alert in [alerts] is due to escalate, or null if none can.
     */
    fun nextEscalationAt(alerts: List<Alert>): Long? =
        alerts
            .filter { !it.acknowledged && it.escalationLevel != EscalationLevel.PHARMACIST }
            .minOfOrNull { it.timestamp + (it.escalationLevel.ordinal + 1) * ESCALATION_INTERVAL_MS }

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
    fun acknowledgeAlert(alert: Alert, timestamp: Long = System.currentTimeMillis()): Alert {
        return alert.copy(acknowledged = true, acknowledgedAt = timestamp)
    }

    /**
     * Acknowledges an alert and persists the updated alert via [AlertRepository].
     */
    suspend fun acknowledgeAndPersist(alert: Alert, repository: AlertRepository): Result<Alert> {
        val acknowledged = acknowledgeAlert(alert)
        return repository.saveAlert(acknowledged).map { acknowledged }
    }
}
