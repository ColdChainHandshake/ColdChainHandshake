package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.UploadedBatch

enum class UploadIssueType {
    /** Some readings aren't in any batch, or batches overlap. */
    NOT_COVERED,
    UNSIGNED,

    /** The uploader's key can't be read, so the signature can't be checked. */
    KEY_UNAVAILABLE,
    BAD_SIGNATURE,

    /** The batch ends on a different reading from the one held here. */
    HEAD_MISMATCH
}

data class UploadIssue(
    val firstSeq: Long,
    val lastSeq: Long,
    val type: UploadIssueType
)

/**
 * Checks each upload was signed by the device that claims it. This shows which phone uploaded, not
 * that the readings are true: a courier's own phone can sign anything.
 */
object UploadAudit {

    fun check(
        shipmentId: String,
        batches: List<UploadedBatch>,
        events: List<TemperatureEvent>
    ): List<UploadIssue> {
        val issues = mutableListOf<UploadIssue>()
        val bySeq = events.associateBy { it.seq }
        var expectedFirst = 1L

        for (batch in batches.sortedBy { it.lastSeq }) {
            if (batch.firstSeq != expectedFirst) {
                issues += UploadIssue(expectedFirst, batch.firstSeq, UploadIssueType.NOT_COVERED)
            }
            expectedFirst = batch.lastSeq + 1

            val problem = problemWith(shipmentId, batch)
            if (problem != null) issues += UploadIssue(batch.firstSeq, batch.lastSeq, problem)

            val held = bySeq[batch.lastSeq]
            if (held != null && held.currentHash != batch.headHash) {
                issues += UploadIssue(batch.firstSeq, batch.lastSeq, UploadIssueType.HEAD_MISMATCH)
            }
        }

        val lastHeld = events.maxOfOrNull { it.seq } ?: 0L
        if (expectedFirst <= lastHeld) {
            issues += UploadIssue(expectedFirst, lastHeld, UploadIssueType.NOT_COVERED)
        }

        return issues
    }

    private fun problemWith(shipmentId: String, batch: UploadedBatch): UploadIssueType? {
        val signature = batch.signature ?: return UploadIssueType.UNSIGNED
        val publicKey = batch.publicKey ?: return UploadIssueType.KEY_UNAVAILABLE
        val claim = SignedBatch(shipmentId, batch.firstSeq, batch.lastSeq, batch.headHash)
        return if (claim.verify(publicKey, signature)) null else UploadIssueType.BAD_SIGNATURE
    }
}
