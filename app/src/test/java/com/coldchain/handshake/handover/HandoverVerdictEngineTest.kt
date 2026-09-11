package com.coldchain.handshake.handover

import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverVerdictEngineTest {

    /**
     * TEST 9:
     * temperaturePassed = true, integrityPassed = true, workerSigned = true, pharmacistSigned = true
     * Expected: PASS, ACCEPTED, no failure reasons
     */
    @Test
    fun test9_allConditionsMetYieldsPassAndAccepted() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertEquals("Verdict must be PASS", HandoverVerdict.PASS, result.verdict)
        assertEquals("Target status must be ACCEPTED", ShipmentStatus.ACCEPTED, result.targetStatus)
        assertTrue("Failure reasons list must be empty", result.failureReasons.isEmpty())
    }

    /**
     * TEST 10:
     * temperaturePassed = false, others = true
     * Expected: FAIL, QUARANTINED, TEMPERATURE_FAILED
     */
    @Test
    fun test10_temperatureFailYieldsQuarantined() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = false,
            integrityPassed = true,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertEquals("Verdict must be FAIL", HandoverVerdict.FAIL, result.verdict)
        assertEquals("Target status must be QUARANTINED", ShipmentStatus.QUARANTINED, result.targetStatus)
        assertEquals("Exactly one failure reason expected", 1, result.failureReasons.size)
        assertEquals(HandoverFailureReason.TEMPERATURE_FAILED, result.failureReasons[0])
    }

    /**
     * TEST 11:
     * integrityPassed = false, others = true
     * Expected: FAIL, QUARANTINED, INTEGRITY_FAILED
     */
    @Test
    fun test11_integrityFailYieldsQuarantined() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = false,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertEquals("Verdict must be FAIL", HandoverVerdict.FAIL, result.verdict)
        assertEquals("Target status must be QUARANTINED", ShipmentStatus.QUARANTINED, result.targetStatus)
        assertEquals("Exactly one failure reason expected", 1, result.failureReasons.size)
        assertEquals(HandoverFailureReason.INTEGRITY_FAILED, result.failureReasons[0])
    }

    /**
     * TEST 12:
     * workerSigned = false, others = true
     * Expected: FAIL, QUARANTINED, WORKER_SIGNATURE_MISSING
     */
    @Test
    fun test12_workerMissingSignatureYieldsFail() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = true,
            workerSigned = false,
            pharmacistSigned = true
        )

        assertEquals("Verdict must be FAIL", HandoverVerdict.FAIL, result.verdict)
        assertEquals("Target status must be QUARANTINED", ShipmentStatus.QUARANTINED, result.targetStatus)
        assertEquals("Exactly one failure reason expected", 1, result.failureReasons.size)
        assertEquals(HandoverFailureReason.WORKER_SIGNATURE_MISSING, result.failureReasons[0])
    }

    /**
     * TEST 13:
     * pharmacistSigned = false, others = true
     * Expected: FAIL, QUARANTINED, PHARMACIST_SIGNATURE_MISSING
     */
    @Test
    fun test13_pharmacistMissingSignatureYieldsFail() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = true,
            workerSigned = true,
            pharmacistSigned = false
        )

        assertEquals("Verdict must be FAIL", HandoverVerdict.FAIL, result.verdict)
        assertEquals("Target status must be QUARANTINED", ShipmentStatus.QUARANTINED, result.targetStatus)
        assertEquals("Exactly one failure reason expected", 1, result.failureReasons.size)
        assertEquals(HandoverFailureReason.PHARMACIST_SIGNATURE_MISSING, result.failureReasons[0])
    }

    /**
     * TEST 14:
     * both signatures false, temperature/integrity true
     * Expected: FAIL, QUARANTINED, both signature failure reasons
     */
    @Test
    fun test14_bothSignaturesMissingYieldsFailWithTwoReasons() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = true,
            integrityPassed = true,
            workerSigned = false,
            pharmacistSigned = false
        )

        assertEquals("Verdict must be FAIL", HandoverVerdict.FAIL, result.verdict)
        assertEquals("Target status must be QUARANTINED", ShipmentStatus.QUARANTINED, result.targetStatus)
        assertEquals("Exactly two failure reasons expected", 2, result.failureReasons.size)
        assertTrue(result.failureReasons.contains(HandoverFailureReason.WORKER_SIGNATURE_MISSING))
        assertTrue(result.failureReasons.contains(HandoverFailureReason.PHARMACIST_SIGNATURE_MISSING))
    }

    /**
     * TEST A:
     * all four false
     * Expected: FAIL, QUARANTINED, exactly 4 applicable failure reasons
     */
    @Test
    fun testA_allFourFalseYieldsFailWithAllFourReasons() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = false,
            integrityPassed = false,
            workerSigned = false,
            pharmacistSigned = false
        )

        assertEquals("Verdict must be FAIL", HandoverVerdict.FAIL, result.verdict)
        assertEquals("Target status must be QUARANTINED", ShipmentStatus.QUARANTINED, result.targetStatus)
        assertEquals("All 4 failure reasons expected", 4, result.failureReasons.size)
        assertEquals(
            listOf(
                HandoverFailureReason.TEMPERATURE_FAILED,
                HandoverFailureReason.INTEGRITY_FAILED,
                HandoverFailureReason.WORKER_SIGNATURE_MISSING,
                HandoverFailureReason.PHARMACIST_SIGNATURE_MISSING
            ),
            result.failureReasons
        )
    }

    /**
     * TEST B:
     * temperature + integrity false, signatures true
     * Expected: two corresponding failure reasons
     */
    @Test
    fun testB_temperatureAndIntegrityFalseSignaturesTrue() {
        val result = HandoverVerdictEngine.evaluate(
            temperaturePassed = false,
            integrityPassed = false,
            workerSigned = true,
            pharmacistSigned = true
        )

        assertEquals("Verdict must be FAIL", HandoverVerdict.FAIL, result.verdict)
        assertEquals("Target status must be QUARANTINED", ShipmentStatus.QUARANTINED, result.targetStatus)
        assertEquals("Exactly 2 failure reasons expected", 2, result.failureReasons.size)
        assertEquals(
            listOf(
                HandoverFailureReason.TEMPERATURE_FAILED,
                HandoverFailureReason.INTEGRITY_FAILED
            ),
            result.failureReasons
        )
    }

    /**
     * TEST C:
     * repeated calls with identical inputs produce identical results
     */
    @Test
    fun testC_repeatedCallsProduceIdenticalResults() {
        val result1 = HandoverVerdictEngine.evaluate(
            temperaturePassed = false,
            integrityPassed = true,
            workerSigned = false,
            pharmacistSigned = true
        )
        val result2 = HandoverVerdictEngine.evaluate(
            temperaturePassed = false,
            integrityPassed = true,
            workerSigned = false,
            pharmacistSigned = true
        )

        assertEquals(result1, result2)
        assertEquals(result1.verdict, result2.verdict)
        assertEquals(result1.targetStatus, result2.targetStatus)
        assertEquals(result1.failureReasons, result2.failureReasons)
    }
}
