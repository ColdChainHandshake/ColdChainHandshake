package com.coldchain.handshake.logger

data class LoggerInfo(
    val loggerId: String,
    val protocolVersion: Int,
    val firmwareVersion: String
)

/**
 * @property oldestSeq The oldest record still kept. Anything before it has been overwritten.
 */
data class LoggerStatus(
    val oldestSeq: Long,
    val newestSeq: Long
)

/**
 * A range of records as the logger signed it. Hashes and the raw `r || s` signature are hex.
 */
data class LoggerBatch(
    val firstSeq: Long,
    val lastSeq: Long,
    val startHash: String,
    val endHash: String,
    val records: List<LoggerRecord>,
    val signature: String
)

/**
 * True when [records] are exactly [firstSeq], [firstSeq] + 1, … [lastSeq], in order. The range comes from the
 * device, so it is checked record by record and nothing is built in its size (security review S1: a header
 * claiming `0..Long.MAX_VALUE` ran the phone out of memory). No records fits only a range with nothing in it.
 */
internal fun recordsRunExactly(records: List<LoggerRecord>, firstSeq: Long, lastSeq: Long): Boolean {
    if (records.isEmpty()) return lastSeq < firstSeq
    if (records.first().seq != firstSeq || records.last().seq != lastSeq) return false
    return records.zipWithNext().all { (a, b) -> a.seq != Long.MAX_VALUE && b.seq == a.seq + 1 }
}

/**
 * The logger's newest record, signed with the reader's nonce, which shows it is present now.
 */
data class LoggerHead(
    val headSeq: Long,
    val headHash: String,
    val nonce: String,
    val signature: String
)

/**
 * A logger as the app talks to it, over Bluetooth or to the virtual logger, so both run the same checks.
 */
interface LoggerLink {
    suspend fun info(): Result<LoggerInfo>

    /** The 65-byte uncompressed point. Never trust it on its own: compare it with the key on record. */
    suspend fun publicKey(): Result<ByteArray>
    suspend fun status(): Result<LoggerStatus>

    /**
     * @return up to [maxRecords] records from [fromSeq], or the oldest kept if that is later, or null if
     *   there is nothing that new.
     */
    suspend fun readHistory(fromSeq: Long, maxRecords: Int): Result<LoggerBatch?>
    suspend fun challenge(nonce: ByteArray): Result<LoggerHead>
}
