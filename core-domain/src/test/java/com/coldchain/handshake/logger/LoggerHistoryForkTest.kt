package com.coldchain.handshake.logger

import com.coldchain.handshake.logger.LoggerIssueType.BROKEN_CHAIN
import com.coldchain.handshake.logger.LoggerIssueType.HEAD_MISMATCH
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature

/**
 * Gate 4 review G4-3 (mutants M7 and M8): two histories signed by one key, as a logger whose key was copied could
 * make. Each fork is signed and chains on its own; only the joins between batches and the head's hash tell a history
 * made of both from either one. Every signature here is good, so nothing but those two checks can catch them.
 */
class LoggerHistoryForkTest {

    private val loggerId = "LOG-FORK"
    private val keys = VirtualLogger.newKeyPair()

    /** The same key and the same records 1 to 3; from record 4, [b] records 9.00 °C where [a] records 5.00 °C. */
    private val a = VirtualLogger(loggerId, keys).apply { (1..10).forEach { record(it * 60_000L, 500) } }
    private val b = VirtualLogger(loggerId, keys).apply { (1..10).forEach { record(it * 60_000L, if (it >= 4) 900 else 500) } }

    private val nonce = ByteArray(16) { 3 }

    private fun batch(logger: VirtualLogger, from: Long, max: Int) = runBlocking { logger.readHistory(from, max) }.getOrThrow()!!
    private fun head(logger: VirtualLogger) = runBlocking { logger.challenge(nonce) }.getOrThrow()
    private fun verify(batches: List<LoggerBatch>, head: LoggerHead) =
        LoggerHistoryVerifier.verify(loggerId, a.publicKeyUncompressed, batches, 1, head, nonce)

    @Test
    fun eachForkOnItsOwnVerifies() {
        assertEquals(emptyList<LoggerIssue>(), verify(listOf(batch(a, 1, 5), batch(a, 6, 5)), head(a)).issues)
        assertEquals(emptyList<LoggerIssue>(), verify(listOf(batch(b, 1, 5), batch(b, 6, 5)), head(b)).issues)
    }

    @Test
    fun batchesSplicedFromTwoForksDoNotJoin() {
        // a's records 1 to 5, then b's 6 to 10 with b's own head: each piece is signed, the seam isn't.
        val verification = verify(listOf(batch(a, 1, 5), batch(b, 6, 5)), head(b))

        assertEquals(listOf(LoggerIssue(6, BROKEN_CHAIN)), verification.issues)
    }

    @Test
    fun aHeadFromTheOtherForkDoesNotMatchTheHistory() {
        // a's whole history, with a head b signed at the same sequence over its own hash.
        val verification = verify(listOf(batch(a, 1, 10)), head(b))

        assertEquals(listOf(LoggerIssue(10, HEAD_MISMATCH)), verification.issues)
    }

    @Test
    fun aFirstBatchThatDoesNotStartAtTheLoggersGenesisDoesNotJoin() {
        // a's records, re-chained from a made-up start and signed with the copied key.
        val records = batch(a, 1, 10).records
        val start = ByteArray(32) { 9 }
        val end = records.fold(start) { hash, record -> LoggerProtocol.chain(hash, record) }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(LoggerProtocol.batchPayload(loggerId, 1, 10, start, end))
            LoggerProtocol.derToRaw(sign())
        }
        val forged = LoggerBatch(1, 10, start.toHex(), end.toHex(), records, signature.toHex())

        val verification = verify(listOf(forged), head(a))

        assertTrue(verification.issues.toString(), LoggerIssue(1, BROKEN_CHAIN) in verification.issues)
    }
}
