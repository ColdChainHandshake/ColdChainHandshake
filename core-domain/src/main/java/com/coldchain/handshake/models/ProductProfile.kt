package com.coldchain.handshake.models

/**
 * Temperature rules for one kind of product.
 *
 * @property minC Lowest safe temperature, inclusive.
 * @property maxC Highest safe temperature, inclusive.
 * @property maxExcursionMinutesHeat Cumulative minutes allowed above [maxC]. 0 means any reading above it fails.
 * @property maxExcursionMinutesFreeze Cumulative minutes allowed below [minC]. 0 means any reading below it fails.
 * @property loggingIntervalSeconds How often the logger is expected to record.
 * @property gapToleranceFactor How many intervals may pass in silence before the record has a gap.
 */
data class ProductProfile(
    val id: String,
    val name: String,
    val minC: Double,
    val maxC: Double,
    val maxExcursionMinutesHeat: Int,
    val maxExcursionMinutesFreeze: Int,
    val loggingIntervalSeconds: Int,
    val gapToleranceFactor: Int
) {
    val maxSilenceMs: Long
        get() = loggingIntervalSeconds * gapToleranceFactor * 1_000L
}

object ProductProfiles {

    val FREEZE_SENSITIVE_VACCINE = ProductProfile(
        id = "vaccine-2-8-freeze-sensitive",
        name = "2–8 °C vaccine (freeze-sensitive)",
        minC = 2.0,
        maxC = 8.0,
        maxExcursionMinutesHeat = 5,
        maxExcursionMinutesFreeze = 0,
        loggingIntervalSeconds = 60,
        gapToleranceFactor = 3
    )

    /** A second set of rules, so per-product behaviour can be exercised. Not based on any real product. */
    val FREEZE_TOLERANT = ProductProfile(
        id = "test-2-8-freeze-tolerant",
        name = "2–8 °C test profile (freeze-tolerant)",
        minC = 2.0,
        maxC = 8.0,
        maxExcursionMinutesHeat = 5,
        maxExcursionMinutesFreeze = 60,
        loggingIntervalSeconds = 60,
        gapToleranceFactor = 3
    )

    val DEFAULT = FREEZE_SENSITIVE_VACCINE

    val ALL = listOf(FREEZE_SENSITIVE_VACCINE, FREEZE_TOLERANT)

    /** An ID this build doesn't know gets the strictest rules, never looser ones. */
    fun byId(id: String): ProductProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
