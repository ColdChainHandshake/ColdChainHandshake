package com.coldchain.handshake.safety

import com.coldchain.handshake.crypto.ClockSkewDetector
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductProfileEvaluationTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_700_000_000_000L
    private val strict = ProductProfiles.FREEZE_SENSITIVE_VACCINE
    private val tolerant = ProductProfiles.FREEZE_TOLERANT

    private fun reading(offsetMs: Long, temperature: Double, id: String = "E-$offsetMs") = TemperatureEvent(
        id = id,
        shipmentId = "SHIP-1",
        loggerId = "LOG-1",
        timestamp = t0 + offsetMs,
        temperature = temperature,
        previousHash = "",
        currentHash = "",
        syncStatus = SyncStatus.SYNCED
    )

    private fun minutely(vararg temperatures: Double) =
        temperatures.mapIndexed { index, temperature -> reading(index * minute, temperature) }

    @Test
    fun theSeedProfileMatchesTheBrief() {
        assertEquals(2.0, strict.minC, 0.0)
        assertEquals(8.0, strict.maxC, 0.0)
        assertEquals(0, strict.maxExcursionMinutesFreeze)
        assertEquals(strict, ProductProfiles.DEFAULT)
    }

    @Test
    fun anUnknownProfileIdFallsBackToTheStrictestProfile() {
        assertEquals(strict, ProductProfiles.byId("no-such-profile"))
        assertEquals(tolerant, ProductProfiles.byId(tolerant.id))
    }

    @Test
    fun readingsOnBothLimitsAreInRange() {
        val result = BreachDetector.evaluateTelemetry(minutely(2.0, 8.0, 2.0, 8.0), strict)

        assertEquals(TemperatureVerdict.PASS, result.verdict)
        assertTrue(result.temperaturePassed)
        assertTrue(result.excursions.isEmpty())
    }

    @Test
    fun theSeedLoggingRulesArePinned() {
        assertEquals(60, strict.loggingIntervalSeconds)
        assertEquals(3, strict.gapToleranceFactor)
        assertEquals(5, strict.maxExcursionMinutesHeat)
    }

    // Heat

    @Test
    fun aShipmentStillHotAtHandoverCountsTheTimeUpToHandover() {
        val readings = minutely(5.0, 12.0, 12.0, 12.0, 12.0, 12.0, 12.0)
        val handover = t0 + 6 * minute + 179_000L

        val result = BreachDetector.evaluateTelemetry(readings, strict, windowEnd = handover)

        assertEquals(TemperatureVerdict.FAIL, result.verdict)
    }

    @Test
    fun aHandoverTimeBeforeTheLastReadingCreditsNothing() {
        val result = BreachDetector.evaluateTelemetry(minutely(5.0, 12.0), strict, windowEnd = t0)

        assertEquals(0L, result.cumulativeBreachDurationMs)
    }

    @Test
    fun aSafeTwinAtTheSameTimeCannotHideAHotReading() {
        val readings = (0..30).flatMap { index ->
            listOf(
                reading(index * minute, 12.0, id = "A-$index"),
                reading(index * minute, 5.0, id = "Z-$index")
            )
        }

        val result = BreachDetector.evaluateTelemetry(readings, strict)

        assertEquals(TemperatureVerdict.FAIL, result.verdict)
    }

    @Test
    fun aSafeTwinAtTheSameTimeCannotHideAFrozenReading() {
        val readings = listOf(reading(0, -3.0, id = "A"), reading(0, 5.0, id = "Z"))

        assertEquals(TemperatureVerdict.FAIL, BreachDetector.evaluateTelemetry(readings, strict).verdict)
    }

    @Test
    fun heatAndFreezeHaveSeparateBudgets() {
        val readings = minutely(9.0, 9.0, 9.0, 9.0, 5.0, -3.0, -3.0, -3.0, -3.0, 5.0)

        assertEquals(TemperatureVerdict.PASS, BreachDetector.evaluateTelemetry(readings, tolerant).verdict)
    }

    @Test
    fun thePeakIgnoresFrozenReadings() {
        val result = BreachDetector.evaluateTelemetry(minutely(5.0, -3.0, 5.0), strict)

        assertEquals(null, result.maxExcursionTemperature)
    }

    @Test
    fun heatWithinItsBudgetPasses() {
        val result = BreachDetector.evaluateTelemetry(minutely(5.0, 9.0, 9.0, 9.0, 5.0), strict)

        assertEquals(TemperatureVerdict.PASS, result.verdict)
        assertEquals(3 * minute, result.cumulativeBreachDurationMs)
    }

    @Test
    fun heatBeyondItsBudgetFails() {
        val result = BreachDetector.evaluateTelemetry(minutely(5.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 5.0), strict)

        assertEquals(TemperatureVerdict.FAIL, result.verdict)
        assertTrue(result.hasBreach)
        assertFalse(result.temperaturePassed)
    }

    // Freeze

    @Test
    fun aFinalReadingBelowTheMinimumFailsAZeroFreezeBudget() {
        val result = BreachDetector.evaluateTelemetry(minutely(5.0, -3.0), strict)

        assertEquals(TemperatureVerdict.FAIL, result.verdict)
        assertTrue(result.hasBreach)
    }

    @Test
    fun aSingleReadingBelowTheMinimumFailsAZeroFreezeBudget() {
        val result = BreachDetector.evaluateTelemetry(listOf(reading(0, 1.9)), strict)

        assertEquals(TemperatureVerdict.FAIL, result.verdict)
    }

    @Test
    fun freezeDurationIsCountedSeparatelyFromHeat() {
        val result = BreachDetector.evaluateTelemetry(minutely(5.0, -3.0, -4.0, 5.0, 9.0, 5.0), strict)

        assertEquals(2 * minute, result.cumulativeFreezeDurationMs)
        assertEquals(3 * minute, result.cumulativeBreachDurationMs)
        assertEquals(-4.0, result.minExcursionTemperature!!, 0.0)
        assertEquals(9.0, result.maxExcursionTemperature!!, 0.0)
    }

    @Test
    fun aFreezeTolerantProfileAllowsAFreezeWithinItsBudget() {
        val shortFreeze = minutely(5.0, -3.0, -3.0, 5.0)

        assertEquals(TemperatureVerdict.FAIL, BreachDetector.evaluateTelemetry(shortFreeze, strict).verdict)
        assertEquals(TemperatureVerdict.PASS, BreachDetector.evaluateTelemetry(shortFreeze, tolerant).verdict)
    }

    @Test
    fun aFreezeTolerantProfileStillFailsBeyondItsBudget() {
        val temperatures = DoubleArray(tolerant.maxExcursionMinutesFreeze + 2) { -3.0 }

        assertEquals(TemperatureVerdict.FAIL, BreachDetector.evaluateTelemetry(minutely(*temperatures), tolerant).verdict)
    }

    // Gaps

    @Test
    fun noReadingsAtAllIsUnknownNotAPass() {
        val result = BreachDetector.evaluateTelemetry(emptyList(), strict)

        assertEquals(TemperatureVerdict.UNKNOWN, result.verdict)
        assertFalse(result.temperaturePassed)
        assertFalse(result.hasBreach)
    }

    @Test
    fun aGapOfExactlyTheToleranceIsNotAGap() {
        val tolerance = strict.loggingIntervalSeconds * strict.gapToleranceFactor * 1_000L
        val result = BreachDetector.evaluateTelemetry(listOf(reading(0, 5.0), reading(tolerance, 5.0)), strict)

        assertEquals(TemperatureVerdict.PASS, result.verdict)
        assertTrue(result.dataGaps.isEmpty())
    }

    @Test
    fun aGapBeyondTheToleranceIsUnknown() {
        val tolerance = strict.loggingIntervalSeconds * strict.gapToleranceFactor * 1_000L
        val result = BreachDetector.evaluateTelemetry(listOf(reading(0, 5.0), reading(tolerance + 1, 5.0)), strict)

        assertEquals(TemperatureVerdict.UNKNOWN, result.verdict)
        assertEquals(listOf(DataGap(t0, t0 + tolerance + 1)), result.dataGaps)
    }

    @Test
    fun aLoggerGoingQuietBeforeHandoverIsUnknown() {
        val readings = minutely(5.0, 5.0, 5.0)

        val atTheTime = BreachDetector.evaluateTelemetry(readings, strict, windowEnd = t0 + 3 * minute)
        val hoursLater = BreachDetector.evaluateTelemetry(readings, strict, windowEnd = t0 + 6 * hour)

        assertEquals(TemperatureVerdict.PASS, atTheTime.verdict)
        assertEquals(TemperatureVerdict.UNKNOWN, hoursLater.verdict)
        assertEquals(t0 + 2 * minute, hoursLater.dataGaps.single().startTimestamp)
    }

    @Test
    fun aLateFirstReadingAfterDispatchIsUnknown() {
        val readings = minutely(5.0, 5.0)

        val result = BreachDetector.evaluateTelemetry(readings, strict, windowStart = t0 - 2 * hour)

        assertEquals(TemperatureVerdict.UNKNOWN, result.verdict)
        assertEquals(t0 - 2 * hour, result.dataGaps.single().startTimestamp)
    }

    @Test
    fun aWindowWithNoReadingsIsOneGap() {
        val result = BreachDetector.evaluateTelemetry(emptyList(), strict, t0, t0 + 2 * hour)

        assertEquals(TemperatureVerdict.UNKNOWN, result.verdict)
        assertEquals(listOf(DataGap(t0, t0 + 2 * hour)), result.dataGaps)
    }

    @Test
    fun aConfirmedBreachOutranksAGap() {
        val readings = minutely(5.0, -3.0, 5.0) + reading(6 * hour, 5.0)

        assertEquals(TemperatureVerdict.FAIL, BreachDetector.evaluateTelemetry(readings, strict).verdict)
    }

    @Test
    fun aReadingBesideAGapCountsOnlyUpToTheSilenceLimit() {
        val tolerance = strict.loggingIntervalSeconds * strict.gapToleranceFactor * 1_000L
        val readings = listOf(reading(0, 9.0), reading(6 * hour, 5.0))

        val result = BreachDetector.evaluateTelemetry(readings, strict)

        assertEquals(tolerance, result.cumulativeBreachDurationMs)
        assertEquals(TemperatureVerdict.UNKNOWN, result.verdict)
    }

    @Test
    fun aGapSplitsAnExcursionInTwo() {
        val readings = listOf(reading(0, 9.0), reading(minute, 9.0), reading(6 * hour, 9.0), reading(6 * hour + minute, 5.0))

        val result = BreachDetector.evaluateTelemetry(readings, strict)

        assertEquals(listOf(t0, t0 + 6 * hour), result.excursions.map { it.startTimestamp })
    }

    @Test
    fun readingsDatedAfterTheHandoverCannotShowTheLoggerWasRunning() {
        val readings = steadyReadings(t0, 60 * minute, 5.0)
        val handover = t0 + 3 * minute

        val result = BreachDetector.evaluateTelemetry(readings, strict, windowEnd = handover)

        assertEquals(TemperatureVerdict.UNKNOWN, result.verdict)
    }

    @Test
    fun aReadingDatedExactlyTheToleranceAfterHandoverIsAllowed() {
        val readings = minutely(5.0, 5.0, 5.0)
        val lastReading = readings.maxOf { it.timestamp }

        val atLimit = BreachDetector.evaluateTelemetry(
            readings, strict, windowEnd = lastReading - ClockSkewDetector.TOLERANCE_MS
        )
        val pastLimit = BreachDetector.evaluateTelemetry(
            readings, strict, windowEnd = lastReading - ClockSkewDetector.TOLERANCE_MS - 1
        )

        assertEquals(TemperatureVerdict.PASS, atLimit.verdict)
        assertEquals(TemperatureVerdict.UNKNOWN, pastLimit.verdict)
    }

    @Test
    fun aSmallClockDifferenceAtHandoverIsAllowed() {
        val readings = minutely(5.0, 5.0, 5.0)

        val result = BreachDetector.evaluateTelemetry(readings, strict, windowEnd = t0 + minute)

        assertEquals(TemperatureVerdict.PASS, result.verdict)
    }

    @Test
    fun anUnreadableTemperatureIsNotAPass() {
        val readings = minutely(5.0, Double.NaN, 5.0)

        assertEquals(TemperatureVerdict.UNKNOWN, BreachDetector.evaluateTelemetry(readings, strict).verdict)
    }

    // Excursions

    @Test
    fun excursionsRecordTheirDirectionStartAndExtreme() {
        val result = BreachDetector.evaluateTelemetry(minutely(5.0, 9.0, 11.0, 5.0, -2.0, 1.0, 5.0), strict)

        val (heat, freeze) = result.excursions
        assertEquals(ExcursionType.HEAT, heat.type)
        assertEquals(t0 + minute, heat.startTimestamp)
        assertEquals(11.0, heat.extremeTemperature, 0.0)
        assertEquals(ExcursionType.FREEZE, freeze.type)
        assertEquals(t0 + 4 * minute, freeze.startTimestamp)
        assertEquals(-2.0, freeze.extremeTemperature, 0.0)
    }

    @Test
    fun onlyTheExcursionThatCrossesTheBudgetIsMarkedBreached() {
        val readings = minutely(9.0, 9.0, 9.0, 9.0, 5.0, 9.0, 9.0, 9.0, 5.0)

        val result = BreachDetector.evaluateTelemetry(readings, strict)

        assertEquals(listOf(false, true), result.excursions.map { it.breached })
    }

    @Test
    fun aHotReadingDirectlyFollowedByAFrozenOneMakesTwoExcursions() {
        val result = BreachDetector.evaluateTelemetry(minutely(9.0, -3.0, 5.0), strict)

        assertEquals(listOf(ExcursionType.HEAT, ExcursionType.FREEZE), result.excursions.map { it.type })
    }
}
