package com.coldchain.handshake.logger

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A logger in software, following the protocol exactly, for tests and for builds without hardware.
 *
 * Its key lives in the same app as the courier's phone, so it proves nothing about temperature. It
 * exists so the protocol, the checks and the screens all run end to end before a real logger does.
 */
class VirtualLogger(
    val loggerId: String,
    private val keys: KeyPair = newKeyPair(),
    private val capacity: Int = DEFAULT_CAPACITY,
    private val firmwareVersion: String = "virtual-1"
) : LoggerLink {

    private val records = ArrayDeque<LoggerRecord>()
    private val hashes = ArrayDeque<ByteArray>()
    private var hashBeforeOldest = LoggerProtocol.genesis(loggerId)
    private var nextSeq = 1L

    val publicKeyUncompressed: ByteArray = LoggerProtocol.uncompressed(keys.public as ECPublicKey)

    @Synchronized
    fun record(loggerTs: Long, tempCentiC: Int, flags: Int = 0): LoggerRecord {
        val record = LoggerRecord(nextSeq++, loggerTs, tempCentiC, flags)
        hashes.addLast(LoggerProtocol.chain(hashes.lastOrNull() ?: hashBeforeOldest, record))
        records.addLast(record)
        // A full ring buffer overwrites its oldest record, and a reader asking for it sees the gap.
        if (records.size > capacity) {
            records.removeFirst()
            hashBeforeOldest = hashes.removeFirst()
        }
        return record
    }

    override suspend fun info() = Result.success(LoggerInfo(loggerId, LoggerProtocol.VERSION, firmwareVersion))

    override suspend fun publicKey() = Result.success(publicKeyUncompressed.copyOf())

    override suspend fun status() = Result.success(snapshot { LoggerStatus(records.firstOrNull()?.seq ?: nextSeq, nextSeq - 1) })

    override suspend fun readHistory(fromSeq: Long, maxRecords: Int): Result<LoggerBatch?> = Result.success(
        snapshot {
            val firstIndex = records.indexOfFirst { it.seq >= fromSeq }
            if (firstIndex < 0 || maxRecords < 1) return@snapshot null
            val lastIndex = minOf(records.size, firstIndex + maxRecords) - 1
            val start = if (firstIndex == 0) hashBeforeOldest else hashes[firstIndex - 1]
            val end = hashes[lastIndex]
            val first = records[firstIndex].seq
            val last = records[lastIndex].seq
            LoggerBatch(
                firstSeq = first,
                lastSeq = last,
                startHash = start.toHex(),
                endHash = end.toHex(),
                records = records.subList(firstIndex, lastIndex + 1).toList(),
                signature = sign(LoggerProtocol.batchPayload(loggerId, first, last, start, end)).toHex()
            )
        }
    )

    override suspend fun challenge(nonce: ByteArray): Result<LoggerHead> = Result.success(
        snapshot {
            val headSeq = nextSeq - 1
            val headHash = hashes.lastOrNull() ?: hashBeforeOldest
            LoggerHead(
                headSeq = headSeq,
                headHash = headHash.toHex(),
                nonce = nonce.toHex(),
                signature = sign(LoggerProtocol.headPayload(loggerId, headSeq, headHash, nonce)).toHex()
            )
        }
    )

    @Synchronized
    private fun <T> snapshot(block: () -> T): T = block()

    private fun sign(payload: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(payload)
            sign()
        }
        return LoggerProtocol.derToRaw(der)
    }

    companion object {
        /** The reference design's ring buffer: 5.7 days of one reading a minute. */
        const val DEFAULT_CAPACITY = 8_192

        fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
