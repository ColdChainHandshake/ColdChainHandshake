package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HashChainServiceTest {

    private fun createSampleEvent(
        id: String = "TE-001",
        shipmentId: String = "SHP-001",
        loggerId: String = "LOG-100",
        timestamp: Long = 1700000000000L,
        temperature: Double = 4.5
    ): TemperatureEvent {
        return TemperatureEvent(
            id = id,
            shipmentId = shipmentId,
            loggerId = loggerId,
            timestamp = timestamp,
            temperature = temperature,
            previousHash = "",
            currentHash = "",
            syncStatus = SyncStatus.SYNCED
        )
    }

    /**
     * TEST 1: Same event + same previousHash produces exactly the same SHA-256 hash.
     */
    @Test
    fun test1_sameEventAndPreviousHashProducesSameHash() {
        val event1 = createSampleEvent(id = "TE-001", temperature = 4.5, timestamp = 1700000000000L)
        val event2 = createSampleEvent(id = "TE-001", temperature = 4.5, timestamp = 1700000000000L)
        val previousHash = HashChainService.GENESIS_HASH

        val hash1 = HashChainService.createHash(event1, previousHash)
        val hash2 = HashChainService.createHash(event2, previousHash)

        assertEquals("Hashes must be strictly deterministic and identical", hash1, hash2)
        assertEquals("SHA-256 hex digest must be 64 characters long", 64, hash1.length)
    }

    /**
     * TEST 2: Changing temperature changes currentHash.
     */
    @Test
    fun test2_changingTemperatureChangesCurrentHash() {
        val baseEvent = createSampleEvent(temperature = 4.5)
        val modifiedEvent = baseEvent.copy(temperature = 4.6)
        val previousHash = HashChainService.GENESIS_HASH

        val hashBase = HashChainService.createHash(baseEvent, previousHash)
        val hashModified = HashChainService.createHash(modifiedEvent, previousHash)

        assertNotEquals("Altering temperature must result in a different SHA-256 digest", hashBase, hashModified)
    }

    /**
     * TEST 3: Changing timestamp changes currentHash.
     */
    @Test
    fun test3_changingTimestampChangesCurrentHash() {
        val baseEvent = createSampleEvent(timestamp = 1700000000000L)
        val modifiedEvent = baseEvent.copy(timestamp = 1700000060000L)
        val previousHash = HashChainService.GENESIS_HASH

        val hashBase = HashChainService.createHash(baseEvent, previousHash)
        val hashModified = HashChainService.createHash(modifiedEvent, previousHash)

        assertNotEquals("Altering timestamp must result in a different SHA-256 digest", hashBase, hashModified)
    }

    /**
     * TEST 4: Valid 3-event chain verifies successfully.
     */
    @Test
    fun test4_validThreeEventChainVerifiesSuccessfully() {
        val rawEvents = listOf(
            createSampleEvent(id = "TE-001", timestamp = 1700000000000L, temperature = 4.2),
            createSampleEvent(id = "TE-002", timestamp = 1700000060000L, temperature = 4.8),
            createSampleEvent(id = "TE-003", timestamp = 1700000120000L, temperature = 5.1)
        )

        val chain = HashChainService.buildChain(rawEvents)
        assertEquals(3, chain.size)

        val result = HashChainService.verifyChain(chain)
        assertTrue("Valid 3-event chain must pass verification", result.valid)
        assertNull("Valid chain should have no corrupted event ID", result.corruptedEventId)
        assertNull("Valid chain should have no failure reason", result.reason)
        assertNull("findCorruptedEvent must return null for valid chain", HashChainService.findCorruptedEvent(chain))
    }

    /**
     * TEST 5: Changing an earlier event's temperature causes verification failure.
     */
    @Test
    fun test5_changingEarlierEventTemperatureCausesVerificationFailure() {
        val rawEvents = listOf(
            createSampleEvent(id = "TE-001", timestamp = 1700000000000L, temperature = 4.0),
            createSampleEvent(id = "TE-002", timestamp = 1700000060000L, temperature = 4.5),
            createSampleEvent(id = "TE-003", timestamp = 1700000120000L, temperature = 5.0)
        )
        val chain = HashChainService.buildChain(rawEvents).toMutableList()

        // Tamper with Event 1's temperature without updating hashes
        chain[0] = chain[0].copy(temperature = 12.0)

        val result = HashChainService.verifyChain(chain)
        assertFalse("Tampered chain must fail verification", result.valid)
        assertEquals("Offending event must be identified as TE-001", "TE-001", result.corruptedEventId)
        assertEquals(
            "Reason must be CURRENT_HASH_MISMATCH since stored hash does not match tampered content",
            IntegrityFailureReason.CURRENT_HASH_MISMATCH,
            result.reason
        )
    }

    /**
     * TEST 6: Changing currentHash causes verification failure.
     */
    @Test
    fun test6_changingCurrentHashCausesVerificationFailure() {
        val rawEvents = listOf(
            createSampleEvent(id = "TE-001", timestamp = 1700000000000L, temperature = 4.0),
            createSampleEvent(id = "TE-002", timestamp = 1700000060000L, temperature = 4.5)
        )
        val chain = HashChainService.buildChain(rawEvents).toMutableList()

        // Invalidate currentHash of event 2
        chain[1] = chain[1].copy(currentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

        val result = HashChainService.verifyChain(chain)
        assertFalse("Chain with altered currentHash must fail verification", result.valid)
        assertEquals("TE-002", result.corruptedEventId)
        assertEquals(IntegrityFailureReason.CURRENT_HASH_MISMATCH, result.reason)
    }

    /**
     * TEST 7: Changing previousHash causes verification failure.
     */
    @Test
    fun test7_changingPreviousHashCausesVerificationFailure() {
        val rawEvents = listOf(
            createSampleEvent(id = "TE-001", timestamp = 1700000000000L, temperature = 4.0),
            createSampleEvent(id = "TE-002", timestamp = 1700000060000L, temperature = 4.5)
        )
        val chain = HashChainService.buildChain(rawEvents).toMutableList()

        // Invalidate previousHash of event 2
        chain[1] = chain[1].copy(previousHash = "bogus_previous_hash_value")

        val result = HashChainService.verifyChain(chain)
        assertFalse("Chain with altered previousHash must fail verification", result.valid)
        assertEquals("TE-002", result.corruptedEventId)
        assertEquals(IntegrityFailureReason.PREVIOUS_HASH_MISMATCH, result.reason)
    }

    /**
     * TEST 8: Breaking the event order causes verification failure if ordering is part of the contract.
     */
    @Test
    fun test8_breakingEventOrderCausesVerificationFailure() {
        val rawEvents = listOf(
            createSampleEvent(id = "TE-001", timestamp = 1700000000000L, temperature = 4.0),
            createSampleEvent(id = "TE-002", timestamp = 1700000060000L, temperature = 4.5),
            createSampleEvent(id = "TE-003", timestamp = 1700000120000L, temperature = 5.0)
        )
        val chain = HashChainService.buildChain(rawEvents)

        // Reverse the order (TE-003, TE-002, TE-001)
        val reversedChain = chain.reversed()

        val result = HashChainService.verifyChain(reversedChain)
        assertFalse("Out-of-order events must fail verification", result.valid)
        assertEquals(IntegrityFailureReason.ORDER_VIOLATION, result.reason)
    }

    /**
     * TEST 16: Corruption demo actually causes verifyChain() to fail.
     */
    @Test
    fun test16_corruptionDemoActuallyCausesVerifyChainToFail() {
        val rawEvents = listOf(
            createSampleEvent(id = "TE-001", timestamp = 1700000000000L, temperature = 4.2),
            createSampleEvent(id = "TE-002", timestamp = 1700000060000L, temperature = 4.6),
            createSampleEvent(id = "TE-003", timestamp = 1700000120000L, temperature = 5.0)
        )
        val pristineChain = HashChainService.buildChain(rawEvents)

        // 1. Pristine chain verification passes
        val beforeResult = HashChainService.verifyChain(pristineChain)
        assertTrue("Before corruption: hash chain must be verified", beforeResult.valid)

        // 2. Simulate corruption on a copy of TE-002
        val tamperedChain = pristineChain.toMutableList()
        tamperedChain[1] = tamperedChain[1].copy(temperature = 15.0) // Tamper temperature to 15.0°C

        // 3. Verify corrupted chain
        val afterResult = HashChainService.verifyChain(tamperedChain)
        assertFalse("After corruption: integrity verification must fail", afterResult.valid)
        assertEquals("TE-002", afterResult.corruptedEventId)
        assertEquals(IntegrityFailureReason.CURRENT_HASH_MISMATCH, afterResult.reason)

        val corruptedEvent = HashChainService.findCorruptedEvent(tamperedChain)
        assertNotNull("Corrupted event must be located", corruptedEvent)
        assertEquals("TE-002", corruptedEvent?.id)
    }

    @Test
    fun testEmptyChainReturnsEmptyChainFailure() {
        val result = HashChainService.verifyChain(emptyList())
        assertFalse(result.valid)
        assertEquals(IntegrityFailureReason.EMPTY_CHAIN, result.reason)
    }

    @Test
    fun testInvalidGenesisFails() {
        val event = createSampleEvent(id = "TE-001")
        val hashed = HashChainService.appendHash(event, "INVALID_GENESIS")
        val result = HashChainService.verifyChain(listOf(hashed))
        assertFalse(result.valid)
        assertEquals(IntegrityFailureReason.INVALID_GENESIS, result.reason)
    }

    @Test
    fun testHashLengthIsExactly64AndLowercaseHexadecimal() {
        val event = createSampleEvent()
        val hash = HashChainService.createHash(event, HashChainService.GENESIS_HASH)
        assertEquals("Hash must be exactly 64 characters long", 64, hash.length)
        assertTrue("Hash must only contain lowercase hexadecimal characters", hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun testTamperingPropagationAcrossChain() {
        val rawEvents = listOf(
            createSampleEvent(id = "TE-001", timestamp = 1700000000000L, temperature = 4.0),
            createSampleEvent(id = "TE-002", timestamp = 1700000060000L, temperature = 4.5),
            createSampleEvent(id = "TE-003", timestamp = 1700000120000L, temperature = 5.0)
        )
        val chain = HashChainService.buildChain(rawEvents)

        // Case A: Event 1's temperature is modified without recomputing hashes
        val tamperedChain1 = chain.toMutableList()
        tamperedChain1[0] = tamperedChain1[0].copy(temperature = 8.5)
        val result1 = HashChainService.verifyChain(tamperedChain1)
        assertFalse(result1.valid)
        assertEquals("TE-001", result1.corruptedEventId)
        assertEquals(IntegrityFailureReason.CURRENT_HASH_MISMATCH, result1.reason)

        // Case B: Attacker recalculates Event 1's currentHash to hide temperature modification,
        // but does not recalculate Event 2's previousHash:
        val tamperedChain2 = chain.toMutableList()
        val recomputedEvent1 = HashChainService.appendHash(chain[0].copy(temperature = 8.5), HashChainService.GENESIS_HASH)
        tamperedChain2[0] = recomputedEvent1
        val result2 = HashChainService.verifyChain(tamperedChain2)
        assertFalse(result2.valid)
        assertEquals("TE-002", result2.corruptedEventId)
        assertEquals(IntegrityFailureReason.PREVIOUS_HASH_MISMATCH, result2.reason)
    }
}
