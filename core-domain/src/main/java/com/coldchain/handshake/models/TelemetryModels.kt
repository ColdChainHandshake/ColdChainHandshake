package com.coldchain.handshake.models

import kotlin.math.roundToInt

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
    val syncStatus: SyncStatus,
    val seq: Long = 0L
) {
    /**
     * The value the chain commits to. Every temperature comes down to this one conversion, so a
     * reading is never rounded differently in two places.
     */
    val tempCentiC: Int
        get() = (temperature * 100).roundToInt()
}
