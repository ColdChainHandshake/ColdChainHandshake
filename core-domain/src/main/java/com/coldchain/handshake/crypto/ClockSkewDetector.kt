package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.TemperatureEvent

enum class ClockIssueType {
    AHEAD_OF_SERVER,
    BACKWARDS
}

data class ClockIssue(
    val seq: Long,
    val type: ClockIssueType
)

/**
 * A logger's clock can't be trusted, so readings are compared with the server's receive time and with
 * each other. The server refuses back-dated readings, and this finds the rest for the handover screen.
 */
object ClockSkewDetector {

    /**
     * Matches `private.clock_tolerance_ms()` on the server.
     */
    const val TOLERANCE_MS = 5 * 60 * 1000L

    fun detect(events: List<TemperatureEvent>): List<ClockIssue> {
        val issues = mutableListOf<ClockIssue>()
        var previous: TemperatureEvent? = null

        for (event in events.sortedBy { it.seq }) {
            val receivedAt = event.receivedAt
            if (receivedAt != null && event.timestamp > receivedAt + TOLERANCE_MS) {
                issues.add(ClockIssue(event.seq, ClockIssueType.AHEAD_OF_SERVER))
            }
            if (previous != null && event.timestamp < previous.timestamp) {
                issues.add(ClockIssue(event.seq, ClockIssueType.BACKWARDS))
            }
            previous = event
        }

        return issues
    }
}
