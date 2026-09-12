package com.coldchain.handshake.util

import android.content.Context
import java.util.UUID

/**
 * Provides a persistent unique device identity for shipment custody tracking.
 * Stored locally in SharedPreferences across application restarts.
 * This is NOT authentication; it serves solely for physical custody ownership tracking.
 */
object DeviceIdProvider {
    private const val PREFS_NAME = "coldchain_device_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cachedDeviceId: String? = null

    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = "DEV-" + UUID.randomUUID().toString().take(8).uppercase()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        cachedDeviceId = id
        return id
    }

    /**
     * Resets or overrides device ID (useful for multi-device simulation in tests).
     */
    fun setDeviceIdForTesting(id: String?) {
        cachedDeviceId = id
    }
}
