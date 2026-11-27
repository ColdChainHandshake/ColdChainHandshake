package com.coldchain.handshake.handover

import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.MemberRole

/**
 * Where a shipment's temperature record is checked up to. While a courier holds it the trip goes on, so silence after
 * the newest reading counts up to now. Once a receiver holds it the trip ended when they took it, so a phone opening
 * it later doesn't count the time since as a gap. Never more lenient than now: without the server's time, or with one
 * that is still to come, the record runs up to now.
 */
object TripWindow {

    fun end(custody: CustodyState?, custodianRole: MemberRole?, now: Long): Long {
        val handedOverAt = custody?.sinceMs ?: return now
        return if (custodianRole == MemberRole.RECEIVER && handedOverAt <= now) handedOverAt else now
    }
}
