package com.coldchain.handshake.safety

import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent

private const val MINUTE_MS = 60_000L

private fun reading(shipmentId: String, index: Int, timestamp: Long, temperature: Double) = TemperatureEvent(
    id = "e${index + 1}",
    shipmentId = shipmentId,
    loggerId = "LOG-FIXTURE",
    timestamp = timestamp,
    temperature = temperature,
    previousHash = "prev-hash",
    currentHash = "curr-hash",
    syncStatus = SyncStatus.SYNCED
)

/**
 * One reading a minute at [temperature] for [hotMs], then one in-range reading that ends the
 * excursion exactly [hotMs] after [start]. Minutely data keeps the record free of gaps, so the
 * only thing a test sees is the excursion.
 */
internal fun hotThenSafe(
    start: Long,
    hotMs: Long,
    temperature: Double,
    shipmentId: String = "SHIP-FIXTURE"
): List<TemperatureEvent> {
    val hot = (0L until hotMs step MINUTE_MS).mapIndexed { index, offset ->
        reading(shipmentId, index, start + offset, temperature)
    }
    return hot + reading(shipmentId, hot.size, start + hotMs, 5.0)
}

/**
 * One reading a minute at [temperature] for [durationMs].
 */
internal fun steadyReadings(
    start: Long,
    durationMs: Long,
    temperature: Double,
    shipmentId: String = "SHIP-FIXTURE"
): List<TemperatureEvent> =
    (0L..durationMs step MINUTE_MS).mapIndexed { index, offset ->
        reading(shipmentId, index, start + offset, temperature)
    }
