package com.coldchain.handshake.logger

import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * The key pair from `docs/protocol/logger_vectors.json`. Its private half is published, so every build of the
 * app refuses a logger that holds it. Test code only: never move this into a main, debug or release source set.
 */
object VectorTestKey {

    // Tests run from their module's folder, so the repository root is one level up.
    private val json: String by lazy { File("../docs/protocol/logger_vectors.json").readText() }

    /** A top-level text field of the vectors. A regex is enough for these flat fields and needs no JSON library. */
    fun field(name: String): String =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: error("No $name in the vectors")

    val loggerId: String get() = field("logger_id")
    val publicKeyUncompressed: ByteArray get() = hex(field("public_key_uncompressed"))
    val spkiBase64: String get() = field("public_key_spki_base64")
    private val privateScalar: BigInteger get() = BigInteger(field("private_key"), 16)

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun keyPair(): KeyPair = keyPair(privateScalar, publicKeyUncompressed)

    /** The same X with the other Y: the public key of `n - d`, which anyone holding `d` can sign with. */
    fun mirroredKeyPair(): KeyPair {
        val p = (p256.curve.field as ECFieldFp).p
        val point = publicKeyUncompressed
        val y = BigInteger(1, point.copyOfRange(33, 65))
        val mirrored = byteArrayOf(0x04) + point.copyOfRange(1, 33) + fixed32(p - y)
        return keyPair(p256.order - privateScalar, mirrored)
    }

    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun keyPair(scalar: BigInteger, point: ByteArray): KeyPair {
        val factory = KeyFactory.getInstance("EC")
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        return KeyPair(
            factory.generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)),
            factory.generatePrivate(ECPrivateKeySpec(scalar, p256))
        )
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().takeLast(32).toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }
}
