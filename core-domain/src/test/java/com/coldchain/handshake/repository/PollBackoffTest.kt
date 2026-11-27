package com.coldchain.handshake.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Finding 11: a screen watching a shipment asked the server every 3 s whether anything had changed. It still asks
 * quickly while readings or custody move, and less often each time nothing has, so a watcher left open costs a few
 * requests a minute rather than twenty.
 */
class PollBackoffTest {

    @Test
    fun whileThingsChangeItAsksAgainQuickly() {
        val backoff = PollBackoff(minMs = 3_000, maxMs = 30_000)

        repeat(5) { assertEquals(3_000L, backoff.after(changed = true)) }
    }

    @Test
    fun eachQuietRoundDoublesTheWaitUpToTheMaximum() {
        val backoff = PollBackoff(minMs = 3_000, maxMs = 30_000)

        val waits = List(6) { backoff.after(changed = false) }

        assertEquals(listOf(6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L), waits)
    }

    @Test
    fun aChangeBringsTheWaitBackToTheMinimum() {
        val backoff = PollBackoff(minMs = 3_000, maxMs = 30_000)
        repeat(4) { backoff.after(changed = false) }

        assertEquals(3_000L, backoff.after(changed = true))
        assertEquals(6_000L, backoff.after(changed = false))
    }

    @Test
    fun aResetStartsQuickAgain() {
        val backoff = PollBackoff(minMs = 3_000, maxMs = 30_000)
        repeat(4) { backoff.after(changed = false) }

        backoff.reset()

        assertEquals(6_000L, backoff.after(changed = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMinimumAboveTheMaximumIsRefused() {
        PollBackoff(minMs = 30_000, maxMs = 3_000)
    }
}
