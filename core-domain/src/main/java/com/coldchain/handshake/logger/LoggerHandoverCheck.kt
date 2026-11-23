package com.coldchain.handshake.logger

import com.coldchain.handshake.logger.LoggerCheckOutcome.FAILED
import com.coldchain.handshake.logger.LoggerCheckOutcome.NOT_CHECKED
import com.coldchain.handshake.logger.LoggerCheckOutcome.PASSED
import com.coldchain.handshake.logger.LoggerCheckOutcome.UNKNOWN
import com.coldchain.handshake.models.TemperatureEvent
import java.security.SecureRandom

/**
 * What reading the logger at handover showed. It can only make a handover stricter: [FAILED] and [UNKNOWN] lower
 * the verdict, and [PASSED] and [NOT_CHECKED] leave it to the other checks.
 */
enum class LoggerCheckOutcome {
    /** The logger's signed history is complete, fresh, and matches the shipment's readings. */
    PASSED,

    /** Evidence of tampering: a signature, the chain, the head or a reading doesn't hold up. */
    FAILED,

    /** The check couldn't be completed, or the logger's own history has holes or faults. */
    UNKNOWN,

    /** No logger was reachable from this phone, as with the virtual logger on someone else's phone. */
    NOT_CHECKED
}

/**
 * @property reasons Plain sentences for the handover screen, one per problem found.
 * @property newestSeq The logger's newest record, when it answered.
 */
data class LoggerCheckResult(
    val outcome: LoggerCheckOutcome,
    val reasons: List<String>,
    val issues: List<LoggerIssue> = emptyList(),
    val mismatches: List<ReadingMismatch> = emptyList(),
    val newestSeq: Long? = null
)

/**
 * Reads a shipment's logger directly at handover, LOGGER_PROTOCOL.md "At handover": every record since custody
 * started, then the head signed over a fresh nonce, all checked against the key on the shipment's record. Then it
 * compares those records with the shipment's readings.
 *
 * @param newNonce A fresh 16-byte nonce for each check.
 * @param pageSize Records asked for per request.
 * @param maxPages A bound on requests, so a logger that never stops answering can't hold the check forever.
 */
class LoggerHandoverCheck(
    private val newNonce: () -> ByteArray = { ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes) },
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES
) {

    /**
     * @param link The logger as this phone reaches it, or null if it can't.
     * @param enrolledKey The 65-byte public key on the shipment's record, never one the logger supplies.
     * @param startSeq The logger's newest record when custody started, so reading `n` is record `startSeq + n`.
     * @param readings The shipment's readings as this phone holds them.
     */
    suspend fun check(
        link: LoggerLink?,
        loggerId: String,
        enrolledKey: ByteArray?,
        startSeq: Long,
        readings: List<TemperatureEvent>
    ): LoggerCheckResult {
        if (link == null) return result(NOT_CHECKED, "No logger reachable from this phone, so it wasn't read.")
        if (enrolledKey == null) {
            return result(UNKNOWN, "The shipment has no logger key on record, so the logger can't be verified.")
        }

        val info = link.info().getOrElse { return unreadable(it) }
        if (info.loggerId != loggerId) {
            return result(FAILED, "This is logger ${info.loggerId}, not the shipment's logger $loggerId.")
        }

        val newest = link.status().getOrElse { return unreadable(it) }.newestSeq
        val batches = mutableListOf<LoggerBatch>()
        var from = startSeq + 1
        while (from <= newest) {
            if (batches.size == maxPages) {
                return result(UNKNOWN, "The logger kept sending history after $maxPages requests, so the check stopped.")
            }
            val batch = link.readHistory(from, pageSize).getOrElse { return unreadable(it) } ?: break
            batches += batch
            from = batch.lastSeq + 1
        }

        val nonce = newNonce()
        val head = link.challenge(nonce).getOrElse { return unreadable(it) }
        val verification = LoggerHistoryVerifier.verify(loggerId, enrolledKey, batches, startSeq + 1, head, nonce)

        // Readings older than the logger's oldest kept record can't be compared; the verifier reports that gap.
        val oldestHeld = verification.records.minOfOrNull { it.seq }
        val comparable = if (oldestHeld == null) readings else readings.filter { startSeq + it.seq >= oldestHeld }
        val mismatches = if (verification.issues.any { it.type == LoggerIssueType.TEST_KEY }) {
            emptyList()
        } else {
            LoggerHistoryVerifier.compare(verification.records, comparable, startSeq, upToSeq = head.headSeq)
        }
        val faults = verification.faults.filter { it > startSeq }

        val failed = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        verification.issues.forEach { issue ->
            val at = issue.seq?.let { " at record $it" } ?: ""
            when (issue.type) {
                LoggerIssueType.TEST_KEY -> failed += "The logger uses the published test key, so anyone could have signed its history."
                LoggerIssueType.BAD_SIGNATURE -> failed += "A signature doesn't match the logger's key on record$at."
                LoggerIssueType.BROKEN_CHAIN -> failed += "The logger's history was changed or reordered$at."
                LoggerIssueType.STALE_HEAD -> failed += "The logger's answer wasn't made for this check, so it may be a replay."
                LoggerIssueType.HEAD_MISMATCH -> failed += "The logger's history stops short of its newest record$at."
                LoggerIssueType.GAP -> unknown += "The logger no longer holds every record since custody started$at."
                LoggerIssueType.UNKNOWN_FLAGS -> unknown += "The logger marked a record in a way this app doesn't understand$at."
            }
        }
        val newestHeld = readings.maxOfOrNull { it.seq } ?: 0L
        mismatches.forEach { mismatch ->
            when (mismatch.type) {
                ReadingMismatchType.DIFFERENT -> failed += "Reading ${mismatch.shipmentSeq} differs from the logger's record."
                ReadingMismatchType.NOT_FROM_LOGGER -> failed += "Reading ${mismatch.shipmentSeq} was never made by the logger."
                // A hole means a reading was removed. Records after the last reading may just not be sent yet.
                ReadingMismatchType.MISSING ->
                    if (mismatch.shipmentSeq < newestHeld) failed += "Reading ${mismatch.shipmentSeq} is missing from the shipment's record."
                    else unknown += "The logger has reading ${mismatch.shipmentSeq}, which the shipment's record doesn't have yet."
            }
        }
        faults.forEach { unknown += "The logger reported a sensor fault at record $it, so that reading isn't a temperature." }

        val outcome = when {
            failed.isNotEmpty() -> FAILED
            unknown.isNotEmpty() -> UNKNOWN
            else -> PASSED
        }
        val reasons = (failed + unknown).ifEmpty { listOf("The logger's own signed history matches the shipment's readings.") }
        return LoggerCheckResult(outcome, reasons, verification.issues, mismatches, newest)
    }

    private fun result(outcome: LoggerCheckOutcome, reason: String) = LoggerCheckResult(outcome, listOf(reason))

    private fun unreadable(error: Throwable) =
        result(UNKNOWN, "Couldn't read the logger: ${error.message ?: error::class.simpleName}.")

    companion object {
        const val NONCE_SIZE = 16
        const val DEFAULT_PAGE_SIZE = 500
        const val DEFAULT_MAX_PAGES = 64
    }
}
