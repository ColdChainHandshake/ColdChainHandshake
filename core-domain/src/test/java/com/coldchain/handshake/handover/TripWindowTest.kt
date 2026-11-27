package com.coldchain.handshake.handover

import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a shipment's temperature record is checked up to. Found on the emulator (27 Nov): a phone that opened
 * `SHIP-2DE57A` two hours after the receiver accepted it showed "UNKNOWN (gap in the readings)", because the
 * silence after the last reading was counted up to that moment, though the trip had ended.
 */
class TripWindowTest {

    private val now = 1_700_010_000_000L
    private val acceptedAt = 1_700_000_000_000L

    @Test
    fun onceAReceiverHoldsTheShipmentTheRecordEndsWhenTheyTookIt() {
        assertEquals(acceptedAt, TripWindow.end(CustodyState(3, "receiver", sinceMs = acceptedAt), MemberRole.RECEIVER, now))
    }

    @Test
    fun whileACourierHoldsItTheRecordRunsUpToNow() {
        assertEquals(now, TripWindow.end(CustodyState(2, "courier-b", sinceMs = acceptedAt), MemberRole.COURIER, now))
    }

    // With no time from the server the end can't be shown, so the silence still counts up to now: never more lenient.
    @Test
    fun withNoTimeFromTheServerTheRecordRunsUpToNow() {
        assertEquals(now, TripWindow.end(CustodyState(3, "receiver"), MemberRole.RECEIVER, now))
        assertEquals(now, TripWindow.end(null, null, now))
    }

    @Test
    fun aTimeFromTheServerAfterNowIsNotTrusted() {
        assertEquals(now, TripWindow.end(CustodyState(3, "receiver", sinceMs = now + 60_000L), MemberRole.RECEIVER, now))
    }
}
