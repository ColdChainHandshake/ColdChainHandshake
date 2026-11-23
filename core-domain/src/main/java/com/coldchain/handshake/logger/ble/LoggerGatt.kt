package com.coldchain.handshake.logger.ble

import com.coldchain.handshake.logger.LoggerHead
import com.coldchain.handshake.logger.LoggerInfo
import com.coldchain.handshake.logger.LoggerRecord
import com.coldchain.handshake.logger.LoggerStatus
import com.coldchain.handshake.logger.toHex
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Why talking to a logger over Bluetooth failed. None of these is evidence about the logger's history: a failed
 * read only means the check couldn't be done, so the caller treats it as UNKNOWN and may try again.
 */
class LoggerGattException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** The connection's MTU can't carry a 145-byte batch header, and the logger sends nothing below 148. */
        MTU_TOO_SMALL,

        /** Bytes that don't follow the layout in LOGGER_PROTOCOL.md. */
        MALFORMED,

        /** A protocol version this app doesn't speak. */
        UNSUPPORTED_VERSION,

        /** The logger didn't finish answering in time. */
        TIMED_OUT,

        /** The pages and the batch header don't match, as when a notification is lost. */
        INCOMPLETE
    }
}

/**
 * The GATT layout and the bytes each characteristic carries, LOGGER_PROTOCOL.md "Bluetooth Low Energy".
 */
object LoggerGatt {
    val SERVICE: UUID = uuid(0x0001)
    val DEVICE_INFORMATION: UUID = uuid(0x0002)
    val PUBLIC_KEY: UUID = uuid(0x0003)
    val HISTORY_REQUEST: UUID = uuid(0x0004)
    val HISTORY_DATA: UUID = uuid(0x0005)
    val CHALLENGE: UUID = uuid(0x0006)
    val CHALLENGE_RESPONSE: UUID = uuid(0x0007)
    val STATUS: UUID = uuid(0x0008)

    /** Below this the 145-byte batch header doesn't fit in one notification, and the logger sends nothing. */
    const val MIN_ATT_MTU = 148

    /** The most Android lets a phone ask for. */
    const val PREFERRED_ATT_MTU = 517

    const val PAGE_RECORDS: Int = 1
    const val PAGE_BATCH_HEADER: Int = 2
    const val BATCH_HEADER_SIZE = 145
    const val CHALLENGE_RESPONSE_SIZE = 104
    const val NONCE_SIZE = 16
    const val MAX_RECORDS_PER_REQUEST = 0xFFFF

    /** One history notification, decoded. */
    sealed interface HistoryNotification {
        data class Records(val records: List<LoggerRecord>) : HistoryNotification
        data class BatchHeader(
            val firstSeq: Long,
            val lastSeq: Long,
            val startHash: String,
            val endHash: String,
            val signature: String
        ) : HistoryNotification
    }

    /** `lp(logger_id) u8(protocol) lp(firmware_version)`, nothing after. */
    fun decodeDeviceInformation(bytes: ByteArray): LoggerInfo = decoding(bytes) {
        val loggerId = lp()
        val protocol = get().toInt() and 0xFF
        val firmware = lp()
        LoggerInfo(loggerId, protocol, firmware)
    }

    /** The 65-byte uncompressed point, `04 || X || Y`. */
    fun decodePublicKey(bytes: ByteArray): ByteArray {
        if (bytes.size != PUBLIC_KEY_SIZE || bytes[0] != UNCOMPRESSED) {
            throw malformed("a public key must be a 65-byte uncompressed point, not ${bytes.size} bytes")
        }
        return bytes.copyOf()
    }

    /** `i64(oldest_seq) i64(newest_seq) u16(flags)`. The flags aren't used yet. */
    fun decodeStatus(bytes: ByteArray): LoggerStatus = decoding(bytes) {
        val oldest = long
        val newest = long
        short
        LoggerStatus(oldest, newest)
    }

    /** `i64(from_seq) u16(max_records)`. */
    fun encodeHistoryRequest(fromSeq: Long, maxRecords: Int): ByteArray {
        require(maxRecords in 1..MAX_RECORDS_PER_REQUEST) { "maxRecords must be 1 to $MAX_RECORDS_PER_REQUEST" }
        return ByteBuffer.allocate(10).putLong(fromSeq).putShort(maxRecords.toShort()).array()
    }

    /**
     * A page, `u8(1) u16(count)` then exactly `count` records, or a batch header,
     * `u8(2) i64(first) i64(last) raw(32) raw(32) raw(64)`.
     */
    fun decodeHistoryNotification(bytes: ByteArray): HistoryNotification = decoding(bytes) {
        when (val kind = get().toInt() and 0xFF) {
            PAGE_RECORDS -> {
                val count = short.toInt() and 0xFFFF
                if (count == 0) throw malformed("a page holds at least one record")
                if (remaining() != count * LoggerRecord.SIZE) {
                    throw malformed("a page says $count records but carries ${remaining()} bytes")
                }
                HistoryNotification.Records(List(count) { LoggerRecord(long, long, int, short.toInt() and 0xFFFF) })
            }
            PAGE_BATCH_HEADER -> {
                if (bytes.size != BATCH_HEADER_SIZE) throw malformed("a batch header is $BATCH_HEADER_SIZE bytes, not ${bytes.size}")
                HistoryNotification.BatchHeader(long, long, raw(HASH_SIZE), raw(HASH_SIZE), raw(SIGNATURE_SIZE))
            }
            else -> throw malformed("unknown history page kind $kind")
        }
    }

    /** `i64(head_seq) raw(h(head_seq)) raw(signature)`, signed over [nonce], which this reader sent. */
    fun decodeChallengeResponse(bytes: ByteArray, nonce: ByteArray): LoggerHead = decoding(bytes) {
        if (bytes.size != CHALLENGE_RESPONSE_SIZE) {
            throw malformed("a challenge response is $CHALLENGE_RESPONSE_SIZE bytes, not ${bytes.size}")
        }
        LoggerHead(long, raw(HASH_SIZE), nonce.toHex(), raw(SIGNATURE_SIZE))
    }

    /** The records a page holds when the MTU is [attMtu]: `u8 u16` then whole 22-byte records. */
    fun recordsPerPage(attMtu: Int): Int = maxOf(0, (attMtu - ATT_HEADER - PAGE_HEADER) / LoggerRecord.SIZE)

    private const val PUBLIC_KEY_SIZE = 65
    private const val UNCOMPRESSED: Byte = 0x04
    private const val HASH_SIZE = 32
    private const val SIGNATURE_SIZE = 64
    private const val ATT_HEADER = 3
    private const val PAGE_HEADER = 3

    private fun uuid(short: Int): UUID = UUID.fromString("6f1c%04x-8e4a-4c43-9b3a-2d7c5e1f0a00".format(short))

    private fun malformed(message: String) = LoggerGattException(LoggerGattException.Kind.MALFORMED, message)

    /** Reads big-endian fields; running past the end, or leaving bytes over, is malformed. */
    private fun <T> decoding(bytes: ByteArray, read: ByteBuffer.() -> T): T {
        val buffer = ByteBuffer.wrap(bytes)
        val value = try {
            buffer.read()
        } catch (e: BufferUnderflowException) {
            throw malformed("${bytes.size} bytes end too soon")
        }
        if (buffer.hasRemaining()) throw malformed("${buffer.remaining()} bytes left over")
        return value
    }

    private fun ByteBuffer.lp(): String {
        val length = int
        if (length < 0 || length > remaining()) throw malformed("a text field says $length bytes but ${remaining()} remain")
        return String(bytes(length), Charsets.UTF_8)
    }

    private fun ByteBuffer.bytes(size: Int): ByteArray = ByteArray(size).also { get(it) }

    private fun ByteBuffer.raw(size: Int): String = bytes(size).toHex()
}
