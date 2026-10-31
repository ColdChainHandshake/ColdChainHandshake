package com.coldchain.handshake.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coldchain.handshake.crypto.SignedBatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * Needs a device or emulator, because only a real Android Keystore can show this works. The unit tests
 * use software keys instead.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceSignerTest {

    private val alias = "cch-test-key"
    private val signer = KeystoreDeviceSigner(alias)
    private val batch = SignedBatch("SHIP-1", firstSeq = 1, lastSeq = 5, headHash = "a".repeat(64))

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aSignatureVerifiesWithThePublicKeyTheServerIsGiven() {
        val signature = signer.sign(batch.payload())

        assertTrue(batch.verify(signer.publicKey, signature))
    }

    @Test
    fun aSignatureCoversTheBatch() {
        val signature = signer.sign(batch.payload())

        assertFalse(batch.copy(lastSeq = 4).verify(signer.publicKey, signature))
    }

    @Test
    fun theKeyStaysTheSameBetweenUses() {
        assertEquals(signer.publicKey, KeystoreDeviceSigner(alias).publicKey)
    }
}
