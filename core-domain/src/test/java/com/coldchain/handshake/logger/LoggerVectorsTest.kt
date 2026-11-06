package com.coldchain.handshake.logger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The vectors come from plain Python with its own ECDSA, so agreeing with them means agreeing with the
 * written protocol, not just with this code.
 */
class LoggerVectorsTest {

    private val vectors = Json.parseToJsonElement(File("../docs/protocol/logger_vectors.json").readText()).jsonObject
    private fun text(name: String) = vectors.getValue(name).jsonPrimitive.content
    private val loggerId = text("logger_id")
    private val publicKey = text("public_key_uncompressed").hexToBytes()

    private val records = vectors.getValue("records").jsonArray.map { it.jsonObject }.map {
        LoggerRecord(
            it.getValue("seq").jsonPrimitive.long,
            it.getValue("logger_ts").jsonPrimitive.long,
            it.getValue("temp_centi_c").jsonPrimitive.int,
            it.getValue("flags").jsonPrimitive.int
        ) to it
    }

    private val batches = vectors.getValue("batches").jsonArray.map { it.jsonObject }.map { batch ->
        val first = batch.getValue("first_seq").jsonPrimitive.long
        val last = batch.getValue("last_seq").jsonPrimitive.long
        LoggerBatch(
            first, last,
            batch.getValue("start_hash").jsonPrimitive.content,
            batch.getValue("end_hash").jsonPrimitive.content,
            records.map { it.first }.filter { it.seq in first..last },
            batch.getValue("signature_raw").jsonPrimitive.content
        ) to batch
    }

    @Test
    fun recordsAndRunningHashesMatch() {
        var hash = LoggerProtocol.genesis(loggerId)
        assertEquals(text("genesis_hash"), hash.toHex())
        records.forEach { (record, expected) ->
            assertEquals(expected.getValue("bytes").jsonPrimitive.content, record.encode().toHex())
            hash = LoggerProtocol.chain(hash, record)
            assertEquals(expected.getValue("running_hash").jsonPrimitive.content, hash.toHex())
        }
    }

    @Test
    fun batchPayloadsAndSignaturesMatch() {
        batches.forEach { (batch, expected) ->
            val payload = LoggerProtocol.batchPayload(
                loggerId, batch.firstSeq, batch.lastSeq, batch.startHash.hexToBytes(), batch.endHash.hexToBytes()
            )
            assertEquals(expected.getValue("payload").jsonPrimitive.content, payload.toHex())
            val raw = batch.signature.hexToBytes()
            assertEquals(expected.getValue("signature_der").jsonPrimitive.content, LoggerProtocol.rawToDer(raw).toHex())
            assertEquals(batch.signature, LoggerProtocol.derToRaw(LoggerProtocol.rawToDer(raw)).toHex())
            assertTrue(LoggerProtocol.verify(publicKey, payload, raw))
            assertFalse(LoggerProtocol.verify(publicKey, payload + 0, raw))
        }
    }

    @Test
    fun theVectorHistoryVerifiesWithItsHead() {
        val head = vectors.getValue("head").jsonObject
        val verification = LoggerHistoryVerifier.verify(
            loggerId, publicKey, batches.map { it.first },
            head = LoggerHead(
                head.getValue("head_seq").jsonPrimitive.long,
                head.getValue("head_hash").jsonPrimitive.content,
                head.getValue("nonce").jsonPrimitive.content,
                head.getValue("signature_raw").jsonPrimitive.content
            ),
            nonce = head.getValue("nonce").jsonPrimitive.content.hexToBytes()
        )

        assertTrue(verification.issues.toString(), verification.trusted)
        assertEquals(listOf(5L), verification.faults)
    }

    @Test
    fun anEditedOrDroppedRecordBreaksTheChain() {
        val (first, second) = batches.map { it.first }
        val edited = first.copy(records = first.records.mapIndexed { i, r -> if (i == 1) r.copy(tempCentiC = 300) else r })
        val dropped = first.copy(records = first.records.drop(1))

        assertTrue(LoggerHistoryVerifier.verify(loggerId, publicKey, listOf(edited, second)).issues.any { it.type == LoggerIssueType.BROKEN_CHAIN })
        assertTrue(LoggerHistoryVerifier.verify(loggerId, publicKey, listOf(dropped, second)).issues.any { it.type == LoggerIssueType.BROKEN_CHAIN })
        assertTrue(LoggerHistoryVerifier.verify(loggerId, publicKey, listOf(second)).issues.any { it.type == LoggerIssueType.GAP })
    }

    @Test
    fun aVirtualLoggersHistoryVerifiesAndAReplayedHeadDoesNot() = runBlocking {
        val logger = VirtualLogger("LOG-V")
        (1..5).forEach { logger.record(1_700_000_000_000L + it * 60_000L, 500 + it) }
        val batch = logger.readHistory(1, 100).getOrThrow()!!
        val nonce = ByteArray(16) { it.toByte() }
        val head = logger.challenge(nonce).getOrThrow()

        assertTrue(LoggerHistoryVerifier.verify("LOG-V", logger.publicKeyUncompressed, listOf(batch), head = head, nonce = nonce).trusted)
        assertTrue(
            LoggerHistoryVerifier.verify("LOG-V", logger.publicKeyUncompressed, listOf(batch), head = head, nonce = ByteArray(16))
                .issues.any { it.type == LoggerIssueType.STALE_HEAD }
        )
        assertFalse(LoggerHistoryVerifier.verify("LOG-V", VirtualLogger("LOG-V").publicKeyUncompressed, listOf(batch)).trusted)
    }
}
