package com.coldchain.handshake.safety

import com.coldchain.handshake.crypto.ClockSkewDetector
import com.coldchain.handshake.models.ProductProfile
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.TemperatureEvent

/**
 * Pure Kotlin evaluation engine for cold-chain temperature telemetry.
 *
 * Rules, all taken from the [ProductProfile]:
 * - Readings exactly on a limit are safe.
 * - Heat and freeze have separate cumulative budgets, and separated excursions accumulate.
 *   A budget of 0 means a single reading past that limit fails, because a final reading proves
 *   no duration at all.
 * - Each reading speaks for the time until the next one, or until the end of the window for the last
 *   one, and never for more than [ProductProfile.maxSilenceMs].
 * - Silence longer than that is a gap, which makes the verdict [TemperatureVerdict.UNKNOWN]. A reading
 *   next to a gap is still credited up to the limit, so a hot reading before a gap counts as hot.
 * - Readings with the same time count as the worst of them in each direction, so a safe or opposite
 *   twin can't hide a bad one.
 * - A reading dated well after the window ends is evidence of a wrong clock, not of a running logger.
 * - A confirmed breach outranks a gap, and no readings at all is never a pass.
 * - Without a window, nothing is extrapolated beyond the final recorded event timestamp.
 */
object BreachDetector {

    /**
     * Evaluates a sequence of [TemperatureEvent]s against a product's rules.
     *
     * @param events Telemetry events, which may be unordered or irregularly spaced.
     * @param profile The rules to apply. Defaults to the strictest profile.
     * @param windowStart When monitoring should have begun, so a late first reading is seen as a gap.
     * @param windowEnd When monitoring should still have been running, such as the moment of
     *   handover, so a logger that went quiet early is seen as a gap.
     * @return [BreachEvaluation] with the verdict and the excursions and gaps behind it.
     */
    fun evaluateTelemetry(
        events: List<TemperatureEvent>,
        profile: ProductProfile = ProductProfiles.DEFAULT,
        windowStart: Long? = null,
        windowEnd: Long? = null
    ): BreachEvaluation {
        val readable = events.filter { it.temperature.isFinite() }

        // Heat and freeze are read as separate series, each with one reading per timestamp, so a twin
        // at the same time can hide neither and every reading keeps its full time.
        val heatSeries = seriesFor(readable, profile, ExcursionType.HEAT)
        val freezeSeries = seriesFor(readable, profile, ExcursionType.FREEZE)
        val sortedEvents = heatSeries

        val excursions = markBreaches(
            (findExcursions(heatSeries, profile, windowEnd).filter { it.type == ExcursionType.HEAT } +
                findExcursions(freezeSeries, profile, windowEnd).filter { it.type == ExcursionType.FREEZE })
                .sortedBy { it.startTimestamp },
            profile
        )
        val dataGaps = findDataGaps(sortedEvents, profile, windowStart, windowEnd)
        val hasBreach = excursions.any { it.breached }
        val datedAfterWindow = windowEnd != null &&
            sortedEvents.any { it.timestamp > windowEnd + ClockSkewDetector.TOLERANCE_MS }

        val heat = excursions.filter { it.type == ExcursionType.HEAT }
        val freeze = excursions.filter { it.type == ExcursionType.FREEZE }

        return BreachEvaluation(
            hasBreach = hasBreach,
            cumulativeBreachDurationMs = excursions.sumOf { it.durationMs },
            maxExcursionTemperature = heat.maxOfOrNull { it.extremeTemperature },
            excursionIntervalCount = excursions.count { it.durationMs > 0L },
            verdict = when {
                hasBreach -> TemperatureVerdict.FAIL
                sortedEvents.isEmpty() || dataGaps.isNotEmpty() -> TemperatureVerdict.UNKNOWN
                datedAfterWindow || readable.size < events.size -> TemperatureVerdict.UNKNOWN
                else -> TemperatureVerdict.PASS
            },
            cumulativeFreezeDurationMs = freeze.sumOf { it.durationMs },
            minExcursionTemperature = freeze.minOfOrNull { it.extremeTemperature },
            excursions = excursions,
            dataGaps = dataGaps
        )
    }

    /**
     * Evaluates a shipment for handover: its own rules, from the start of its journey up to now. If
     * nobody knows when the journey started, nobody can say the readings cover it.
     */
    fun evaluateForHandover(
        shipment: Shipment,
        events: List<TemperatureEvent>,
        handoverAt: Long
    ): BreachEvaluation {
        val evaluation = evaluateTelemetry(
            events = events,
            profile = ProductProfiles.byId(shipment.productProfileId),
            windowStart = shipment.journeyStartedAt,
            windowEnd = handoverAt
        )
        // A start after the handover leaves no window to check, so it can't prove anything either.
        val windowUnknown = shipment.journeyStartedAt.let { it == null || it > handoverAt }
        return if (windowUnknown && evaluation.verdict == TemperatureVerdict.PASS) {
            evaluation.copy(verdict = TemperatureVerdict.UNKNOWN)
        } else {
            evaluation
        }
    }

    /**
     * One reading per timestamp, sorted. Where readings share a time, the worst one in [direction]
     * stands for it, or any other if none is out of range that way.
     */
    private fun seriesFor(
        events: List<TemperatureEvent>,
        profile: ProductProfile,
        direction: ExcursionType
    ): List<TemperatureEvent> {
        val worst = compareBy<TemperatureEvent>({ distanceOutOfRange(it.temperature, profile) }, { it.id })
        return events.groupBy { it.timestamp }.values
            .map { sameTime ->
                sameTime.filter { classify(it.temperature, profile) == direction }.maxWithOrNull(worst)
                    ?: sameTime.minWith(worst)
            }
            .sortedWith(compareBy({ it.timestamp }, { it.id }))
    }

    private fun distanceOutOfRange(temperature: Double, profile: ProductProfile): Double =
        maxOf(temperature - profile.maxC, profile.minC - temperature, 0.0)

    private fun classify(temperature: Double, profile: ProductProfile): ExcursionType? = when {
        temperature > profile.maxC -> ExcursionType.HEAT
        temperature < profile.minC -> ExcursionType.FREEZE
        else -> null
    }

    /**
     * Each reading speaks for the time until the next one, but never for more than the product's
     * silence tolerance. With a window, the last reading speaks for the time up to its end.
     */
    private fun findExcursions(
        sortedEvents: List<TemperatureEvent>,
        profile: ProductProfile,
        windowEnd: Long?
    ): List<Excursion> {
        val excursions = mutableListOf<Excursion>()
        var current: Excursion? = null

        sortedEvents.forEachIndexed { index, event ->
            val type = classify(event.temperature, profile)
            if (current != null && current?.type != type) {
                current?.let { excursions.add(it) }
                current = null
            }

            val nextTimestamp = sortedEvents.getOrNull(index + 1)?.timestamp ?: windowEnd
            val elapsed = nextTimestamp?.let { (it - event.timestamp).coerceAtLeast(0L) } ?: 0L
            val credited = elapsed.coerceAtMost(profile.maxSilenceMs)

            if (type != null) {
                val running = current ?: Excursion(type, event.timestamp, 0L, event.temperature)
                val extreme = when (type) {
                    ExcursionType.HEAT -> maxOf(running.extremeTemperature, event.temperature)
                    ExcursionType.FREEZE -> minOf(running.extremeTemperature, event.temperature)
                }
                current = running.copy(durationMs = running.durationMs + credited, extremeTemperature = extreme)
            }

            if (elapsed > profile.maxSilenceMs) {
                current?.let { excursions.add(it) }
                current = null
            }
        }
        current?.let { excursions.add(it) }

        return excursions
    }

    private fun markBreaches(excursions: List<Excursion>, profile: ProductProfile): List<Excursion> {
        val runningTotals = mutableMapOf<ExcursionType, Long>()

        return excursions.map { excursion ->
            val total = (runningTotals[excursion.type] ?: 0L) + excursion.durationMs
            runningTotals[excursion.type] = total

            val budgetMinutes = when (excursion.type) {
                ExcursionType.HEAT -> profile.maxExcursionMinutesHeat
                ExcursionType.FREEZE -> profile.maxExcursionMinutesFreeze
            }
            excursion.copy(breached = budgetMinutes == 0 || total > budgetMinutes * 60_000L)
        }
    }

    private fun findDataGaps(
        sortedEvents: List<TemperatureEvent>,
        profile: ProductProfile,
        windowStart: Long?,
        windowEnd: Long?
    ): List<DataGap> {
        val checkpoints = buildList {
            windowStart?.let { add(it) }
            addAll(sortedEvents.map { it.timestamp })
            windowEnd?.let { add(it) }
        }
        return checkpoints.zipWithNext()
            .filter { (earlier, later) -> later - earlier > profile.maxSilenceMs }
            .map { (earlier, later) -> DataGap(earlier, later) }
    }

    /**
     * Formats milliseconds into a readable mm:ss string.
     */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
