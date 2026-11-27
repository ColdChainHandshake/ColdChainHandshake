package com.coldchain.handshake.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * A permission refused for good: Android no longer shows its prompt, so asking again does nothing at all. The way left
 * is this app's page in the system settings. Decided after a request came back refused, when Android would no longer
 * explain why the app asks.
 */
object PermissionSettings {

    fun refusedForGood(context: Context, permissions: Collection<String>, results: Map<String, Boolean>): Boolean {
        val activity = context.findActivity() ?: return false
        val refused = permissions.filter { results[it] == false }
        return refused.isNotEmpty() && refused.none { activity.shouldShowRequestPermissionRationale(it) }
    }

    fun open(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
