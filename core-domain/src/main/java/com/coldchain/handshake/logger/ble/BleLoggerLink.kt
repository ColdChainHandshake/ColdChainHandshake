package com.coldchain.handshake.logger.ble

import com.coldchain.handshake.logger.LoggerBatch
import com.coldchain.handshake.logger.LoggerHead
import com.coldchain.handshake.logger.LoggerInfo
import com.coldchain.handshake.logger.LoggerLink
import com.coldchain.handshake.logger.LoggerProtocol
import com.coldchain.handshake.logger.LoggerStatus
import com.coldchain.handshake.logger.ble.LoggerGatt.HistoryNotification
import com.coldchain.handshake.logger.ble.LoggerGattException.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * A logger reached over Bluetooth, speaking the GATT layout in LOGGER_PROTOCOL.md. It only moves bytes: every
 * batch and head it returns still goes through `LoggerHistoryVerifier`, against the key on record.
 *
 * A transfer that goes wrong is a failure with a [LoggerGattException], never a shorter history that looks
 * complete, so a lost notification can't pass for a logger with less to say.
 *
 * @param timeoutMs How long one read, write or answer may take before it counts as failed.
 */
class BleLoggerLink(
    private val connection: GattConnection,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : LoggerLink {

    override suspend fun info(): Result<LoggerInfo> = attempt {
        val info = LoggerGatt.decodeDeviceInformation(timed { connection.read(LoggerGatt.DEVICE_INFORMATION) })
        if (info.protocolVersion != LoggerProtocol.VERSION) {
            throw LoggerGattException(Kind.UNSUPPORTED_VERSION, "the logger speaks protocol ${info.protocolVersion}")
        }
        info
    }

    override suspend fun publicKey(): Result<ByteArray> = attempt {
        LoggerGatt.decodePublicKey(timed { connection.read(LoggerGatt.PUBLIC_KEY) })
    }

    override suspend fun status(): Result<LoggerStatus> = attempt { readStatus() }

    /**
     * Reads the status first, as LOGGER_PROTOCOL.md "Reading history" says: a logger with nothing that new sends
     * no notifications at all, so asking anyway would only wait for the timeout.
     */
    override suspend fun readHistory(fromSeq: Long, maxRecords: Int): Result<LoggerBatch?> = attempt {
        if (maxRecords < 1) return@attempt null
        ensureMtu()
        if (readStatus().newestSeq < fromSeq) return@attempt null

        val request = LoggerGatt.encodeHistoryRequest(fromSeq, minOf(maxRecords, LoggerGatt.MAX_RECORDS_PER_REQUEST))
        val answers = writeThenCollect(LoggerGatt.HISTORY_REQUEST, request, LoggerGatt.HISTORY_DATA) {
            it.isNotEmpty() && it[0].toInt() == LoggerGatt.PAGE_BATCH_HEADER
        }.map(LoggerGatt::decodeHistoryNotification)

        // The answers end at the first header, so anything else last means the notifications stopped early.
        val header = answers.lastOrNull() as? HistoryNotification.BatchHeader
            ?: throw LoggerGattException(Kind.INCOMPLETE, "the logger stopped before sending its batch header")
        val records = answers.dropLast(1).flatMap { (it as HistoryNotification.Records).records }
        if (records.map { it.seq } != (header.firstSeq..header.lastSeq).toList()) {
            throw LoggerGattException(
                Kind.INCOMPLETE,
                "the header covers ${header.firstSeq} to ${header.lastSeq} but ${records.size} records arrived"
            )
        }
        LoggerBatch(header.firstSeq, header.lastSeq, header.startHash, header.endHash, records, header.signature)
    }

    override suspend fun challenge(nonce: ByteArray): Result<LoggerHead> = attempt {
        if (nonce.size != LoggerGatt.NONCE_SIZE) {
            throw LoggerGattException(Kind.MALFORMED, "a nonce is ${LoggerGatt.NONCE_SIZE} bytes, not ${nonce.size}")
        }
        // The 104-byte answer doesn't fit Bluetooth's default MTU of 23 either.
        ensureMtu()
        val answer = writeThenCollect(LoggerGatt.CHALLENGE, nonce, LoggerGatt.CHALLENGE_RESPONSE) { true }.single()
        LoggerGatt.decodeChallengeResponse(answer, nonce)
    }

    private suspend fun readStatus(): LoggerStatus = LoggerGatt.decodeStatus(timed { connection.read(LoggerGatt.STATUS) })

    private suspend fun ensureMtu() {
        if (connection.attMtu >= LoggerGatt.MIN_ATT_MTU) return
        val agreed = timed { connection.requestMtu(LoggerGatt.PREFERRED_ATT_MTU) }
        if (agreed < LoggerGatt.MIN_ATT_MTU) {
            throw LoggerGattException(
                Kind.MTU_TOO_SMALL,
                "the connection agreed an MTU of $agreed; a logger sends history only from ${LoggerGatt.MIN_ATT_MTU}"
            )
        }
    }

    /**
     * Listens on [answers] before writing [value] to [request], because notifications aren't kept for a late
     * listener, and collects up to and including the one [isLast] picks.
     */
    private suspend fun writeThenCollect(
        request: UUID,
        value: ByteArray,
        answers: UUID,
        isLast: (ByteArray) -> Boolean
    ): List<ByteArray> = coroutineScope {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            connection.notifications(answers).transformWhile { emit(it); !isLast(it) }.toList()
        }
        try {
            timed { connection.write(request, value) }
            timed { collected.await() }
        } finally {
            collected.cancel()
        }
    }

    private suspend fun <T> timed(block: suspend () -> T): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        throw LoggerGattException(Kind.TIMED_OUT, "the logger didn't answer within $timeoutMs ms")
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
