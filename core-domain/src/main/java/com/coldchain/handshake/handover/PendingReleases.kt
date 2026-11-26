package com.coldchain.handshake.handover

import java.util.concurrent.ConcurrentHashMap

/**
 * The custodian's handovers still waiting for the other person: for each shipment, who was named to take over.
 *
 * The courier keeps recording after confirming, so the server's head moves on, and the server only moves custody
 * when both confirmations are on the current head. The phone therefore renews this confirmation on each new head
 * until custody moves. Kept on the phone, so a renewal still happens after the app was closed.
 */
interface PendingReleases {
    /** The person named to take over [shipmentId], or null when nothing is waiting. */
    fun get(shipmentId: String): String?

    fun put(shipmentId: String, toUserId: String)

    fun remove(shipmentId: String)

    /** Every shipment with a handover waiting. */
    fun shipmentIds(): Set<String>

    companion object {
        /** Kept in memory only, for tests and for builds with no server. */
        fun inMemory(): PendingReleases = object : PendingReleases {
            private val named = ConcurrentHashMap<String, String>()
            override fun get(shipmentId: String) = named[shipmentId]
            override fun put(shipmentId: String, toUserId: String) {
                named[shipmentId] = toUserId
            }
            override fun remove(shipmentId: String) {
                named.remove(shipmentId)
            }
            override fun shipmentIds(): Set<String> = named.keys.toSet()
        }
    }
}
