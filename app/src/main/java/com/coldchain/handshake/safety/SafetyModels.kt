package com.coldchain.handshake.safety

/**
 * Outcome of checking a temperature history against a product's rules.
 * [FAIL] outranks [UNKNOWN]: a confirmed breach stays a breach even if the record also has a gap.
 */
enum class TemperatureVerdict {
    PASS,
    FAIL,
    UNKNOWN
}

enum class ExcursionType {
    HEAT,
    FREEZE
}

/**
 * One continuous run of readings outside the safe range.
 *
 * @property durationMs Time the readings prove, which is 0 for a run that ends on its first reading.
 * @property extremeTemperature Hottest reading of a heat excursion, coldest of a freeze excursion.
 * @property breached True once this excursion takes the shipment's total for its type past the budget.
 */
data class Excursion(
    val type: ExcursionType,
    val startTimestamp: Long,
    val durationMs: Long,
    val extremeTemperature: Double,
    val breached: Boolean = false
)

/**
 * A stretch of the journey with no readings, longer than the product's tolerance.
 */
data class DataGap(
    val startTimestamp: Long,
    val endTimestamp: Long
)

/**
 * Result of evaluating a collection of TemperatureEvents against a product's rules.
 *
 * @property hasBreach True if heat or freeze exceeded its budget.
 * @property cumulativeBreachDurationMs Total proven time out of range, heat and freeze together.
 * @property maxExcursionTemperature Hottest reading above the range, or null if none.
 * @property excursionIntervalCount Number of excursions with proven duration.
 * @property verdict The overall outcome; only [TemperatureVerdict.PASS] counts as passed.
 * @property cumulativeFreezeDurationMs The part of [cumulativeBreachDurationMs] below the range.
 * @property minExcursionTemperature Coldest reading below the range, or null if none.
 */
data class BreachEvaluation(
    val hasBreach: Boolean,
    val cumulativeBreachDurationMs: Long,
    val maxExcursionTemperature: Double? = null,
    val excursionIntervalCount: Int = 0,
    val verdict: TemperatureVerdict = if (hasBreach) TemperatureVerdict.FAIL else TemperatureVerdict.PASS,
    val cumulativeFreezeDurationMs: Long = 0L,
    val minExcursionTemperature: Double? = null,
    val excursions: List<Excursion> = emptyList(),
    val dataGaps: List<DataGap> = emptyList()
) {
    /**
     * Consumed directly by Handover records. Unknown data is not a pass.
     */
    val temperaturePassed: Boolean
        get() = verdict == TemperatureVerdict.PASS
}

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
