package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert

/**
 * Tells people about alerts. Keeps the safety rules free of Android, which supplies the real one.
 */
interface AlertNotifier {
    fun onAlertRaised(alert: Alert)
    fun onAlertEscalated(alert: Alert)

    companion object {
        val NONE = object : AlertNotifier {
            override fun onAlertRaised(alert: Alert) = Unit
            override fun onAlertEscalated(alert: Alert) = Unit
        }
    }
}
