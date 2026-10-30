package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainIntegrityTest {

    private val genesis = HashChainService.genesisHash(
        ShipmentManifest("SHIP-1", "sender", "profile", "LOG-1", 1_700_000_000_000L)
    )

    private fun raw(count: Int, temperature: Double = 5.0, shipmentId: String = "SHIP-1") =
        (1..count).map { index ->
            TemperatureEvent(
                id = "TE-$index",
                shipmentId = shipmentId,
                loggerId = "LOG-1",
                timestamp = 1_700_000_000_000L + index * 60_000L,
                temperature = temperature,
                previousHash = "",
                currentHash = "",
                syncStatus = SyncStatus.PENDING
            )
        }

    private fun chain(count: Int) = HashChainService.buildChain(raw(count), genesis)

    private fun head(events: List<TemperatureEvent>) = ServerChainHead(events.last().currentHash, events.size.toLong())

    private fun evaluate(
        events: List<TemperatureEvent>,
        server: ServerChainHead? = null,
        syncComplete: Boolean = true
    ) = ShipmentIntegrityEvaluator.evaluate(events, syncComplete, genesis, server)

    // Chain

    @Test
    fun aBuiltChainNumbersItsReadingsFromOne() {
        assertEquals(listOf(1L, 2L, 3L), chain(3).map { it.seq })
    }

    @Test
    fun aBuiltChainStartsAtTheShipmentsGenesis() {
        assertEquals(genesis, chain(3).first().previousHash)
    }

    @Test
    fun aValidChainVerifiesAgainstItsGenesis() {
        assertTrue(HashChainService.verifyChain(chain(3), genesis).valid)
    }

    @Test
    fun aChainDoesNotVerifyAgainstAnotherShipmentsGenesis() {
        val other = HashChainService.genesisHash(ShipmentManifest("SHIP-2", "sender", "profile", "LOG-1", 1L))

        val result = HashChainService.verifyChain(chain(3), other)

        assertFalse(result.valid)
        assertEquals(IntegrityFailureReason.INVALID_GENESIS, result.reason)
    }

    @Test
    fun readingsCannotBeMovedToAnotherShipment() {
        val moved = chain(3).map { it.copy(shipmentId = "SHIP-2") }

        assertFalse(HashChainService.verifyChain(moved, genesis).valid)
    }

    @Test
    fun aGapInTheMiddleIsNotAValidChain() {
        val withGap = chain(4).filterNot { it.seq == 2L }

        val result = HashChainService.verifyChain(withGap, genesis)

        assertFalse(result.valid)
        assertEquals(IntegrityFailureReason.SEQUENCE_GAP, result.reason)
    }

    @Test
    fun twoReadingsWithTheSameTimeAreNotAValidChain() {
        val duplicated = HashChainService.buildChain(
            raw(2).map { it.copy(timestamp = 1_700_000_000_000L) },
            genesis
        )

        val result = HashChainService.verifyChain(duplicated, genesis)

        assertFalse(result.valid)
        assertEquals(IntegrityFailureReason.ORDER_VIOLATION, result.reason)
    }

    @Test
    fun aChangedTemperatureBreaksTheChain() {
        val edited = chain(3).mapIndexed { index, event -> if (index == 1) event.copy(temperature = 4.0) else event }

        assertEquals(IntegrityFailureReason.CURRENT_HASH_MISMATCH, HashChainService.verifyChain(edited, genesis).reason)
    }

    @Test
    fun theHashCoversTheWholeHundredthOfADegree() {
        val base = HashChainService.appendHash(raw(1).single().copy(seq = 1L), genesis)
        val nudged = HashChainService.appendHash(raw(1).single().copy(seq = 1L, temperature = 5.01), genesis)

        assertNotEquals(base.currentHash, nudged.currentHash)
    }

    @Test
    fun aTemperatureSurvivesTheRoundTripToHundredths() {
        (-5000..5000).forEach { centi ->
            val event = raw(1).single().copy(temperature = centi / 100.0)
            assertEquals(centi, event.tempCentiC)
        }
    }

    // Against the server

    @Test
    fun aChainMatchingTheServerIsVerified() {
        val events = chain(5)

        assertEquals(IntegrityVerificationState.VERIFIED, evaluate(events, head(events)).state)
    }

    @Test
    fun aChainWithTheTailRemovedIsTampered() {
        val full = chain(14)
        val truncated = full.take(6)

        val result = evaluate(truncated, head(full))

        assertEquals(IntegrityVerificationState.TAMPER_DETECTED, result.state)
        assertFalse(result.isEligibleForHandover)
    }

    @Test
    fun aRewrittenChainIsTamperedEvenThoughItsOwnHashesAreValid() {
        val real = HashChainService.buildChain(raw(8, temperature = 10.5), genesis)
        val forged = HashChainService.buildChain(raw(8, temperature = 5.0), genesis)

        assertTrue("the forgery is internally consistent", HashChainService.verifyChain(forged, genesis).valid)
        assertEquals(IntegrityVerificationState.TAMPER_DETECTED, evaluate(forged, head(real)).state)
    }

    @Test
    fun aChainStillDownloadingIsNotTampered() {
        val full = chain(14)

        val result = evaluate(full.take(6), head(full), syncComplete = false)

        assertEquals(IntegrityVerificationState.PENDING_SYNC, result.state)
        assertFalse(result.isEligibleForHandover)
    }

    @Test
    fun readingsNotYetUploadedAreNotTampering() {
        val events = chain(8)
        val uploaded = events.take(5)

        assertEquals(IntegrityVerificationState.VERIFIED, evaluate(events, head(uploaded)).state)
    }

    @Test
    fun aChainThatDiffersBeforeTheNewestUploadIsTampered() {
        val real = chain(5)
        val forged = HashChainService.buildChain(raw(8, temperature = 4.0), genesis)

        assertEquals(IntegrityVerificationState.TAMPER_DETECTED, evaluate(forged, head(real)).state)
    }

    @Test
    fun noReadingsWhenTheServerHasSomeIsTamperedOnceSyncHasFinished() {
        val full = chain(3)

        assertEquals(IntegrityVerificationState.TAMPER_DETECTED, evaluate(emptyList(), head(full)).state)
        assertEquals(
            IntegrityVerificationState.PENDING_SYNC,
            evaluate(emptyList(), head(full), syncComplete = false).state
        )
    }

    @Test
    fun anEditShowsEvenWhileTheHistoryIsDownloading() {
        val edited = chain(3).mapIndexed { index, event -> if (index == 1) event.copy(temperature = 4.0) else event }

        assertEquals(IntegrityVerificationState.TAMPER_DETECTED, evaluate(edited, syncComplete = false).state)
    }

    @Test
    fun aMissingStartIsPendingWhileSyncingAndTamperedOnceFinished() {
        val suffix = chain(5).drop(2)

        assertEquals(IntegrityVerificationState.PENDING_SYNC, evaluate(suffix, syncComplete = false).state)
        assertEquals(IntegrityVerificationState.TAMPER_DETECTED, evaluate(suffix, syncComplete = true).state)
    }

    @Test
    fun aMismatchedServerRecordIsReportedWithoutOverclaiming() {
        val full = chain(14)

        val result = evaluate(full.take(6), head(full))

        assertFalse(result.message.contains("immutable", ignoreCase = true))
        assertFalse(result.title.contains("SHA", ignoreCase = true))
    }
}
