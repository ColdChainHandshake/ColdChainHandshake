package com.coldchain.handshake.models

/**
 * Official alert categories for the cold-chain monitoring system.
 */
enum class AlertType {
    TEMPERATURE_BREACH,
    LOGGER_DISCONNECT,
    OPERATIONAL_WARNING
}

/**
 * Official alert escalation levels.
 */
enum class EscalationLevel {
    WORKER,
    SUPERVISOR,
    PHARMACIST
}

/**
 * Official Alert model.
 */
data class Alert(
    val id: String,
    val shipmentId: String,
    val type: AlertType,
    val message: String,
    val timestamp: Long,
    val escalationLevel: EscalationLevel,
    val acknowledged: Boolean
)
