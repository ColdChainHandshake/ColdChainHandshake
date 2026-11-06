package com.coldchain.handshake.logger

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * One reading as the logger stores and signs it. See docs/protocol/LOGGER_PROTOCOL.md.
 *
 * @property seq Goes up by exactly 1 for the logger's whole life.
 */
data class LoggerRecord(
    val seq: Long,
    val loggerTs: Long,
    val tempCentiC: Int,
    val flags: Int = 0
) {
    val sensorFault: Boolean get() = flags and FLAG_SENSOR_FAULT != 0

    fun encode(): ByteArray = ByteBuffer.allocate(SIZE)
        .putLong(seq)
        .putLong(loggerTs)
        .putInt(tempCentiC)
        .putShort(flags.toShort())
        .array()

    companion object {
        const val SIZE = 22
        const val FLAG_SENSOR_FAULT = 0b0001
        const val FLAG_CLOCK_SET = 0b0010
        const val FLAG_LOW_BATTERY = 0b0100
        const val FLAG_POWER_LOST = 0b1000
        const val KNOWN_FLAGS = 0b1111
    }
}

/**
 * The bytes the logger hashes and signs, and the signature checks. Hashes and signatures travel as
 * lower-case hex in the app.
 */
object LoggerProtocol {

    const val VERSION = 1

    private const val SPKI_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d030107034200"

    fun genesis(loggerId: String): ByteArray =
        sha256(byteArrayOf(VERSION.toByte()) + lp("cch-logger-genesis") + lp(loggerId))

    fun chain(previous: ByteArray, record: LoggerRecord): ByteArray =
        sha256(byteArrayOf(VERSION.toByte()) + previous + record.encode())

    fun batchPayload(loggerId: String, firstSeq: Long, lastSeq: Long, startHash: ByteArray, endHash: ByteArray): ByteArray =
        byteArrayOf(VERSION.toByte()) + lp("cch-logger-batch") + lp(loggerId) +
            ByteBuffer.allocate(16).putLong(firstSeq).putLong(lastSeq).array() + startHash + endHash

    fun headPayload(loggerId: String, headSeq: Long, headHash: ByteArray, nonce: ByteArray): ByteArray =
        byteArrayOf(VERSION.toByte()) + lp("cch-logger-head") + lp(loggerId) +
            ByteBuffer.allocate(8).putLong(headSeq).array() + headHash + nonce

    /**
     * The logger sends `r || s`. Java verifies DER.
     */
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "A raw P-256 signature is 64 bytes." }
        val body = derInteger(raw.copyOfRange(0, 32)) + derInteger(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    fun derToRaw(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "Not a DER signature." }
        var at = 2
        fun readInteger(): ByteArray {
            require(der[at] == 0x02.toByte()) { "Not a DER signature." }
            val length = der[at + 1].toInt()
            val value = der.copyOfRange(at + 2, at + 2 + length).dropWhile { it == 0.toByte() }.toByteArray()
            at += 2 + length
            require(value.size <= 32) { "Not a P-256 signature." }
            return ByteArray(32 - value.size) + value
        }
        return readInteger() + readInteger()
    }

    fun spkiFromUncompressed(point: ByteArray): ByteArray {
        require(point.size == 65 && point[0] == 0x04.toByte()) { "An uncompressed P-256 point is 65 bytes." }
        return SPKI_PREFIX.hexToBytes() + point
    }

    fun uncompressed(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed32(key.w.affineX.toByteArray()) + fixed32(key.w.affineY.toByteArray())

    /**
     * @return false for anything malformed, never an exception.
     */
    fun verify(publicKeyUncompressed: ByteArray, payload: ByteArray, rawSignature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiFromUncompressed(publicKeyUncompressed)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(rawToDer(rawSignature))
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun derInteger(unsigned: ByteArray): ByteArray {
        var value = unsigned.dropWhile { it == 0.toByte() }.toByteArray()
        if (value.isEmpty()) value = byteArrayOf(0)
        if (value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
        return byteArrayOf(0x02, value.size.toByte()) + value
    }

    private fun fixed32(signed: ByteArray): ByteArray {
        val value = signed.dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - value.size) + value
    }

    private fun lp(text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4).putInt(data.size).array() + data
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
