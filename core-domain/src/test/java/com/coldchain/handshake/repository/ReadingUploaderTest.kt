package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingUploaderTest {

    private val server = FakeColdChainServer()
    private val genesis = server.seedShipment("SHIP-1")

    private val signer = object : DeviceSigner {
        val signed = mutableListOf<ByteArray>()
        override val publicKey = "public-key"
        override fun sign(payload: ByteArray): String {
            signed += payload
            return "signature-${signed.size}"
        }
    }

    private fun uploader(maxBatchSize: Int = 500) = ReadingUploader(server, signer, { "device-1" }, maxBatchSize)

    private fun chain(count: Int): List<TemperatureEvent> = HashChainService.buildChain(
        (1..count).map { index ->
            TemperatureEvent(
                id = "TE-$index",
                shipmentId = "SHIP-1",
                loggerId = "LOG-1",
                timestamp = 1_700_000_000_000L + index * 60_000L,
                temperature = 5.0,
                previousHash = "",
                currentHash = "",
                syncStatus = SyncStatus.PENDING
            )
        },
        genesis
    )

    @Test
    fun readingsReachTheServerAndTheHeadMoves() = runBlocking {
        val events = chain(3)

        val result = uploader().upload("SHIP-1", events).getOrThrow()

        assertEquals(events.map { it.id }, result.uploadedIds)
        assertEquals(3L, result.head.count)
        assertEquals(events.last().currentHash, result.head.headHash)
        assertEquals(3, server.readings.getValue("SHIP-1").size)
    }

    @Test
    fun readingsGoInSequenceOrderWhateverOrderTheyWereQueuedIn() = runBlocking {
        val result = uploader().upload("SHIP-1", chain(4).reversed()).getOrThrow()

        assertEquals(4L, result.head.count)
    }

    @Test
    fun eachBatchIsSigned() = runBlocking {
        uploader().upload("SHIP-1", chain(2)).getOrThrow()

        assertEquals(listOf("signature-1"), server.signatures)
    }

    @Test
    fun aLongQueueGoesInBatches() = runBlocking {
        val result = uploader(maxBatchSize = 2).upload("SHIP-1", chain(5)).getOrThrow()

        assertEquals(5L, result.head.count)
        assertEquals(3, server.appendCalls)
    }

    @Test
    fun aRetryAfterALostReplySendsNothingTwice() = runBlocking {
        val events = chain(3)
        server.loseNextAppendReply = true
        assertTrue(uploader().upload("SHIP-1", events).isFailure)

        val result = uploader().upload("SHIP-1", events).getOrThrow()

        assertEquals(events.map { it.id }, result.uploadedIds)
        assertEquals(3, server.readings.getValue("SHIP-1").size)
        assertEquals(1, server.appendCalls)
    }

    @Test
    fun aRetryPicksUpFromWhereTheServerIs() = runBlocking {
        val events = chain(5)
        uploader().upload("SHIP-1", events.take(2)).getOrThrow()

        val result = uploader().upload("SHIP-1", events).getOrThrow()

        assertEquals(5L, result.head.count)
    }

    @Test
    fun aLocalHistoryThatDiffersFromTheServerIsNotUploaded() = runBlocking {
        uploader().upload("SHIP-1", chain(3)).getOrThrow()
        val different = chain(3).map { it.copy(currentHash = "f".repeat(64)) }

        val result = uploader().upload("SHIP-1", different)

        assertTrue(result.isFailure)
        assertEquals(1, server.appendCalls)
    }

    @Test
    fun anOlderReadingThatDiffersFromTheServerIsNotMarkedAsUploaded() = runBlocking {
        val events = chain(3)
        uploader().upload("SHIP-1", events).getOrThrow()
        val edited = events.map { if (it.seq == 2L) it.copy(currentHash = "f".repeat(64)) else it }

        val failure = uploader().upload("SHIP-1", edited).exceptionOrNull()

        assertEquals(2L, (failure as HistoryConflict).seq)
    }

    @Test
    fun aReadingTheServerDoesNotActuallyHoldIsNotMarkedAsUploaded() = runBlocking {
        val events = chain(3)
        uploader().upload("SHIP-1", events).getOrThrow()
        server.readings.getValue("SHIP-1").removeAll { it.seq == 2L }

        val failure = uploader().upload("SHIP-1", events).exceptionOrNull()

        assertEquals(2L, (failure as HistoryConflict).seq)
    }

    @Test
    fun aFailedComparisonWithTheServerReportsNothingAsUploaded() = runBlocking {
        val events = chain(3)
        uploader().upload("SHIP-1", events).getOrThrow()
        server.failReads = IllegalStateException("Connection dropped")

        assertTrue(uploader().upload("SHIP-1", events).isFailure)
    }

    @Test
    fun aSequenceBelowOneIsRefusedAndNeverSigned() = runBlocking {
        val invalid = chain(1).single().copy(seq = 0L)

        assertTrue(uploader().upload("SHIP-1", listOf(invalid)).isFailure)
        assertTrue(signer.signed.isEmpty())
    }

    @Test
    fun aReadingThatDoesNotFollowTheServersHeadIsNeverSigned() = runBlocking {
        val detached = chain(2).drop(1)

        val failure = uploader().upload("SHIP-1", detached).exceptionOrNull()

        assertEquals(2L, (failure as HistoryConflict).seq)
        assertTrue(signer.signed.isEmpty())
        assertEquals(0, server.appendCalls)
    }

    @Test
    fun aMissingReadingBetweenTheHeadAndTheQueueIsNeverSigned() = runBlocking {
        val events = chain(4)
        uploader().upload("SHIP-1", events.take(1)).getOrThrow()
        signer.signed.clear()

        val failure = uploader().upload("SHIP-1", events.drop(2)).exceptionOrNull()

        assertEquals(3L, (failure as HistoryConflict).seq)
        assertTrue(signer.signed.isEmpty())
    }

    @Test
    fun losingCustodyStopsTheUpload() = runBlocking {
        server.custody["SHIP-1"] = CustodyState(2, "someone-else")

        val failure = uploader().upload("SHIP-1", chain(2)).exceptionOrNull()

        assertEquals(RemoteRejection.NOT_ALLOWED, (failure as RemoteRejection).code)
        assertTrue(server.readings.getValue("SHIP-1").isEmpty())
    }

    @Test
    fun anEditedReadingIsRefused() = runBlocking {
        val edited = chain(2).mapIndexed { index, event -> if (index == 1) event.copy(temperature = 4.0) else event }

        val failure = uploader().upload("SHIP-1", edited).exceptionOrNull()

        assertEquals(RemoteRejection.HASH, (failure as RemoteRejection).code)
    }

    @Test
    fun beingOfflineFailsWithoutLosingAnything() = runBlocking {
        server.offlineError = IllegalStateException("No network")

        assertFalse(uploader().upload("SHIP-1", chain(2)).isSuccess)
        assertTrue(server.readings.getValue("SHIP-1").isEmpty())
    }

    @Test
    fun anUnknownShipmentIsAFailureNotACrash() = runBlocking {
        assertTrue(uploader().upload("SHIP-X", chain(1)).isFailure)
    }
}
