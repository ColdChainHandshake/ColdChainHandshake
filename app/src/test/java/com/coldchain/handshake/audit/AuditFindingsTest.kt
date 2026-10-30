package com.coldchain.handshake.audit

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.safety.BreachDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests written during the code review. Each one passes because the weakness it describes is still
 * PRESENT, so they document behaviour rather than guard it. Both are review finding 2, which Phase 3
 * fixes with the keyed chain and server checks. Flip each assertion then and move it to [RegressionTest].
 */
class AuditFindingsTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun rawEvents(temps: List<Double>, stepMs: Long = minute): List<TemperatureEvent> =
        temps.mapIndexed { i, t ->
            TemperatureEvent(
                id = "TE-%03d".format(i),
                shipmentId = "SHIP-AUDIT",
                loggerId = "LOG-1",
                timestamp = t0 + i * stepMs,
                temperature = t,
                previousHash = "",
                currentHash = "",
                syncStatus = SyncStatus.PENDING
            )
        }

    @Test
    fun finding1_truncatingTheTailOfTheChainStillVerifies() {
        // 6 safe readings followed by 8 hot readings (a real >5 min breach)
        val chain = HashChainService.buildChain(rawEvents(List(6) { 5.0 } + List(8) { 10.5 }))
        assertTrue(BreachDetector.evaluateTelemetry(chain).hasBreach)

        // Drop the hot tail: nothing commits to the chain length or head hash
        val truncated = chain.take(6)
        assertTrue("truncated chain verifies", HashChainService.verifyChain(truncated).valid)
        assertTrue("and the breach is gone", BreachDetector.evaluateTelemetry(truncated).temperaturePassed)
    }

    @Test
    fun finding2_rewritingAndReHashingTheWholeChainVerifies() {
        val original = HashChainService.buildChain(rawEvents(List(6) { 5.0 } + List(8) { 10.5 }))
        // Anyone holding the data can edit values and recompute every hash: no key is involved
        val forged = HashChainService.buildChain(original.map { it.copy(temperature = 5.0) })
        assertTrue(HashChainService.verifyChain(forged).valid)
        assertEquals(original.map { it.id }, forged.map { it.id })
        assertTrue(BreachDetector.evaluateTelemetry(forged).temperaturePassed)
    }
}
