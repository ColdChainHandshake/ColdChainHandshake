package com.coldchain.handshake.logger

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.flow.first

/**
 * The logger refused a check, so nothing it sent this time was stored.
 */
class LoggerHistoryRejected(val verification: LoggerVerification) :
    Exception("The logger's history failed its checks: ${verification.issues.joinToString { it.type.name }}")

/**
 * Brings a logger's new records onto the phone as the shipment's next readings, verified first. After
 * an outage it simply asks again from where it got to.
 *
 * @param publicKey The key on record for this logger, not one the logger supplies.
 * @param startSeq The logger's newest record when custody started, so reading `n` is record `startSeq + n`.
 */
class LoggerIngestor(
    private val link: LoggerLink,
    private val telemetry: TelemetryRepository,
    private val loggerId: String,
    private val publicKey: ByteArray,
    private val startSeq: Long = 0,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS
) {

    /**
     * @return how many readings were stored.
     */
    suspend fun ingest(shipment: Shipment): Result<Int> {
        val held = telemetry.getTemperatures(shipment.id).first().sortedBy { it.seq }
        val last = held.lastOrNull()
        val fromSeq = startSeq + (last?.seq ?: 0) + 1

        val batch = link.readHistory(fromSeq, maxRecords).getOrElse { return Result.failure(it) }
            ?: return Result.success(0)

        val verification = LoggerHistoryVerifier.verify(loggerId, publicKey, listOf(batch), fromSeq = fromSeq)
        if (!verification.trusted) return Result.failure(LoggerHistoryRejected(verification))

        var previous = last?.currentHash ?: shipment.genesisHash ?: HashChainService.UNBOUND_GENESIS
        val readings = verification.records.map { record ->
            HashChainService.appendHash(
                TemperatureEvent(
                    id = "${shipment.id}:L${record.seq}",
                    shipmentId = shipment.id,
                    loggerId = loggerId,
                    timestamp = record.loggerTs,
                    temperature = record.tempCentiC / 100.0,
                    previousHash = previous,
                    currentHash = "",
                    syncStatus = SyncStatus.PENDING,
                    seq = record.seq - startSeq
                ),
                previous
            ).also { previous = it.currentHash }
        }

        return telemetry.saveTemperatures(readings).map { readings.size }
    }

    private companion object {
        const val DEFAULT_MAX_RECORDS = 500
    }
}
