package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.repository.ShipmentRepository

/**
 * Unified coordinator for the Person 3 Safety Decision Layer.
 *
 * Coordinates:
 * - Thermal breach evaluation via [BreachDetector] (the single authoritative calculation path)
 * - Alert generation, escalation, and persistence via [AlertEscalationEngine] and [AlertRepository]
 * - Quarantine status transitions and persistence via [QuarantineManager] and [ShipmentRepository]
 * - Explicit logger disconnect handling via [LoggerDisconnectMonitor]
 * - Handover temperature verification producing the boolean [temperaturePassed]
 */
class SafetyEngine(
    private val alertRepository: AlertRepository? = null,
    private val shipmentRepository: ShipmentRepository? = null,
    private val alertNotifier: AlertNotifier = AlertNotifier.NONE
) {

    /**
     * Pure evaluation of a shipment and its temperature history against thermal safety rules.
     * Uses [BreachDetector.evaluateTelemetry] as the single authoritative calculation path.
     */
    fun evaluateShipment(
        shipment: Shipment,
        events: List<TemperatureEvent>,
        windowEnd: Long? = null
    ): SafetyDecision {
        val evaluation = BreachDetector.evaluateTelemetry(
            events = events,
            profile = ProductProfiles.byId(shipment.productProfileId),
            windowStart = shipment.dispatchedAt,
            windowEnd = windowEnd
        )
        val shouldQuarantine = QuarantineManager.shouldQuarantine(evaluation)
        val formattedDuration = BreachDetector.formatDuration(evaluation.cumulativeBreachDurationMs)

        return SafetyDecision(
            shipmentId = shipment.id,
            evaluation = evaluation,
            shouldQuarantine = shouldQuarantine,
            breachDurationFormatted = formattedDuration
        )
    }

    /**
     * Evaluates whether a shipment's temperature history passes custody handover inspection.
     * Consumed directly by Person 4 / Handover layer.
     *
     * Rules:
     * - safe history -> true
     * - exactly on a limit -> true
     * - within the product's budget -> true
     * - past the budget, or a gap in the record -> false
     */
    fun evaluateHandoverTemperatureSafety(shipment: Shipment, events: List<TemperatureEvent>): Boolean {
        return evaluateShipment(shipment, events).evaluation.temperaturePassed
    }

    /**
     * Orchestrates end-to-end safety evaluation and repository persistence:
     * 1. Evaluates temperature history via [BreachDetector].
     * 2. Persists an alert for each breached excursion and data gap that has none yet, starting at
     *    [com.coldchain.handshake.models.EscalationLevel.WORKER], and notifies. Existing alerts are
     *    left exactly as they are, so a repeat evaluation can't undo an acknowledgement.
     * 3. If a breach is detected, applies quarantine transition to
     *    [com.coldchain.handshake.models.ShipmentStatus.QUARANTINED] (preserving all other Shipment
     *    fields) and persists to [ShipmentRepository].
     * 4. Surfaces persistence results on [SafetyDecision] so failures are never silently swallowed.
     *    If required persistence repositories are unavailable, explicit failures are surfaced
     *    and [SafetyDecision.hasPersistenceFailure] will be true.
     * 5. Returns the resulting [SafetyDecision].
     */
    suspend fun processTelemetryAndEnforceSafety(
        shipment: Shipment,
        events: List<TemperatureEvent>,
        timestamp: Long = System.currentTimeMillis()
    ): SafetyDecision {
        val decision = evaluateShipment(shipment, events)
        val alertSaveResult = saveNewAlerts(
            AlertEscalationEngine.alertsFor(shipment.id, decision.evaluation, timestamp)
        )

        if (decision.evaluation.hasBreach) {
            // Quarantine shipment and persist if status changed
            val quarantinedShipment = QuarantineManager.applyQuarantine(shipment, decision.evaluation)
            val quarantineSaveResult = if (quarantinedShipment.status != shipment.status) {
                shipmentRepository?.saveShipment(quarantinedShipment)
                    ?: Result.failure(IllegalStateException("ShipmentRepository is required to persist shipment quarantine"))
            } else {
                null
            }

            return decision.copy(
                alertSaveResult = alertSaveResult,
                quarantineSaveResult = quarantineSaveResult
            )
        }

        return decision.copy(alertSaveResult = alertSaveResult)
    }

    private suspend fun saveNewAlerts(alerts: List<Alert>): Result<Unit>? {
        if (alerts.isEmpty()) return null
        val repo = alertRepository
            ?: return Result.failure(IllegalStateException("AlertRepository is required to persist breach alerts"))

        return runCatching {
            alerts.forEach { alert ->
                if (repo.saveAlertIfAbsent(alert).getOrThrow()) {
                    alertNotifier.onAlertRaised(alert)
                }
            }
        }
    }

    /**
     * Advances an alert to the next deterministic escalation tier:
     * WORKER -> SUPERVISOR -> PHARMACIST (terminal).
     * Persists the updated alert to [AlertRepository], surfacing any repository failure.
     * Throws [IllegalStateException] if [AlertRepository] is unavailable.
     */
    suspend fun escalateAlert(alert: Alert): Alert {
        val repo = alertRepository
            ?: throw IllegalStateException("AlertRepository is required to persist escalated alert")
        val escalated = AlertEscalationEngine.escalateAlert(alert)
        val saveResult = repo.saveAlert(escalated)
        saveResult.getOrThrow()
        return escalated
    }

    /**
     * Acknowledges an alert and persists the change to [AlertRepository],
     * surfacing any repository failure.
     * Throws [IllegalStateException] if [AlertRepository] is unavailable.
     */
    suspend fun acknowledgeAlert(alert: Alert): Alert {
        val repo = alertRepository
            ?: throw IllegalStateException("AlertRepository is required to persist acknowledged alert")
        val acknowledged = AlertEscalationEngine.acknowledgeAlert(alert)
        val saveResult = repo.saveAlert(acknowledged)
        saveResult.getOrThrow()
        return acknowledged
    }

    /**
     * Handles an explicit logger disconnect event, creating a [com.coldchain.handshake.models.AlertType.LOGGER_DISCONNECT]
     * alert starting at [com.coldchain.handshake.models.EscalationLevel.WORKER] and persisting it to [AlertRepository],
     * surfacing any repository failure.
     * Throws [IllegalStateException] if [AlertRepository] is unavailable.
     */
    suspend fun handleLoggerDisconnect(
        shipmentId: String,
        loggerId: String,
        reason: String = "Sensor connection dropped",
        timestamp: Long = System.currentTimeMillis()
    ): Alert {
        val repo = alertRepository
            ?: throw IllegalStateException("AlertRepository is required to persist disconnect alert")
        val alert = LoggerDisconnectMonitor.onLoggerDisconnected(
            shipmentId = shipmentId,
            loggerId = loggerId,
            reason = reason,
            timestamp = timestamp
        )
        val saveResult = repo.saveAlert(alert)
        saveResult.getOrThrow()
        return alert
    }

    /**
     * Non-suspending alert factory helper.
     */
    fun createBreachAlertIfViolated(
        shipment: Shipment,
        events: List<TemperatureEvent>,
        timestamp: Long = System.currentTimeMillis()
    ): Alert? {
        val evaluation = evaluateShipment(shipment, events).evaluation
        return AlertEscalationEngine.alertsFor(shipment.id, evaluation, timestamp)
            .firstOrNull { it.type != AlertType.DATA_GAP }
    }

    /**
     * Non-suspending quarantine helper.
     */
    fun applyQuarantineIfBreached(
        shipment: Shipment,
        events: List<TemperatureEvent>
    ): Shipment {
        val evaluation = evaluateShipment(shipment, events).evaluation
        return QuarantineManager.applyQuarantine(shipment, evaluation)
    }
}
