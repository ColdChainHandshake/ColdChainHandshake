package com.coldchain.handshake.logger

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * The vectors publish a logger's private key, so a logger holding it proves nothing. Every build must refuse it,
 * in whatever form the key arrives.
 */
class TestLoggerKeyTest {

    private val point = VectorTestKey.publicKeyUncompressed

    @Test
    fun theGuardHoldsTheKeyFromTheVectors() {
        assertEquals(VectorTestKey.field("public_key_uncompressed").substring(2, 66), TestLoggerKey.X_HEX)
    }

    @Test
    fun theTestKeyIsRecognisedInEveryEncoding() {
        val compressed = byteArrayOf((2 + (point.last().toInt() and 1)).toByte()) + point.copyOfRange(1, 33)

        assertTrue("uncompressed point", TestLoggerKey.matches(point))
        assertTrue("compressed point", TestLoggerKey.matches(compressed))
        assertTrue("raw X and Y", TestLoggerKey.matches(point.copyOfRange(1, 65)))
        assertTrue("SPKI bytes", TestLoggerKey.matches(Base64.getDecoder().decode(VectorTestKey.spkiBase64)))
        assertTrue("SPKI from Java", TestLoggerKey.matches(VectorTestKey.keyPair().public.encoded))
        assertTrue("SPKI Base64", TestLoggerKey.matchesSpkiBase64(VectorTestKey.spkiBase64))
    }

    @Test
    fun theMirroredKeyIsRefusedBecauseItsPrivateKeyIsKnownToo() {
        val mirrored = VectorTestKey.mirroredKeyPair()
        val mirroredPoint = LoggerProtocol.uncompressed(mirrored.public as ECPublicKey)
        val payload = "any batch".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(mirrored.private)
            update(payload)
            sign()
        }

        assertFalse(mirroredPoint.contentEquals(point))
        assertTrue("n - d signs for it", LoggerProtocol.verify(mirroredPoint, payload, LoggerProtocol.derToRaw(signature)))
        assertTrue(TestLoggerKey.matches(mirroredPoint))
    }

    @Test
    fun otherKeysAreNotTheTestKey() {
        assertFalse(TestLoggerKey.matches(VirtualLogger("LOG-X").publicKeyUncompressed))
        assertFalse(TestLoggerKey.matches(ByteArray(0)))
        assertFalse(TestLoggerKey.matches(ByteArray(65)))
        assertFalse(TestLoggerKey.matchesSpkiBase64("not base64!"))
    }

    @Test
    fun theVerifierRefusesAPerfectlySignedHistoryFromTheTestKey() = runBlocking {
        val logger = VirtualLogger(VectorTestKey.loggerId, VectorTestKey.keyPair())
        (1..3).forEach { logger.record(1_700_000_000_000L + it * 60_000L, 500) }
        val batch = logger.readHistory(1, 100).getOrThrow()!!
        val nonce = ByteArray(16) { it.toByte() }
        val head = logger.challenge(nonce).getOrThrow()

        val refused = LoggerHistoryVerifier.verify(VectorTestKey.loggerId, point, listOf(batch), head = head, nonce = nonce)

        assertEquals(listOf(LoggerIssue(null, LoggerIssueType.TEST_KEY)), refused.issues)
        assertTrue(refused.records.isEmpty())
        assertFalse(refused.trusted)
        // Every other check passes, so the key alone is what's refused.
        assertTrue(LoggerHistoryVerifier.verifyAnyKey(VectorTestKey.loggerId, point, listOf(batch), head = head, nonce = nonce).trusted)
    }

    @Test
    fun theVerifierRefusesTheMirroredKey() {
        val mirroredPoint = LoggerProtocol.uncompressed(VectorTestKey.mirroredKeyPair().public as ECPublicKey)

        val refused = LoggerHistoryVerifier.verify(VectorTestKey.loggerId, mirroredPoint, emptyList())

        assertEquals(listOf(LoggerIssueType.TEST_KEY), refused.issues.map { it.type })
    }

    @Test
    fun shippedSourcesNeverHoldTheTestPrivateKey() {
        val d = VectorTestKey.field("private_key").lowercase()
        val dBase64 = Base64.getEncoder().encodeToString(VectorTestKey.hex(d))
        val shipped = listOf("../core-domain/src/main", "../app/src/main", "../app/src/debug", "../app/src/release").map(::File)

        val holders = shipped.flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .filter { file -> file.readText().let { d in it.lowercase() || dBase64 in it } }

        assertTrue("a shipped source folder is missing", shipped.all { it.isDirectory })
        assertEquals(emptyList<File>(), holders)
    }
}
