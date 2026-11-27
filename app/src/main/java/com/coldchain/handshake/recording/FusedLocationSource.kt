package com.coldchain.handshake.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fixes from Google Play services' fused location provider, one a minute like the readings. Asked for only while the
 * recording service runs with the `location` type, which is what lets them go on with the screen off.
 */
class FusedLocationSource(private val context: Context) : LocationSource {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var callback: LocationCallback? = null

    override fun start(onFix: (LocationFix) -> Unit) {
        stop()
        if (!allowed()) return
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { onFix(LocationFix(it.latitude, it.longitude, it.accuracy, it.time)) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
            callback = listener
        } catch (_: SecurityException) {
            // Taken away between the check and the request: no fixes, and the readings carry on.
        }
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun allowed(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    companion object {
        const val INTERVAL_MS = 60_000L
    }
}
