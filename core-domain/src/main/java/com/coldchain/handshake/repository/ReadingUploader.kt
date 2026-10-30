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
        var head = remote.getChainHead(shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("The server has no record of shipment $shipmentId."))

        val sorted = pending.sortedBy { it.seq }
        val (alreadyThere, unsent) = sorted.partition { it.seq <= head.count }

        val atHead = alreadyThere.firstOrNull { it.seq == head.count }
        if (atHead != null && atHead.currentHash != head.headHash) {
            return Result.failure(
                IllegalStateException("Reading ${atHead.seq} differs from the one the server holds.")
            )
        }

        val uploaded = alreadyThere.map { it.id }.toMutableList()

        for (batch in unsent.chunked(maxBatchSize)) {
            val claim = SignedBatch(
                shipmentId = shipmentId,
                firstSeq = batch.first().seq,
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

    private companion object {
        const val DEFAULT_BATCH_SIZE = 500
    }
}
