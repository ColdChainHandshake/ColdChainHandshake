package com.coldchain.handshake.recording

import android.content.Context
import android.content.SharedPreferences

/**
 * Which shipment this phone is recording, kept so recording can pick up again after the process was restarted.
 * Null when nothing is recording: a pause, a handover or a sign-out clears it.
 */
interface RecordingStore {
    var shipmentId: String?

    companion object {
        fun inMemory(): RecordingStore = object : RecordingStore {
            @Volatile override var shipmentId: String? = null
        }
    }
}

/** [RecordingStore] in the app's private preferences. Holds a shipment ID only. */
class SharedPreferencesRecordingStore(private val prefs: SharedPreferences) : RecordingStore {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override var shipmentId: String?
        get() = prefs.getString(KEY_SHIPMENT, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_SHIPMENT) else putString(KEY_SHIPMENT, value) }.apply()
        }

    companion object {
        const val PREFS = "recording"
        private const val KEY_SHIPMENT = "shipmentId"
    }
}
