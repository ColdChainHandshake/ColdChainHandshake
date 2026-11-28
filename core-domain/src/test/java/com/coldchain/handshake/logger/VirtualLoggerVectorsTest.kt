package com.coldchain.handshake.logger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * `VirtualLogger` itself against `docs/protocol/logger_vectors.json`, as the firmware's Step 2 and Step 4 host tests
 * hold the C++ log to it (Gate 4 review, 28 Nov: the virtual logger had only been checked against itself). Given the
 * vectors' key and records, its history pages, batch payloads and signed head must be the vectors' bytes. Its
 * signatures use a random nonce rather than RFC 6979's, so they are checked by verifying, not by comparing bytes.
 */
class VirtualLoggerVectorsTest {

    private val vectors = Json.parseToJsonElement(File("../docs/protocol/logger_vectors.json").readText()).jsonObject
    private fun text(name: String) = vectors.getValue(name).jsonPrimitive.content
    private val publicKey = text("public_key_uncompressed").hexToBytes()

    /** The vectors' published key pair, the same one the firmware tests sign with. */
    private fun vectorKeys(): KeyPair {
        val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public as ECPublicKey).params
        val factory = KeyFactory.getInstance("EC")
        val x = BigInteger(1, publicKey.copyOfRange(1, 33))
        val y = BigInteger(1, publicKey.copyOfRange(33, 65))
        return KeyPair(
            factory.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)),
            factory.generatePrivate(ECPrivateKeySpec(BigInteger(text("private_key"), 16), params))
        )
    }

    private val logger = VirtualLogger(text("logger_id"), vectorKeys()).apply {
        vectors.getValue("records").jsonArray.map { it.jsonObject }.forEach {
            val made = record(
                it.getValue("logger_ts").jsonPrimitive.long,
                it.getValue("temp_centi_c").jsonPrimitive.int,
                it.getValue("flags").jsonPrimitive.int
            )
            assertEquals(it.getValue("seq").jsonPrimitive.long, made.seq)
            assertEquals(it.getValue("bytes").jsonPrimitive.content, made.encode().toHex())
        }
    }

    @Test
    fun itPresentsTheVectorsKey() = runBlocking {
        assertArrayEquals(publicKey, logger.publicKey().getOrThrow())
    }

    @Test
    fun itsHistoryPagesAreTheVectorsBatches() = runBlocking {
        vectors.getValue("batches").jsonArray.map { it.jsonObject }.forEach { expected ->
            val first = expected.getValue("first_seq").jsonPrimitive.long
            val last = expected.getValue("last_seq").jsonPrimitive.long

            val batch = logger.readHistory(first, (last - first + 1).toInt()).getOrThrow()!!

            assertEquals(first, batch.firstSeq)
            assertEquals(last, batch.lastSeq)
            assertEquals(expected.getValue("start_hash").jsonPrimitive.content, batch.startHash)
            assertEquals(expected.getValue("end_hash").jsonPrimitive.content, batch.endHash)
            val payload = LoggerProtocol.batchPayload(
                logger.loggerId, batch.firstSeq, batch.lastSeq, batch.startHash.hexToBytes(), batch.endHash.hexToBytes()
            )
            assertEquals(expected.getValue("payload").jsonPrimitive.content, payload.toHex())
            assertTrue("batch $first..$last", LoggerProtocol.verify(publicKey, payload, batch.signature.hexToBytes()))
        }
    }

    @Test
    fun itsHeadIsTheVectorsHead() = runBlocking {
        val expected = vectors.getValue("head").jsonObject
        val nonce = expected.getValue("nonce").jsonPrimitive.content.hexToBytes()

        val head = logger.challenge(nonce).getOrThrow()

        assertEquals(expected.getValue("head_seq").jsonPrimitive.long, head.headSeq)
        assertEquals(expected.getValue("head_hash").jsonPrimitive.content, head.headHash)
        val payload = LoggerProtocol.headPayload(logger.loggerId, head.headSeq, head.headHash.hexToBytes(), nonce)
        assertEquals(expected.getValue("payload").jsonPrimitive.content, payload.toHex())
        assertTrue(LoggerProtocol.verify(publicKey, payload, head.signature.hexToBytes()))
    }
}
