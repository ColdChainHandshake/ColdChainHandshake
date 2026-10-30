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
     * Applies every escalation that is due.
     *
     * @return when the next escalation falls due, or null if none is pending.
     */
    suspend fun run(): Long? {
        val openAlerts = alertRepository.getOpenAlerts()

        AlertEscalationEngine.dueEscalations(openAlerts, clock()).forEach { alert ->
            if (alertRepository.escalateIfUnacknowledged(alert.id, alert.escalationLevel).getOrThrow()) {
                alertNotifier.onAlertEscalated(alert)
            }
        }

        return AlertEscalationEngine.nextEscalationAt(alertRepository.getOpenAlerts())
    }
}
