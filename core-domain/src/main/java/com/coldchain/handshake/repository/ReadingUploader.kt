package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.crypto.SignedBatch
import com.coldchain.handshake.models.TemperatureEvent

data class UploadResult(
    val uploadedIds: List<String>,
    val head: ServerChainHead
)

/**
 * This device's history and the server's disagree at [seq]. Retrying can't fix that, so the readings
 * stay unsent and the difference stays visible.
 */
class HistoryConflict(val seq: Long, message: String) : Exception(message)

/**
 * Sends a shipment's unsent readings to the server, in order and signed by this device.
 *
 * Safe to run again at any time: it starts from what the server says it holds, so a reply lost in a
 * dropped connection doesn't send anything twice.
 */
class ReadingUploader(
    private val remote: ColdChainRemote,
    private val signer: DeviceSigner,
    private val deviceId: () -> String,
    private val maxBatchSize: Int = DEFAULT_BATCH_SIZE
) {

    suspend fun upload(shipmentId: String, pending: List<TemperatureEvent>): Result<UploadResult> {
        val sorted = pending.sortedBy { it.seq }
        sorted.firstOrNull { it.seq < 1 }?.let {
            return Result.failure(IllegalArgumentException("Reading ${it.id} has no valid sequence number."))
        }

        var head = remote.getChainHead(shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("The server has no record of shipment $shipmentId."))

        val (alreadyThere, unsent) = sorted.partition { it.seq <= head.count }

        firstDifference(shipmentId, alreadyThere).getOrElse { return Result.failure(it) }?.let { seq ->
            return Result.failure(HistoryConflict(seq, "Reading $seq differs from the one the server holds."))
        }

        val uploaded = alreadyThere.map { it.id }.toMutableList()

        for (batch in unsent.chunked(maxBatchSize)) {
            val first = batch.first()
            if (first.seq != head.count + 1 || first.previousHash != head.headHash) {
                return Result.failure(
                    HistoryConflict(first.seq, "Reading ${first.seq} doesn't follow the server's newest reading.")
                )
            }

            val claim = SignedBatch(
                shipmentId = shipmentId,
                firstSeq = first.seq,
                lastSeq = batch.last().seq,
                headHash = batch.last().currentHash
            )
            head = remote.appendReadings(
                shipmentId = shipmentId,
                readings = batch,
                expectedPrevHead = head.headHash,
                deviceId = deviceId(),
                signature = signer.sign(claim.payload())
            ).getOrElse { return Result.failure(it) }

            uploaded += batch.map { it.id }
        }

        return Result.success(UploadResult(uploaded, head))
    }

    /**
     * A reading only counts as uploaded once the server's own hash for it has been compared. Being at
     * or below the server's count says nothing about whether it's the same reading.
     *
     * @return the first sequence number that differs or is missing, or null if they all match.
     */
    private suspend fun firstDifference(shipmentId: String, readings: List<TemperatureEvent>): Result<Long?> {
        val first = readings.firstOrNull() ?: return Result.success(null)
        val held = remote.getReadingsAfter(shipmentId, first.seq - 1)
            .getOrElse { return Result.failure(it) }
            .associateBy { it.seq }
        return Result.success(readings.firstOrNull { held[it.seq]?.currentHash != it.currentHash }?.seq)
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 500
    }
}
