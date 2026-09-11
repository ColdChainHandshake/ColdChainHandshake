package com.coldchain.handshake.safety

import com.coldchain.handshake.models.TemperatureEvent

/**
 * Pure Kotlin evaluation engine for cold-chain temperature telemetry.
 *
 * Enforces the canonical business rule:
 * - Safe range: 2°C through 8°C inclusive.
 * - Only temperature > 8°C is considered an excursion (8.0°C exactly is SAFE).
 * - Breach threshold: cumulative proven duration strictly > 5 minutes (300,000 ms).
 *   (5:00 exactly = NOT A BREACH; 5:01 / 300,001 ms or greater = BREACH).
 * - Irregular timestamps supported; multiple separated intervals accumulate.
 * - Never extrapolates beyond the final recorded event timestamp.
 */
object BreachDetector {

    /**
     * Evaluates a sequence of [TemperatureEvent]s against thermal safety thresholds.
     *
     * @param events List of temperature telemetry events, which may be unordered or irregularly spaced.
     * @return [BreachEvaluation] containing cumulative breach duration, breach state, and safety verdict.
     */
    fun evaluateTelemetry(events: List<TemperatureEvent>): BreachEvaluation {
        if (events.isEmpty()) {
            return BreachEvaluation(
                hasBreach = false,
                cumulativeBreachDurationMs = 0L,
                temperaturePassed = true,
                maxExcursionTemperature = null,
                excursionIntervalCount = 0
            )
        }

        // 1. Sort telemetry deterministically: timestamp ascending, with ID tie-breaker
        val sortedEvents = events.sortedWith(
            compareBy<TemperatureEvent>({ it.timestamp }, { it.id })
        )

        var cumulativeBreachMs = 0L
        var maxExcursionTemp: Double? = null
        var intervalCount = 0
        var insideExcursion = false

        // Record excursion peak even if a single event exists
        for (event in sortedEvents) {
            if (event.temperature > SafetyConstants.MAX_SAFE_TEMPERATURE) {
                maxExcursionTemp = maxOf(maxExcursionTemp ?: event.temperature, event.temperature)
            }
        }

        // 2. Iterate consecutive event pairs (earlier, next)
        for (i in 0 until sortedEvents.size - 1) {
            val earlier = sortedEvents[i]
            val next = sortedEvents[i + 1]

            if (earlier.temperature > SafetyConstants.MAX_SAFE_TEMPERATURE) {
                val elapsed = next.timestamp - earlier.timestamp
                if (elapsed > 0L) {
                    cumulativeBreachMs += elapsed
                    if (!insideExcursion) {
                        intervalCount++
                        insideExcursion = true
                    }
                }
            } else {
                // Return to safe range (or safe start) marks end of the previous excursion interval
                insideExcursion = false
            }
        }

        // 3. Final-event non-extrapolation:
        // No elapsed time is added beyond sortedEvents.last().timestamp.

        // 4. Threshold rule: cumulative proven duration strictly > 5 minutes (300,000 ms)
        val hasBreach = cumulativeBreachMs > SafetyConstants.BREACH_DURATION_THRESHOLD_MS
        val temperaturePassed = !hasBreach

        return BreachEvaluation(
            hasBreach = hasBreach,
            cumulativeBreachDurationMs = cumulativeBreachMs,
            temperaturePassed = temperaturePassed,
            maxExcursionTemperature = maxExcursionTemp,
            excursionIntervalCount = intervalCount
        )
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
