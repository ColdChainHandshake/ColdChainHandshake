package com.coldchain.handshake.logger

import com.coldchain.handshake.models.TemperatureEvent

enum class LoggerIssueType {
    BAD_SIGNATURE,

    /** Records are missing, edited, repeated or out of order inside a batch, or batches don't join. */
    BROKEN_CHAIN,

    /** Records the reader asked for never arrived, for example because the logger overwrote them. */
    GAP,
    UNKNOWN_FLAGS,

    /** The head wasn't signed over this reader's nonce, so it may be a replay. */
    STALE_HEAD,

    /** The head doesn't match the newest record handed over, so the history stops short. */
    HEAD_MISMATCH,

    /** The key is the published test key, so anyone could have signed this. Nothing else is checked. */
    TEST_KEY
}

data class LoggerIssue(val seq: Long?, val type: LoggerIssueType)

/**
 * @property records Only the records that passed every check.
 * @property faults Records where the logger itself reported a sensor fault. They are genuine, but
 *   their temperature isn't a reading.
 */
data class LoggerVerification(
    val records: List<LoggerRecord>,
    val issues: List<LoggerIssue>,
    val faults: List<Long>
) {
    val trusted: Boolean get() = issues.isEmpty()
}

enum class ReadingMismatchType {
    /** The logger recorded it, but the shipment's readings don't have it: removed or never sent. */
    MISSING,

    /** Both have it, with a different time or temperature. */
    DIFFERENT,

    /** The shipment has a reading the logger never made. */
    NOT_FROM_LOGGER
}

data class ReadingMismatch(val shipmentSeq: Long, val type: ReadingMismatchType)

/**
 * Checks a logger's history against its own key, then against what the phone or server holds. The key
 * must come from the shipment's record, never from the logger being checked.
 */
object LoggerHistoryVerifier {

    /**
     * @param fromSeq The first record the reader asked for. From 1, the first batch must start at the
     *   logger's genesis.
     * @param nonce The nonce this reader sent, which [head] must be signed over.
     */
    fun verify(
        loggerId: String,
        publicKey: ByteArray,
        batches: List<LoggerBatch>,
        fromSeq: Long = 1,
        head: LoggerHead? = null,
        nonce: ByteArray? = null
    ): LoggerVerification =
        if (TestLoggerKey.matches(publicKey)) {
            LoggerVerification(emptyList(), listOf(LoggerIssue(null, LoggerIssueType.TEST_KEY)), emptyList())
        } else {
            verifyAnyKey(loggerId, publicKey, batches, fromSeq, head, nonce)
        }

    /**
     * [verify] without refusing the published test key. `internal`, so the app can't reach it: it exists for this
     * module's own tests against the vectors, which are signed with that key.
     */
    internal fun verifyAnyKey(
        loggerId: String,
        publicKey: ByteArray,
        batches: List<LoggerBatch>,
        fromSeq: Long = 1,
        head: LoggerHead? = null,
        nonce: ByteArray? = null
    ): LoggerVerification {
        val issues = mutableListOf<LoggerIssue>()
        val verified = mutableListOf<LoggerRecord>()
        var expectedSeq = fromSeq
        var expectedStart: String? = if (fromSeq == 1L) LoggerProtocol.genesis(loggerId).toHex() else null

        for (batch in batches.sortedBy { it.firstSeq }) {
            when {
                batch.firstSeq > expectedSeq -> issues += LoggerIssue(expectedSeq, LoggerIssueType.GAP)
                batch.firstSeq < expectedSeq -> issues += LoggerIssue(batch.firstSeq, LoggerIssueType.BROKEN_CHAIN)
                expectedStart != null && batch.startHash != expectedStart ->
                    issues += LoggerIssue(batch.firstSeq, LoggerIssueType.BROKEN_CHAIN)
            }

            val signed = LoggerProtocol.verify(
                publicKey,
                LoggerProtocol.batchPayload(
                    loggerId, batch.firstSeq, batch.lastSeq, batch.startHash.hexToBytes(), batch.endHash.hexToBytes()
                ),
                batch.signature.hexToBytes()
            )
            val joined = recordsRunExactly(batch.records, batch.firstSeq, batch.lastSeq) &&
                batch.records.fold(batch.startHash.hexToBytes()) { hash, record -> LoggerProtocol.chain(hash, record) }
                    .toHex() == batch.endHash

            if (!signed) issues += LoggerIssue(batch.firstSeq, LoggerIssueType.BAD_SIGNATURE)
            if (!joined) issues += LoggerIssue(batch.firstSeq, LoggerIssueType.BROKEN_CHAIN)
            if (signed && joined) {
                verified += batch.records
                batch.records.filter { it.flags and LoggerRecord.KNOWN_FLAGS.inv() != 0 }
                    .forEach { issues += LoggerIssue(it.seq, LoggerIssueType.UNKNOWN_FLAGS) }
            }

            expectedSeq = batch.lastSeq + 1
            expectedStart = batch.endHash
        }

        if (head != null) {
            if (nonce == null || head.nonce != nonce.toHex()) {
                issues += LoggerIssue(head.headSeq, LoggerIssueType.STALE_HEAD)
            }
            val signed = LoggerProtocol.verify(
                publicKey,
                LoggerProtocol.headPayload(loggerId, head.headSeq, head.headHash.hexToBytes(), head.nonce.hexToBytes()),
                head.signature.hexToBytes()
            )
            if (!signed) issues += LoggerIssue(head.headSeq, LoggerIssueType.BAD_SIGNATURE)
            if (head.headSeq != expectedSeq - 1 || (expectedStart != null && head.headHash != expectedStart)) {
                issues += LoggerIssue(head.headSeq, LoggerIssueType.HEAD_MISMATCH)
            }
        }

        return LoggerVerification(verified, issues, verified.filter { it.sensorFault }.map { it.seq })
    }

    /**
     * Compares verified logger records with a shipment's readings. Shipment reading `n` is logger record
     * `startSeq + n`.
     *
     * @param upToSeq The last logger record that should have reached the shipment, such as the head at
     *   release. Later records belong to whoever has the logger next.
     */
    fun compare(
        records: List<LoggerRecord>,
        readings: List<TemperatureEvent>,
        startSeq: Long,
        upToSeq: Long = records.maxOfOrNull { it.seq } ?: startSeq
    ): List<ReadingMismatch> {
        val bySeq = records.associateBy { it.seq }
        val mismatches = mutableListOf<ReadingMismatch>()

        for (reading in readings) {
            val record = bySeq[startSeq + reading.seq]
            when {
                record == null -> mismatches += ReadingMismatch(reading.seq, ReadingMismatchType.NOT_FROM_LOGGER)
                record.loggerTs != reading.timestamp || record.tempCentiC != reading.tempCentiC ->
                    mismatches += ReadingMismatch(reading.seq, ReadingMismatchType.DIFFERENT)
            }
        }

        val held = readings.map { it.seq }.toSet()
        records.filter { it.seq in (startSeq + 1)..upToSeq && (it.seq - startSeq) !in held }
            .forEach { mismatches += ReadingMismatch(it.seq - startSeq, ReadingMismatchType.MISSING) }

        return mismatches.sortedBy { it.shipmentSeq }
    }
}
