package com.coldchain.handshake.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class BatchSignatureTest {

    /** Software keys, standing in for the Android Keystore that signs on a real device. */
    private class TestSigner(private val keys: KeyPair = newKeys()) : DeviceSigner {
        override val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)

        override fun sign(payload: ByteArray): String {
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initSign(keys.private)
                update(payload)
            }
            return Base64.getEncoder().encodeToString(signature.sign())
        }

        companion object {
            fun newKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        }
    }

    private val head = "a".repeat(64)
    private val batch = SignedBatch("SHIP-1", firstSeq = 1, lastSeq = 5, headHash = head)
    private val signer = TestSigner()

    @Test
    fun aSignatureVerifiesWithTheDevicesPublicKey() {
        val signature = signer.sign(batch.payload())

        assertTrue(batch.verify(signer.publicKey, signature))
    }

    @Test
    fun anotherDevicesKeyDoesNotVerify() {
        val signature = signer.sign(batch.payload())

        assertFalse(batch.verify(TestSigner().publicKey, signature))
    }

    @Test
    fun aSignatureCoversTheHeadTheRangeAndTheShipment() {
        val signature = signer.sign(batch.payload())

        assertFalse(batch.copy(headHash = "b".repeat(64)).verify(signer.publicKey, signature))
        assertFalse(batch.copy(lastSeq = 4).verify(signer.publicKey, signature))
        assertFalse(batch.copy(firstSeq = 2).verify(signer.publicKey, signature))
        assertFalse(batch.copy(shipmentId = "SHIP-2").verify(signer.publicKey, signature))
    }

    @Test
    fun aMalformedKeyOrSignatureIsNotValidAndDoesNotThrow() {
        val signature = signer.sign(batch.payload())

        assertFalse(batch.verify("not a key", signature))
        assertFalse(batch.verify(signer.publicKey, "not a signature"))
        assertFalse(batch.verify(signer.publicKey, ""))
    }

    @Test
    fun aBatchPayloadCannotBeMistakenForAChainHash() {
        val readingBytes = HashChainService.readingHash("SHIP-1", "LOG-1", 1, 1, 1, head)

        assertNotEquals(readingBytes.length, batch.payload().size)
        assertEquals(2, batch.payload().first().toInt())
    }
}
