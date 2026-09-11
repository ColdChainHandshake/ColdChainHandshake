package com.coldchain.handshake.safety

/**
 * Constants governing cold-chain thermal safety rules.
 */
object SafetyConstants {
    /**
     * Upper boundary of safe refrigeration (8.0°C inclusive).
     * Only temperatures strictly greater than 8.0°C represent an excursion.
     */
    const val MAX_SAFE_TEMPERATURE = 8.0

    /**
     * Lower boundary of safe refrigeration (2.0°C inclusive).
     */
    const val MIN_SAFE_TEMPERATURE = 2.0

    /**
     * Cumulative breach duration threshold: strictly greater than 5 minutes (300,000 ms).
     * Exactly 5:00 (300,000 ms) is safe; 5:00.001+ is a breach.
     */
    const val BREACH_DURATION_THRESHOLD_MS = 5 * 60 * 1000L // 300,000 ms
}

/**
 * Result of evaluating a collection of TemperatureEvents against safety thresholds.
 *
 * @property hasBreach True if cumulative excursion duration strictly exceeds 5 minutes.
 * @property cumulativeBreachDurationMs Total proven elapsed duration where temperature was > 8.0°C.
 * @property temperaturePassed Inversion of hasBreach; consumed directly by Handover records.
 * @property maxExcursionTemperature Highest recorded temperature during excursions, or null if none.
 * @property excursionIntervalCount Number of discrete excursion intervals recorded.
 */
data class BreachEvaluation(
    val hasBreach: Boolean,
    val cumulativeBreachDurationMs: Long,
    val temperaturePassed: Boolean,
    val maxExcursionTemperature: Double? = null,
    val excursionIntervalCount: Int = 0
)

/**
 * Overall safety decision for a shipment at a point in time, surfacing evaluation results
 * and any repository persistence outcomes so failures are never silently swallowed.
 */
data class SafetyDecision(
    val shipmentId: String,
    val evaluation: BreachEvaluation,
    val shouldQuarantine: Boolean,
    val breachDurationFormatted: String,
    val alertSaveResult: Result<Unit>? = null,
    val quarantineSaveResult: Result<Unit>? = null
) {
    /**
     * Indicates whether any repository persistence operation encountered a failure.
     */
    val hasPersistenceFailure: Boolean
        get() = alertSaveResult?.isFailure == true || quarantineSaveResult?.isFailure == true
}
