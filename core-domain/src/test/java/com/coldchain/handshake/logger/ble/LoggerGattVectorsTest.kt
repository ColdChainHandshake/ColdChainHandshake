package com.coldchain.handshake.logger.ble

import com.coldchain.handshake.logger.LoggerBatch
import com.coldchain.handshake.logger.LoggerHistoryVerifier
import com.coldchain.handshake.logger.LoggerProtocol
import com.coldchain.handshake.logger.LoggerRecord
import com.coldchain.handshake.logger.hexToBytes
import com.coldchain.handshake.logger.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The GATT bytes come from the separate Python implementation in `tools/protocol/gen_logger_vectors.py`, so
 * decoding them proves the app reads what the protocol says a logger sends, not just what this code writes.
 */
class LoggerGattVectorsTest {

    private val vectors = Json.parseToJsonElement(File("../docs/protocol/logger_vectors.json").readText()).jsonObject
    private val gatt = vectors.getValue("gatt").jsonObject
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private val loggerId = vectors.text("logger_id")
    private val publicKey = vectors.text("public_key_uncompressed").hexToBytes()
    private val records = vectors.getValue("records").jsonArray.map { it.jsonObject }.map {
        LoggerRecord(
            it.getValue("seq").jsonPrimitive.long,
            it.getValue("logger_ts").jsonPrimitive.long,
            it.getValue("temp_centi_c").jsonPrimitive.int,
            it.getValue("flags").jsonPrimitive.int
        )
    }
    private val requests = gatt.getValue("history_requests").jsonArray.map { it.jsonObject }

    @Test
    fun theUuidsAreTheOnesInTheProtocolTable() {
        val expected = mapOf(
            LoggerGatt.SERVICE to "6f1c0001-8e4a-4c43-9b3a-2d7c5e1f0a00",
            LoggerGatt.DEVICE_INFORMATION to "6f1c0002-8e4a-4c43-9b3a-2d7c5e1f0a00",
            LoggerGatt.PUBLIC_KEY to "6f1c0003-8e4a-4c43-9b3a-2d7c5e1f0a00",
            LoggerGatt.HISTORY_REQUEST to "6f1c0004-8e4a-4c43-9b3a-2d7c5e1f0a00",
            LoggerGatt.HISTORY_DATA to "6f1c0005-8e4a-4c43-9b3a-2d7c5e1f0a00",
            LoggerGatt.CHALLENGE to "6f1c0006-8e4a-4c43-9b3a-2d7c5e1f0a00",
            LoggerGatt.CHALLENGE_RESPONSE to "6f1c0007-8e4a-4c43-9b3a-2d7c5e1f0a00",
            LoggerGatt.STATUS to "6f1c0008-8e4a-4c43-9b3a-2d7c5e1f0a00"
        )
        expected.forEach { (uuid, text) -> assertEquals(UUID.fromString(text), uuid) }
    }

    @Test
    fun deviceInformationPublicKeyAndStatusDecode() {
        val info = LoggerGatt.decodeDeviceInformation(gatt.text("device_information").hexToBytes())
        assertEquals(loggerId, info.loggerId)
        assertEquals(1, info.protocolVersion)
        assertEquals(gatt.text("firmware_version"), info.firmwareVersion)

        assertArrayEquals(publicKey, LoggerGatt.decodePublicKey(gatt.text("public_key").hexToBytes()))

        val status = LoggerGatt.decodeStatus(gatt.text("status").hexToBytes())
        assertEquals(1L, status.oldestSeq)
        assertEquals(7L, status.newestSeq)
    }

    @Test
    fun historyRequestsEncodeAsTheVectorsDo() {
        requests.forEach { request ->
            val bytes = LoggerGatt.encodeHistoryRequest(
                request.getValue("from_seq").jsonPrimitive.long,
                request.getValue("max_records").jsonPrimitive.int
            )
            assertEquals(request.text("request"), bytes.toHex())
        }
    }

    @Test
    fun everyHistoryAnswerDecodesToRecordsAndASignedBatchThatVerifies() {
        var checked = 0
        requests.forEach { request ->
            val notifications = request.getValue("notifications").jsonArray.map { it.jsonPrimitive.content.hexToBytes() }
            if (notifications.isEmpty()) return@forEach
            val decoded = notifications.map(LoggerGatt::decodeHistoryNotification)
            val pages = decoded.dropLast(1).map { it as LoggerGatt.HistoryNotification.Records }
            val header = decoded.last() as LoggerGatt.HistoryNotification.BatchHeader
            val received = pages.flatMap { it.records }

            val fromSeq = request.getValue("from_seq").jsonPrimitive.long
            val max = request.getValue("max_records").jsonPrimitive.int
            assertEquals(records.filter { it.seq >= fromSeq }.take(max), received)
            assertEquals(received.first().seq, header.firstSeq)
            assertEquals(received.last().seq, header.lastSeq)

            val batch = LoggerBatch(header.firstSeq, header.lastSeq, header.startHash, header.endHash, received, header.signature)
            val verification = LoggerHistoryVerifier.verifyAnyKey(loggerId, publicKey, listOf(batch), fromSeq = fromSeq)
            assertTrue("from $fromSeq: ${verification.issues}", verification.trusted)
            checked++
        }
        assertEquals(3, checked)
    }

    @Test
    fun aPageHoldsAsManyWholeRecordsAsTheMtuAllows() {
        assertEquals(6, LoggerGatt.recordsPerPage(148))
        assertEquals(8, LoggerGatt.recordsPerPage(185))
        assertEquals(23, LoggerGatt.recordsPerPage(517))
        val twoPages = requests.first { it.getValue("att_mtu").jsonPrimitive.int == 148 && it.getValue("from_seq").jsonPrimitive.long == 1L }
        val pageSizes = twoPages.getValue("notifications").jsonArray.dropLast(1).map {
            (LoggerGatt.decodeHistoryNotification(it.jsonPrimitive.content.hexToBytes()) as LoggerGatt.HistoryNotification.Records).records.size
        }
        assertEquals(listOf(6, 1), pageSizes)
    }

    @Test
    fun theChallengeResponseDecodesToTheSignedHead() {
        val challenge = gatt.getValue("challenge").jsonObject
        val nonce = challenge.text("nonce").hexToBytes()
        val head = LoggerGatt.decodeChallengeResponse(challenge.text("response").hexToBytes(), nonce)
        val expected = vectors.getValue("head").jsonObject
        assertEquals(expected.getValue("head_seq").jsonPrimitive.long, head.headSeq)
        assertEquals(expected.text("head_hash"), head.headHash)
        assertEquals(expected.text("signature_raw"), head.signature)
        assertEquals(challenge.text("nonce"), head.nonce)
        assertTrue(
            LoggerProtocol.verify(
                publicKey,
                LoggerProtocol.headPayload(loggerId, head.headSeq, head.headHash.hexToBytes(), nonce),
                head.signature.hexToBytes()
            )
        )
    }

    @Test
    fun bytesThatDontFollowTheLayoutAreRefusedNotGuessed() {
        val page = requests.first().getValue("notifications").jsonArray.first().jsonPrimitive.content.hexToBytes()
        val header = requests.first().getValue("notifications").jsonArray.last().jsonPrimitive.content.hexToBytes()
        val malformed = listOf(
            "a page one byte short" to { LoggerGatt.decodeHistoryNotification(page.copyOf(page.size - 1)) },
            "a page with a byte too many" to { LoggerGatt.decodeHistoryNotification(page + 0) },
            "a header one byte short" to { LoggerGatt.decodeHistoryNotification(header.copyOf(header.size - 1)) },
            "an unknown page kind" to { LoggerGatt.decodeHistoryNotification(byteArrayOf(3) + page.copyOfRange(1, page.size)) },
            "an empty notification" to { LoggerGatt.decodeHistoryNotification(ByteArray(0)) },
            "a page of no records" to { LoggerGatt.decodeHistoryNotification(byteArrayOf(1, 0, 0)) },
            "a status of the wrong length" to { LoggerGatt.decodeStatus(ByteArray(17)) },
            "a compressed public key" to { LoggerGatt.decodePublicKey(publicKey.copyOf(33).also { it[0] = 2 }) },
            "a public key without its 04 prefix" to { LoggerGatt.decodePublicKey(publicKey.copyOf().also { it[0] = 5 }) },
            "device information with bytes left over" to {
                LoggerGatt.decodeDeviceInformation(gatt.text("device_information").hexToBytes() + 0)
            },
            "device information whose name runs past the end" to {
                LoggerGatt.decodeDeviceInformation(byteArrayOf(0, 0, 0, 50, 65))
            },
            "a challenge response one byte short" to {
                LoggerGatt.decodeChallengeResponse(ByteArray(LoggerGatt.CHALLENGE_RESPONSE_SIZE - 1), ByteArray(16))
            }
        )
        malformed.forEach { (name, decode) ->
            try {
                decode()
                fail("$name was accepted")
            } catch (e: LoggerGattException) {
                assertEquals(name, LoggerGattException.Kind.MALFORMED, e.kind)
            }
        }
    }
}
