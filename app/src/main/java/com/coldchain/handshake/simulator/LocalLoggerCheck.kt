package com.coldchain.handshake.simulator

import com.coldchain.handshake.logger.LoggerCheckResult
import com.coldchain.handshake.logger.LoggerHandoverCheck
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.flow.first

/**
 * Debug local mode's check at handover (4.1, MASTER 5.4 item 2): the shipment's virtual logger on this phone, read as
 * a receiver reads a logger, against the key [LocalLoggers] took when it started. No logger here (after a restart) is
 * NOT_CHECKED.
 *
 * It runs between readings: a reading is saved before its logger records it, so a check in between would see a reading
 * the logger never made and fail an honest trip.
 */
class LocalLoggerCheck(
    private val simulator: TemperatureSimulator,
    private val loggers: LocalLoggers,
    private val telemetry: TelemetryRepository,
    private val loggerCheck: LoggerHandoverCheck = LoggerHandoverCheck()
) {
    suspend fun check(shipment: Shipment): LoggerCheckResult = simulator.betweenReadings {
        val readings = telemetry.getTemperatures(shipment.id).first().sortedBy { it.seq }
        loggerCheck.check(loggers.of(shipment.id), shipment.loggerId, loggers.enrolledKey(shipment.id), 0L, readings)
    }
}
