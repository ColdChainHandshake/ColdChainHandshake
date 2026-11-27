package com.coldchain.handshake.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * What recording in the background needs from the person, asked for whenever they take custody as a courier: on
 * Transit when a shipment leaves the sender, and on Handover when they take over from another courier. Nearby devices
 * lets the recording service run in the foreground (its `connectedDevice` type, Android 12 and later), so it keeps
 * recording with the screen off; location lets it add its `location` type and record where the shipment is. Refusing
 * either only limits recording: custody is taken anyway.
 */
object RecordingPermissions {

    val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * What is still to be asked for, on Android [sdkInt]. Nearby devices is asked for only from Android 12 ([sdkInt]
     * is the guard, which lint can't follow); its name is a string inlined at compile time, safe on older versions.
     */
    @SuppressLint("InlinedApi")
    fun missing(sdkInt: Int, granted: (String) -> Boolean): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (LOCATION.none(granted)) addAll(LOCATION)
    }

    fun missing(context: Context): List<String> = missing(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
