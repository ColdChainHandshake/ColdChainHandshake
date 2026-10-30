package com.coldchain.handshake.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.coldchain.handshake.MainActivity
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.safety.AlertNotifier

/**
 * Posts alerts as local notifications. Each alert keeps one notification, so an escalation replaces
 * it rather than adding another.
 */
class AndroidAlertNotifier(context: Context) : AlertNotifier {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cold chain alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onAlertRaised(alert: Alert) {
        post(alert)
        AlertEscalationScheduler.checkNow(appContext)
    }

    override fun onAlertEscalated(alert: Alert) {
        post(alert)
    }

    private fun post(alert: Alert) {
        if (!canPostNotifications()) return

        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("${alert.escalationLevel.label()} alert: ${alert.shipmentId}")
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        manager.notify(alert.id.hashCode(), notification)
    }

    // Before Android 13 this permission doesn't exist as a runtime permission and would read as denied.
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun EscalationLevel.label(): String = when (this) {
        EscalationLevel.WORKER -> "Worker"
        EscalationLevel.SUPERVISOR -> "Supervisor"
        EscalationLevel.PHARMACIST -> "Pharmacist"
    }

    companion object {
        const val CHANNEL_ID = "cold_chain_alerts"
    }
}
