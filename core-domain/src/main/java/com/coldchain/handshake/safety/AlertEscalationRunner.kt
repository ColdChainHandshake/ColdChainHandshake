package com.coldchain.handshake.safety

import com.coldchain.handshake.repository.AlertRepository

/**
 * One pass of the escalation timer. It holds no schedule of its own, so whatever runs it (on
 * Android, a WorkManager job) can restart after process death and pick up from stored alerts.
 */
class AlertEscalationRunner(
    private val alertRepository: AlertRepository,
    private val alertNotifier: AlertNotifier = AlertNotifier.NONE,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Applies every escalation that is due, notifying once per shipment.
     *
     * @return when the next escalation falls due, or null if none is pending.
     */
    suspend fun run(): Long? {
        val openAlerts = alertRepository.getOpenAlerts()

        val escalated = AlertEscalationEngine.dueEscalations(openAlerts, clock()).filter { alert ->
            alertRepository.escalateIfUnacknowledged(alert.id, alert.escalationLevel).getOrThrow()
        }

        // A backlog after time away must not become hundreds of notifications at once.
        escalated.groupBy { it.shipmentId }.values.forEach { alerts ->
            alertNotifier.onAlertEscalated(alerts.maxBy { it.escalationLevel })
        }

        return AlertEscalationEngine.nextEscalationAt(alertRepository.getOpenAlerts())
    }
}
