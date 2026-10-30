package com.coldchain.handshake.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signs with this device's own key. On a phone that key lives in the Android Keystore.
 *
 * @property publicKey X.509 SubjectPublicKeyInfo, Base64.
 */
interface DeviceSigner {
    val publicKey: String

    /**
     * @return a DER-encoded ECDSA P-256 signature, Base64.
     */
    fun sign(payload: ByteArray): String
}

/**
 * The claim a device signs when it uploads readings: these readings, ending at this head.
 *
 * This proves which phone uploaded, not that the readings are true. A courier's own phone can sign
 * anything. The readings only become evidence once a logger signs its own history.
 */
data class SignedBatch(
    val shipmentId: String,
    val firstSeq: Long,
    val lastSeq: Long,
    val headHash: String
) {

    /**
     * The bytes are defined in docs/protocol/CANONICAL_ENCODING.md.
     */
    fun payload(): ByteArray {
        val shipment = shipmentId.toByteArray(Charsets.UTF_8)
        val head = headHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return ByteBuffer.allocate(1 + Int.SIZE_BYTES + shipment.size + 2 * Long.SIZE_BYTES + head.size)
            .put(HashChainService.CHAIN_VERSION.toByte())
            .putInt(shipment.size)
            .put(shipment)
            .putLong(firstSeq)
            .putLong(lastSeq)
            .put(head)
            .array()
    }

    fun verify(publicKey: String, signature: String): Boolean = try {
        val key = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(key)
            update(payload())
            verify(Base64.getDecoder().decode(signature))
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        const val KEY_ALGORITHM = "EC"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
