package com.coldchain.handshake.logger

import com.coldchain.handshake.logger.LoggerCheckOutcome.FAILED
import com.coldchain.handshake.logger.LoggerCheckOutcome.NOT_CHECKED
import com.coldchain.handshake.logger.LoggerCheckOutcome.PASSED
import com.coldchain.handshake.logger.LoggerCheckOutcome.UNKNOWN
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiver's check of the logger itself at handover. Each case is one way a courier, a phone or a logger could
 * misreport a journey; none of them may come out as PASSED.
 */
class LoggerHandoverCheckTest {

    private val loggerId = "LOG-HAND-1"
    private val startSeq = 3L

    /** A logger that already held 3 records from an earlier trip, then 9 from this one. */
    private fun logger(capacity: Int = VirtualLogger.DEFAULT_CAPACITY, count: Int = 12) =
        VirtualLogger(loggerId, capacity = capacity).apply {
            (1..count).forEach { record(1_700_000_000_000 + it * 60_000L, 400 + it * 3) }
        }

    /** The shipment's readings as an honest courier uploaded them: every record since custody started. */
    private fun readingsOf(logger: VirtualLogger, upTo: Long = Long.MAX_VALUE): List<TemperatureEvent> =
        runBlocking { logger.readHistory(startSeq + 1, 10_000) }.getOrThrow()!!.records
            .filter { it.seq <= upTo }
            .map { reading(it.seq - startSeq, it.loggerTs, it.tempCentiC) }

    private fun reading(seq: Long, ts: Long, centi: Int) = TemperatureEvent(
        id = "R$seq", shipmentId = "SHIP-H", loggerId = loggerId, timestamp = ts, temperature = centi / 100.0,
        previousHash = "", currentHash = "", syncStatus = SyncStatus.SYNCED, seq = seq
    )

    private val check = LoggerHandoverCheck(newNonce = { ByteArray(16) { it.toByte() } })

    private suspend fun run(
        link: LoggerLink?,
        key: ByteArray?,
        readings: List<TemperatureEvent>,
        id: String = loggerId
    ) = check.check(link, id, key, startSeq, readings)

    @Test
    fun anHonestJourneyPasses() = runTest {
        val logger = logger()
        val result = run(logger, logger.publicKeyUncompressed, readingsOf(logger))
        assertEquals(result.reasons.toString(), PASSED, result.outcome)
        assertEquals(12L, result.newestSeq)
    }

    @Test
    fun noLoggerReachableIsNotCheckedAndSaysSo() = runTest {
        val result = run(null, logger().publicKeyUncompressed, emptyList())
        assertEquals(NOT_CHECKED, result.outcome)
        assertTrue(result.reasons.single().contains("No logger"))
    }

    @Test
    fun withNoKeyOnRecordNothingCanBeVerified() = runTest {
        val logger = logger()
        assertEquals(UNKNOWN, run(logger, null, readingsOf(logger)).outcome)
    }

    @Test
    fun anEditedReadingFails() = runTest {
        val logger = logger()
        val readings = readingsOf(logger).map { if (it.seq == 4L) it.copy(temperature = 5.0) else it }
        val result = run(logger, logger.publicKeyUncompressed, readings)
        assertEquals(FAILED, result.outcome)
        assertEquals(listOf(ReadingMismatch(4, ReadingMismatchType.DIFFERENT)), result.mismatches)
    }

    @Test
    fun aReadingRemovedFromTheMiddleFails() = runTest {
        val logger = logger()
        val result = run(logger, logger.publicKeyUncompressed, readingsOf(logger).filter { it.seq != 5L })
        assertEquals(FAILED, result.outcome)
        assertEquals(listOf(ReadingMismatch(5, ReadingMismatchType.MISSING)), result.mismatches)
    }

    @Test
    fun aReadingTheLoggerNeverMadeFails() = runTest {
        val logger = logger()
        val invented = readingsOf(logger) + reading(10, 1_700_000_000_000 + 13 * 60_000L, 450)
        val result = run(logger, logger.publicKeyUncompressed, invented)
        assertEquals(FAILED, result.outcome)
        assertEquals(listOf(ReadingMismatch(10, ReadingMismatchType.NOT_FROM_LOGGER)), result.mismatches)
    }

    @Test
    fun recordsTheShipmentDoesntHoldYetAreUnknownNotPassed() = runTest {
        val logger = logger()
        val result = run(logger, logger.publicKeyUncompressed, readingsOf(logger, upTo = 10))
        assertEquals(UNKNOWN, result.outcome)
        assertEquals(
            listOf(ReadingMismatch(8, ReadingMismatchType.MISSING), ReadingMismatch(9, ReadingMismatchType.MISSING)),
            result.mismatches
        )
    }

    @Test
    fun anotherLoggersKeyFails() = runTest {
        val logger = logger()
        val result = run(logger, logger("LOG-OTHER").publicKeyUncompressed, readingsOf(logger))
        assertEquals(FAILED, result.outcome)
        assertTrue(result.issues.any { it.type == LoggerIssueType.BAD_SIGNATURE })
    }

    @Test
    fun aLoggerWithAnotherIdFails() = runTest {
        val logger = logger()
        val result = run(logger, logger.publicKeyUncompressed, readingsOf(logger), id = "LOG-SHIPMENT")
        assertEquals(FAILED, result.outcome)
    }

    // Security review S4: the ID is the device's own text, shown among the reasons on the handover screen.
    @Test
    fun aLoggerIdCanNotWriteItsOwnReasonLines() = runTest {
        val forged = "X.\nThe logger's own signed history matches the shipment's readings." + "!".repeat(400)
        val logger = VirtualLogger(forged).apply { (1..5).forEach { record(1_700_000_000_000 + it * 60_000L, 450) } }
        val result = run(logger, logger.publicKeyUncompressed, emptyList(), id = "LOG-SHIPMENT")

        assertEquals(FAILED, result.outcome)
        val reason = result.reasons.single()
        assertTrue(reason, reason.none { it.isISOControl() })
        assertTrue(reason, reason.length < 160)
    }

    @Test
    fun aHistoryEditedInTransitFails() = runTest {
        val logger = logger()
        val editing = object : LoggerLink by logger {
            override suspend fun readHistory(fromSeq: Long, maxRecords: Int) = logger.readHistory(fromSeq, maxRecords).map { batch ->
                batch?.copy(records = batch.records.map { if (it.seq == 6L) it.copy(tempCentiC = 450) else it })
            }
        }
        val result = run(editing, logger.publicKeyUncompressed, readingsOf(logger))
        assertEquals(FAILED, result.outcome)
        assertTrue(result.issues.any { it.type == LoggerIssueType.BROKEN_CHAIN })
    }

    @Test
    fun aReplayedHeadFails() = runTest {
        val logger = logger()
        val oldHead = logger.challenge(ByteArray(16) { 9 }).getOrThrow()
        val replaying = object : LoggerLink by logger {
            override suspend fun challenge(nonce: ByteArray) = Result.success(oldHead)
        }
        val result = run(replaying, logger.publicKeyUncompressed, readingsOf(logger))
        assertEquals(FAILED, result.outcome)
        assertTrue(result.issues.any { it.type == LoggerIssueType.STALE_HEAD })
    }

    @Test
    fun aHistoryCutShortOfTheHeadFails() = runTest {
        // The newest records are withheld, as if the logger had nothing after 10, but its head says 12.
        val logger = logger()
        val truncating = object : LoggerLink by logger {
            override suspend fun status() = Result.success(LoggerStatus(1, 10))
            override suspend fun readHistory(fromSeq: Long, maxRecords: Int) =
                if (fromSeq > 10) Result.success(null)
                else logger.readHistory(fromSeq, minOf(maxRecords, (11 - fromSeq).toInt()))
        }
        val result = run(truncating, logger.publicKeyUncompressed, readingsOf(logger, upTo = 10))
        assertEquals(FAILED, result.outcome)
        assertTrue(result.issues.any { it.type == LoggerIssueType.HEAD_MISMATCH })
    }

    @Test
    fun aLoggerThatCantBeReadIsUnknown() = runTest {
        val logger = logger()
        val broken = object : LoggerLink by logger {
            override suspend fun readHistory(fromSeq: Long, maxRecords: Int) =
                Result.failure<LoggerBatch?>(IllegalStateException("radio off"))
        }
        val result = run(broken, logger.publicKeyUncompressed, readingsOf(logger))
        assertEquals(UNKNOWN, result.outcome)
        assertTrue(result.reasons.single().contains("radio off"))
    }

    @Test
    fun recordsOverwrittenSinceCustodyStartedAreUnknown() = runTest {
        // A ring buffer of 6 has lost records 4 to 6, which belong to this journey.
        val logger = logger(capacity = 6)
        val readings = (4L..12L).map { reading(it - startSeq, 1_700_000_000_000 + it * 60_000L, (400 + it * 3).toInt()) }
        val result = run(logger, logger.publicKeyUncompressed, readings)
        assertEquals(UNKNOWN, result.outcome)
        assertTrue(result.issues.any { it.type == LoggerIssueType.GAP })
    }

    @Test
    fun aSensorFaultDuringTheJourneyIsUnknown() = runTest {
        val logger = logger(count = 11).apply { record(1_700_000_000_000 + 12 * 60_000L, 0, LoggerRecord.FLAG_SENSOR_FAULT) }
        val result = run(logger, logger.publicKeyUncompressed, readingsOf(logger))
        assertEquals(UNKNOWN, result.outcome)
    }

    @Test
    fun aSensorFaultBeforeCustodyStartedDoesntCount() = runTest {
        val logger = VirtualLogger(loggerId).apply {
            record(1_700_000_060_000, 0, LoggerRecord.FLAG_SENSOR_FAULT)
            (2..12).forEach { record(1_700_000_000_000 + it * 60_000L, 400 + it * 3) }
        }
        assertEquals(PASSED, run(logger, logger.publicKeyUncompressed, readingsOf(logger)).outcome)
    }

    @Test
    fun thePublishedTestKeyFailsWhateverItSigned() = runTest {
        val logger = VirtualLogger(VectorTestKey.loggerId, VectorTestKey.keyPair()).apply {
            (1..12).forEach { record(1_700_000_000_000 + it * 60_000L, 400 + it * 3) }
        }
        val readings = readingsOf(logger).map { it.copy(loggerId = VectorTestKey.loggerId) }
        val result = check.check(logger, VectorTestKey.loggerId, VectorTestKey.publicKeyUncompressed, startSeq, readings)
        assertEquals(FAILED, result.outcome)
        assertTrue(result.issues.any { it.type == LoggerIssueType.TEST_KEY })
    }

    @Test
    fun aLoggerThatNeverStopsAnsweringIsCutOff() = runTest {
        val logger = logger()
        var asked = 0
        val endless = object : LoggerLink by logger {
            override suspend fun status() = Result.success(LoggerStatus(1, Long.MAX_VALUE))
            override suspend fun readHistory(fromSeq: Long, maxRecords: Int): Result<LoggerBatch?> {
                asked++
                return logger.readHistory(4, 1)
            }
        }
        val result = LoggerHandoverCheck(maxPages = 5).check(endless, loggerId, logger.publicKeyUncompressed, startSeq, readingsOf(logger))
        assertEquals(UNKNOWN, result.outcome)
        assertEquals(5, asked)
    }

    @Test
    fun eachCheckSendsItsOwnNonce() = runTest {
        val logger = logger()
        val nonces = mutableListOf<String>()
        val recording = object : LoggerLink by logger {
            override suspend fun challenge(nonce: ByteArray): Result<LoggerHead> {
                nonces += nonce.toHex()
                return logger.challenge(nonce)
            }
        }
        val fresh = LoggerHandoverCheck()
        repeat(2) { fresh.check(recording, loggerId, logger.publicKeyUncompressed, startSeq, readingsOf(logger)) }
        assertEquals(2, nonces.toSet().size)
        assertTrue(nonces.all { it.length == 32 })
    }

    private fun logger(id: String) = VirtualLogger(id)
}
