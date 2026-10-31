package com.coldchain.handshake.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.SignedBatch
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * This install's P-256 key, which never leaves the Android Keystore. It proves which phone uploaded a
 * batch, not that the readings in it are true.
 */
class KeystoreDeviceSigner(private val alias: String) : DeviceSigner {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    private fun keyEntry(): KeyStore.PrivateKeyEntry {
        if (!keyStore.containsAlias(alias)) generateKey()
        return keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    private fun generateKey() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generateKeyPair()
        }
    }

    override val publicKey: String
        get() = Base64.getEncoder().encodeToString(keyEntry().certificate.publicKey.encoded)

    override fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance(SignedBatch.SIGNATURE_ALGORITHM).apply {
            initSign(keyEntry().privateKey)
            update(payload)
        }
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
