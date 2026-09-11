package com.coldchain.handshake.models

/**
 * Supported fault injection scenarios for the resilience test lab.
 */
enum class ChaosScenarioType {
    HEAT_SPIKE,
    LOGGER_DISCONNECT,
    NETWORK_FAILURE,
    CORRUPT_EVENT
}

/**
 * Record of a chaos simulation event.
 */
data class ChaosEvent(
    val id: String,
    val scenarioType: ChaosScenarioType,
    val isActive: Boolean,
    val triggeredAtTimestamp: Long = System.currentTimeMillis(),
    val description: String
)
