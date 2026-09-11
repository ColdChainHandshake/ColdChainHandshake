package com.coldchain.handshake.models

/**
 * Official TemperatureEvent model representing a discrete thermal logging event with hash-chain fields.
 */
data class TemperatureEvent(
    val id: String,
    val shipmentId: String,
    val loggerId: String,
    val timestamp: Long,
    val temperature: Double,
    val previousHash: String,
    val currentHash: String,
    val syncStatus: SyncStatus
)
