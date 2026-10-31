package com.coldchain.handshake.util

import android.content.Context
import java.util.UUID

/**
 * Names this install to the server, which is why it's a UUID. A name proves nothing alone: the
 * device's key does that, and the two are registered together.
 *
 * It stays the same when someone signs out, so one phone can't confirm both sides of a handover by
 * switching accounts. The server refuses a device already registered to someone else.
 */
object DeviceIdProvider {
    private const val PREFS_NAME = "coldchain_device_prefs"

    // Earlier versions kept a shorter ID under "device_id", which the server can't accept.
    private const val KEY_DEVICE_ID = "device_uuid"

    @Volatile
    private var cachedDeviceId: String? = null

    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
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
