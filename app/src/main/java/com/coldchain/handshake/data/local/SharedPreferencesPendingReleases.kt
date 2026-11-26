package com.coldchain.handshake.data.local

import android.content.Context
import android.content.SharedPreferences
import com.coldchain.handshake.handover.PendingReleases

/**
 * The custodian's handovers still waiting for the other person, kept in the app's private preferences so the
 * background sync can renew them after the app was closed or its process died. Holds shipment IDs and user IDs only.
 */
class SharedPreferencesPendingReleases(private val prefs: SharedPreferences) : PendingReleases {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun get(shipmentId: String): String? = prefs.getString(shipmentId, null)

    override fun put(shipmentId: String, toUserId: String) {
        prefs.edit().putString(shipmentId, toUserId).apply()
    }

    override fun remove(shipmentId: String) {
        prefs.edit().remove(shipmentId).apply()
    }

    override fun shipmentIds(): Set<String> = prefs.all.keys.toSet()

    /** Forgets everything, for the next person on this phone. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS = "pending_releases"
    }
}
