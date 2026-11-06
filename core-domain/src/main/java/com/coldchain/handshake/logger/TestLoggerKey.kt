package com.coldchain.handshake.logger

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The logger key whose private half `docs/protocol/logger_vectors.json` publishes. Anyone can sign as it, so a logger
 * presenting it proves nothing, and every build refuses it, debug included. Only test code may hold the key pair.
 */
object TestLoggerKey {

    /**
     * The X coordinate of its public point. Only X is compared: the point with the same X and the other Y belongs to
     * the private key `n - d`, which anyone holding `d` can work out.
     */
    internal const val X_HEX = "6f56e353e87d63e976bf24b09e68063378c5d5611385f9069700c438834d6ef7"
    private val x = BigInteger(X_HEX, 16)

    /**
     * True for the test key or its mirror as an uncompressed point, a compressed point, raw `X || Y`, or X.509
     * SubjectPublicKeyInfo. False for anything else, including bytes that aren't a key at all.
     */
    fun matches(publicKey: ByteArray): Boolean = xOf(publicKey) == x

    fun matchesSpkiBase64(spkiBase64: String): Boolean =
        runCatching { Base64.getDecoder().decode(spkiBase64.trim()) }.getOrNull()?.let(::matches) ?: false

    private fun xOf(key: ByteArray): BigInteger? = when {
        key.size == 65 && key[0] == UNCOMPRESSED -> BigInteger(1, key.copyOfRange(1, 33))
        key.size == 33 && (key[0] == COMPRESSED_EVEN || key[0] == COMPRESSED_ODD) -> BigInteger(1, key.copyOfRange(1, 33))
        key.size == 64 -> BigInteger(1, key.copyOfRange(0, 32))
        else -> runCatching {
            (KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)) as ECPublicKey).w.affineX
        }.getOrNull()
    }

    private const val UNCOMPRESSED: Byte = 0x04
    private const val COMPRESSED_EVEN: Byte = 0x02
    private const val COMPRESSED_ODD: Byte = 0x03
}
