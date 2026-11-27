package com.coldchain.handshake.repository

/**
 * How long a screen or loop watching the server waits before asking again (finding 11): [minMs] while something
 * changes, then twice as long after each round where nothing did, up to [maxMs]. Any change brings it back to [minMs].
 */
class PollBackoff(private val minMs: Long = MIN_MS, private val maxMs: Long = MAX_MS) {

    init {
        require(minMs in 1..maxMs) { "The shortest wait must be positive and no longer than the longest." }
    }

    private var waitMs = minMs

    /** Call after each round with whether it found anything new; returns how long to wait before the next. */
    fun after(changed: Boolean): Long {
        waitMs = if (changed) minMs else minOf(waitMs * 2, maxMs)
        return waitMs
    }

    /** Starts quick again, as after a change. */
    fun reset() {
        waitMs = minMs
    }

    companion object {
        const val MIN_MS = 3_000L
        const val MAX_MS = 30_000L
    }
}
