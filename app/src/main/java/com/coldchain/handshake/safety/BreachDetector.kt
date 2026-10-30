package com.coldchain.handshake.safety

import com.coldchain.handshake.models.ProductProfile
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.TemperatureEvent

/**
 * Pure Kotlin evaluation engine for cold-chain temperature telemetry.
 *
 * Rules, all taken from the [ProductProfile]:
 * - Readings exactly on a limit are safe.
 * - Heat and freeze have separate cumulative budgets, and separated excursions accumulate.
 *   A budget of 0 means a single reading past that limit fails, because a final reading proves
 *   no duration at all.
 * - Silence longer than [ProductProfile.maxSilenceMs] is a gap. It makes the verdict
 *   [TemperatureVerdict.UNKNOWN], and is counted neither as safe nor as an excursion.
 * - A confirmed breach outranks a gap, and no readings at all is never a pass.
 * - Never extrapolates beyond the final recorded event timestamp.
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
        // Sort telemetry deterministically: timestamp ascending, with ID tie-breaker
        val sortedEvents = events.sortedWith(
            compareBy<TemperatureEvent>({ it.timestamp }, { it.id })
        )

        val excursions = markBreaches(findExcursions(sortedEvents, profile), profile)
        val dataGaps = findDataGaps(sortedEvents, profile, windowStart, windowEnd)
        val hasBreach = excursions.any { it.breached }

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
                else -> TemperatureVerdict.PASS
            },
            cumulativeFreezeDurationMs = freeze.sumOf { it.durationMs },
            minExcursionTemperature = freeze.minOfOrNull { it.extremeTemperature },
            excursions = excursions,
            dataGaps = dataGaps
        )
    }

    private fun classify(temperature: Double, profile: ProductProfile): ExcursionType? = when {
        temperature > profile.maxC -> ExcursionType.HEAT
        temperature < profile.minC -> ExcursionType.FREEZE
        else -> null
    }

    /**
     * Each reading speaks for the time until the next one, but never for more than the product's
     * silence tolerance, so a gap can't be counted as time spent out of range.
     */
    private fun findExcursions(sortedEvents: List<TemperatureEvent>, profile: ProductProfile): List<Excursion> {
        val excursions = mutableListOf<Excursion>()
        var current: Excursion? = null

        sortedEvents.forEachIndexed { index, event ->
            val type = classify(event.temperature, profile)
            if (current != null && current?.type != type) {
                current?.let { excursions.add(it) }
                current = null
            }

            val elapsed = sortedEvents.getOrNull(index + 1)?.let { it.timestamp - event.timestamp } ?: 0L
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
