package com.coldchain.handshake.safety

import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreachDetectorTest {

    private fun createEvent(
        id: String,
        timestamp: Long,
        temperature: Double,
        shipmentId: String = "SHIP-001",
        loggerId: String = "LOG-001"
    ): TemperatureEvent {
        return TemperatureEvent(
            id = id,
            shipmentId = shipmentId,
            loggerId = loggerId,
            timestamp = timestamp,
            temperature = temperature,
            previousHash = "0000",
            currentHash = "abcd",
            syncStatus = SyncStatus.SYNCED
        )
    }

    @Test
    fun exact8DegreesIsSafe() {
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 8.0),
            createEvent("e2", baseTime + 180_000L, 8.0),
            createEvent("e3", baseTime + 360_000L, 8.0),
            createEvent("e4", baseTime + 600_000L, 8.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals("Cumulative breach duration must be 0 for 8.0°C", 0L, result.cumulativeBreachDurationMs)
        assertFalse("8.0°C exact must not trigger a breach", result.hasBreach)
        assertTrue("8.0°C exact must pass temperature inspection", result.temperaturePassed)
        assertNull("No excursion temperature should be recorded", result.maxExcursionTemperature)
        assertEquals(0, result.excursionIntervalCount)
    }

    @Test
    fun sub8DegreesIsSafe() {
        // Typical normal operation: 4°C - 6°C at 2-minute simulated logger intervals
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 4.0),
            createEvent("e2", baseTime + 120_000L, 5.5),
            createEvent("e3", baseTime + 240_000L, 6.0),
            createEvent("e4", baseTime + 360_000L, 4.5)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(0L, result.cumulativeBreachDurationMs)
        assertFalse(result.hasBreach)
        assertTrue(result.temperaturePassed)
    }

    @Test
    fun exactly5MinutesIsSafe() {
        // Excursion begins at baseTime (9.0°C).
        // Next reading at exactly baseTime + 300,000ms (5 mins) drops to 5.0°C.
        // Elapsed duration is exactly 300,000ms.
        // Rule: cumulative proven duration strictly > 5 minutes is a breach.
        // Therefore, exactly 5:00 is NOT a breach.
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.0),
            createEvent("e2", baseTime + 300_000L, 5.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(300_000L, result.cumulativeBreachDurationMs)
        assertFalse("Exactly 5 minutes must NOT trigger a breach", result.hasBreach)
        assertTrue("Exactly 5 minutes must pass temperature check", result.temperaturePassed)
        assertEquals(9.0, result.maxExcursionTemperature!!, 0.001)
        assertEquals(1, result.excursionIntervalCount)
    }

    @Test
    fun exceeding5MinutesIsBreach() {
        // Excursion begins at baseTime (9.2°C).
        // Next reading at baseTime + 300,001ms (5 mins and 1 millisecond).
        // Duration > 300,000ms.
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.2),
            createEvent("e2", baseTime + 300_001L, 5.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(300_001L, result.cumulativeBreachDurationMs)
        assertTrue("Exceeding 5 minutes by 1ms MUST trigger a breach", result.hasBreach)
        assertFalse("Exceeding 5 minutes MUST NOT pass temperature check", result.temperaturePassed)
        assertEquals(9.2, result.maxExcursionTemperature!!, 0.001)
    }

    @Test
    fun realisticProblemStatementSequence() {
        // Exact sequence from specification:
        // 0:00   5°C
        // 2:00   9°C
        // 4:00   5°C  (2 minutes hot: 120,000 ms)
        // 6:00   9°C
        // 9:01   5°C  (3 minutes 1 second hot: 181,000 ms)
        // Total hot: 120,000 + 181,000 = 301,000 ms -> BREACH
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime + 0L, 5.0),
            createEvent("e2", baseTime + 120_000L, 9.0),
            createEvent("e3", baseTime + 240_000L, 5.0),
            createEvent("e4", baseTime + 360_000L, 9.0),
            createEvent("e5", baseTime + 541_000L, 5.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(301_000L, result.cumulativeBreachDurationMs)
        assertTrue("Cumulative excursion > 5 mins must trigger breach", result.hasBreach)
        assertFalse("Must fail handover temperature check", result.temperaturePassed)
        assertEquals(2, result.excursionIntervalCount)
        assertEquals(9.0, result.maxExcursionTemperature!!, 0.001)
    }

    @Test
    fun networkDropoutDoesNotFabricateBreachDuration() {
        // Simulates a 10-minute network dropout during transit:
        // At T=0, safe (5°C).
        // At T=2m, spike (9°C).
        // At T=4m, safe again (5°C) -> proven hot time is 2 minutes (120,000 ms).
        // Network drops for 10 minutes until T=14m when next reading is received (5°C).
        // Safety engine must NOT assume it was hot during the 10-minute dropout.
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime + 0L, 5.0),
            createEvent("e2", baseTime + 120_000L, 9.0),
            createEvent("e3", baseTime + 240_000L, 5.0),
            createEvent("e4", baseTime + 840_000L, 5.0) // 10 minutes after e3
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(120_000L, result.cumulativeBreachDurationMs)
        assertFalse("Dropout must not fabricate hot duration", result.hasBreach)
        assertTrue(result.temperaturePassed)
    }

    @Test
    fun multipleSeparatedIntervalsAccumulate() {
        // Interval 1: 3 minutes hot (180,000ms) from t=0 to t=180,000
        // Safe period: from t=180,000 to t=600,000
        // Interval 2: 2 minutes 1 second hot (121,000ms) from t=600,000 to t=721,000
        // Total cumulative hot time = 180,000 + 121,000 = 301,000ms (> 5 minutes)
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.5),
            createEvent("e2", baseTime + 180_000L, 5.0),
            createEvent("e3", baseTime + 300_000L, 4.5),
            createEvent("e4", baseTime + 600_000L, 10.0),
            createEvent("e5", baseTime + 721_000L, 6.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(301_000L, result.cumulativeBreachDurationMs)
        assertTrue("Separated intervals totaling > 5 mins must trigger breach", result.hasBreach)
        assertFalse(result.temperaturePassed)
        assertEquals(10.0, result.maxExcursionTemperature!!, 0.001)
        assertEquals(2, result.excursionIntervalCount)
    }

    @Test
    fun irregularTimestampsAccumulateAccurately() {
        // Telemetry readings at arbitrary non-uniform intervals
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 8.5),
            createEvent("e2", baseTime + 47_320L, 9.1),
            createEvent("e3", baseTime + 152_800L, 8.8),
            createEvent("e4", baseTime + 305_000L, 4.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(305_000L, result.cumulativeBreachDurationMs)
        assertTrue(result.hasBreach)
        assertFalse(result.temperaturePassed)
    }

    @Test
    fun finalEventNonExtrapolation() {
        // Event 1 is safe, Event 2 is hot but is the FINAL event in the list.
        // We cannot extrapolate how long Event 2 stayed hot after its timestamp.
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 5.0),
            createEvent("e2", baseTime + 60_000L, 12.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals("Must not extrapolate beyond the final event", 0L, result.cumulativeBreachDurationMs)
        assertFalse(result.hasBreach)
        assertTrue(result.temperaturePassed)
        assertEquals(12.0, result.maxExcursionTemperature!!, 0.001)
    }

    @Test
    fun singleIsolatedHotEventDoesNotProveBreach() {
        // Only one event in the entire history, and it is > 8°C.
        // Duration is 0ms because no subsequent event proves elapsed time.
        val singleHot = createEvent("e1", 1_700_000_000_000L, 11.0)
        val result = BreachDetector.evaluateTelemetry(listOf(singleHot))

        assertEquals(0L, result.cumulativeBreachDurationMs)
        assertFalse("Single hot event without duration must not breach", result.hasBreach)
        assertTrue(result.temperaturePassed)
        assertEquals(11.0, result.maxExcursionTemperature!!, 0.001)
    }

    @Test
    fun outOfOrderEventsAreSortedDeterministically() {
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e3", baseTime + 301_000L, 5.0),
            createEvent("e1", baseTime, 9.0),
            createEvent("e2", baseTime + 100_000L, 9.5)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(301_000L, result.cumulativeBreachDurationMs)
        assertTrue(result.hasBreach)
    }

    @Test
    fun equalTimestampsDeterministicTieBreak() {
        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e2", baseTime, 9.0),
            createEvent("e1", baseTime, 9.0),
            createEvent("e3", baseTime + 301_000L, 5.0)
        )

        val result = BreachDetector.evaluateTelemetry(events)

        assertEquals(301_000L, result.cumulativeBreachDurationMs)
        assertTrue(result.hasBreach)
    }

    @Test
    fun emptyAndSingleEventHandling() {
        val emptyResult = BreachDetector.evaluateTelemetry(emptyList())
        assertEquals(0L, emptyResult.cumulativeBreachDurationMs)
        assertFalse(emptyResult.hasBreach)
        assertTrue(emptyResult.temperaturePassed)

        val singleSafe = BreachDetector.evaluateTelemetry(listOf(createEvent("e1", 1000L, 5.0)))
        assertEquals(0L, singleSafe.cumulativeBreachDurationMs)
        assertFalse(singleSafe.hasBreach)
        assertTrue(singleSafe.temperaturePassed)
    }
}
