package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClockSkewDetectorTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun reading(seq: Long, timestamp: Long, receivedAt: Long? = null) = TemperatureEvent(
        id = "TE-$seq",
        shipmentId = "SHIP-1",
        loggerId = "LOG-1",
        timestamp = timestamp,
        temperature = 5.0,
        previousHash = "",
        currentHash = "",
        syncStatus = SyncStatus.SYNCED,
        seq = seq,
        receivedAt = receivedAt
    )

    @Test
    fun theToleranceIsTheServersOwn() {
        val migration = listOf(File("../supabase/migrations/0002_trust_core.sql"), File("supabase/migrations/0002_trust_core.sql"))
            .first { it.exists() }
            .readText()
        val serverMs = Regex("""clock_tolerance_ms\(\) returns bigint\s+language sql immutable set search_path = '' as 'select (\d+)::bigint'""")
            .find(migration)!!.groupValues[1].toLong()

        assertEquals(serverMs, ClockSkewDetector.TOLERANCE_MS)
        assertEquals(5 * 60_000L, ClockSkewDetector.TOLERANCE_MS)
    }

    @Test
    fun honestReadingsHaveNoIssues() {
        val readings = listOf(
            reading(1, t0, receivedAt = t0 + minute),
            reading(2, t0 + minute, receivedAt = t0 + 2 * minute)
        )

        assertTrue(ClockSkewDetector.detect(readings).isEmpty())
    }

    @Test
    fun aReadingDatedAfterItReachedTheServerIsFlagged() {
        val ahead = t0 + ClockSkewDetector.TOLERANCE_MS + 1

        val issues = ClockSkewDetector.detect(listOf(reading(1, ahead, receivedAt = t0)))

        assertEquals(listOf(ClockIssue(1, ClockIssueType.AHEAD_OF_SERVER)), issues)
    }

    @Test
    fun aSmallDriftWithinTheToleranceIsNotFlagged() {
        val issues = ClockSkewDetector.detect(listOf(reading(1, t0 + ClockSkewDetector.TOLERANCE_MS, receivedAt = t0)))

        assertTrue(issues.isEmpty())
    }

    @Test
    fun aReadingDatedBeforeTheOneBeforeItIsFlagged() {
        val issues = ClockSkewDetector.detect(listOf(reading(1, t0 + minute), reading(2, t0)))

        assertEquals(listOf(ClockIssue(2, ClockIssueType.BACKWARDS)), issues)
    }

    @Test
    fun readingsNotYetOnTheServerAreOnlyCheckedForOrder() {
        val issues = ClockSkewDetector.detect(listOf(reading(1, t0 + 365L * 24 * 60 * minute)))

        assertTrue(issues.isEmpty())
    }

    @Test
    fun readingsAreComparedInSequenceOrderHoweverTheyArrive() {
        val issues = ClockSkewDetector.detect(listOf(reading(2, t0), reading(1, t0 + minute)))

        assertEquals(listOf(ClockIssue(2, ClockIssueType.BACKWARDS)), issues)
    }
}
