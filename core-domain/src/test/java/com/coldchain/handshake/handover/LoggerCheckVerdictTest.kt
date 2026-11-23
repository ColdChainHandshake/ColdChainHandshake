package com.coldchain.handshake.handover

import com.coldchain.handshake.logger.LoggerCheckOutcome
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.safety.TemperatureVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logger check at handover can only make a verdict stricter, never more lenient, and not reaching a logger
 * changes nothing, so a handover where the logger travels on another phone can still pass on the other checks.
 */
class LoggerCheckVerdictTest {

    private fun verdict(
        logger: LoggerCheckOutcome,
        temperature: TemperatureVerdict = TemperatureVerdict.PASS,
        integrity: Boolean = true
    ) = HandoverVerdictEngine.evaluate(
        temperature = temperature,
        integrityPassed = integrity,
        workerSigned = true,
        pharmacistSigned = true,
        loggerCheck = logger
    )

    @Test
    fun aPassingOrUnreadLoggerLeavesAPassAsItIs() {
        assertEquals(HandoverVerdict.PASS, verdict(LoggerCheckOutcome.PASSED).verdict)
        assertEquals(HandoverVerdict.PASS, verdict(LoggerCheckOutcome.NOT_CHECKED).verdict)
    }

    @Test
    fun aFailedLoggerCheckFailsTheHandoverWithItsReason() {
        val evaluation = verdict(LoggerCheckOutcome.FAILED)
        assertEquals(HandoverVerdict.FAIL, evaluation.verdict)
        assertTrue(HandoverFailureReason.LOGGER_CHECK_FAILED in evaluation.failureReasons)
    }

    @Test
    fun anUnknownLoggerCheckMakesAPassUnknown() {
        val evaluation = verdict(LoggerCheckOutcome.UNKNOWN)
        assertEquals(HandoverVerdict.UNKNOWN, evaluation.verdict)
        assertTrue(HandoverFailureReason.LOGGER_CHECK_UNKNOWN in evaluation.failureReasons)
    }

    @Test
    fun noLoggerOutcomeLiftsAFailOrAnUnknown() {
        LoggerCheckOutcome.entries.forEach { logger ->
            assertEquals("$logger", HandoverVerdict.FAIL, verdict(logger, temperature = TemperatureVerdict.FAIL).verdict)
            assertEquals("$logger", HandoverVerdict.FAIL, verdict(logger, integrity = false).verdict)
            val unknownOrWorse = verdict(logger, temperature = TemperatureVerdict.UNKNOWN).verdict
            assertTrue("$logger gave $unknownOrWorse", unknownOrWorse == HandoverVerdict.UNKNOWN || unknownOrWorse == HandoverVerdict.FAIL)
        }
    }

    @Test
    fun aReceiverCantDeclareAPassOverAFailedLogger() {
        val decision = HandoverVerdictEngine.decide(verdict(LoggerCheckOutcome.FAILED), HandoverVerdict.PASS, null)
        assertTrue(decision.isFailure)
    }
}
