package com.coldchain.handshake.handover

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.FakeHandoverDao
import com.coldchain.handshake.repository.FakeShipmentDao
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.safety.IllegalTransitionException
import com.coldchain.handshake.safety.hotThenSafe
import com.coldchain.handshake.safety.steadyReadings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverDecisionTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_700_000_000_000L

    private val shipments = ShipmentRepositoryImpl(FakeShipmentDao())
    private val telemetry = InMemoryTelemetryRepository()
    private val handovers = HandoverRepositoryImpl(FakeHandoverDao())
    private val orchestrator = HandoverOrchestrator(shipments, telemetry, handovers)

    private fun shipment(
        status: ShipmentStatus,
        profileId: String = ProductProfiles.DEFAULT.id,
        journeyStartedAt: Long? = t0
    ) = Shipment("SHIP-1", "QR", "LOG-1", "A", "B", "W-1", status, profileId, journeyStartedAt)

    private suspend fun prepare(
        readings: List<TemperatureEvent>,
        status: ShipmentStatus = ShipmentStatus.ARRIVED,
        profileId: String = ProductProfiles.DEFAULT.id,
        journeyStartedAt: Long? = t0
    ) {
        shipments.adoptRemoteShipment(shipment(status, profileId, journeyStartedAt)).getOrThrow()
        val ownReadings = readings.mapIndexed { index, reading -> reading.copy(id = "R-$index", shipmentId = "SHIP-1") }
        telemetry.saveTemperatures(HashChainService.buildChain(ownReadings)).getOrThrow()
    }

    private suspend fun handOver(
        at: Long = t0 + 11 * minute,
        declared: HandoverVerdict? = null,
        reason: String? = null,
        vvmStage: VvmStage = VvmStage.NOT_RECORDED,
        shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE,
        workerSigned: Boolean = true,
        pharmacistSigned: Boolean = true
    ) = orchestrator.processHandover(
        shipmentId = "SHIP-1",
        workerSigned = workerSigned,
        pharmacistSigned = pharmacistSigned,
        declaredVerdict = declared,
        reason = reason,
        vvmStage = vvmStage,
        shakeTest = shakeTest,
        timestamp = at
    )

    private suspend fun storedStatus() = shipments.getShipment("SHIP-1").first()?.status

    // The three verdicts

    @Test
    fun cleanReadingsRecordAPass() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver().getOrThrow()

        assertEquals(HandoverVerdict.PASS, result.handover.verdict)
        assertTrue(result.handover.temperaturePassed)
        assertNull(result.handover.reason)
        assertEquals(ShipmentStatus.ACCEPTED, storedStatus())
    }

    @Test
    fun theVerdictComesFromTheReadingsNotFromTheCaller() = runBlocking {
        prepare(hotThenSafe(t0, 10 * minute, 11.0))

        val result = handOver().getOrThrow()

        assertEquals(HandoverVerdict.FAIL, result.handover.verdict)
        assertFalse(result.handover.temperaturePassed)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun aFailureRecordsItsReason() = runBlocking {
        prepare(hotThenSafe(t0, 10 * minute, 11.0))

        val result = handOver().getOrThrow()

        assertTrue(result.handover.reason!!.contains("out of range"))
    }

    @Test
    fun aGapInTheReadingsRecordsUnknownAndHoldsTheShipment() = runBlocking {
        prepare(steadyReadings(t0, minute, 5.0) + steadyReadings(t0 + 2 * hour, 10 * minute, 5.0))

        val result = handOver(at = t0 + 2 * hour + 11 * minute).getOrThrow()

        assertEquals(HandoverVerdict.UNKNOWN, result.handover.verdict)
        assertFalse(result.handover.temperaturePassed)
        assertTrue(result.handover.reason!!.contains("gap"))
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun aLoggerThatWentQuietBeforeHandoverIsUnknown() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver(at = t0 + 6 * hour).getOrThrow()

        assertEquals(HandoverVerdict.UNKNOWN, result.handover.verdict)
    }

    @Test
    fun aShipmentWhoseJourneyStartIsUnknownCannotPass() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0), journeyStartedAt = null)

        assertEquals(HandoverVerdict.UNKNOWN, handOver().getOrThrow().handover.verdict)
    }

    @Test
    fun aJourneyStartAfterTheHandoverCannotPass() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0), journeyStartedAt = t0 + 12 * minute)

        val handover = handOver(at = t0 + 11 * minute).getOrThrow().handover

        assertEquals(HandoverVerdict.UNKNOWN, handover.verdict)
        assertFalse("an UNKNOWN needs a reason", handover.reason.isNullOrBlank())
    }

    @Test
    fun movingTheJourneyStartLaterCannotTurnAnUnknownIntoAPass() = runBlocking {
        // The third Gate 2 review's probe d12: the readings begin an hour after the journey started.
        prepare(steadyReadings(t0, 10 * minute, 5.0), journeyStartedAt = t0 - hour)
        val moving = shipments.getShipment("SHIP-1").first()!!

        assertTrue(shipments.saveShipment(moving.copy(journeyStartedAt = t0)).isFailure)
        assertEquals(HandoverVerdict.UNKNOWN, handOver().getOrThrow().handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun aFirstReadingLongAfterTheJourneyStartedIsUnknown() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0), journeyStartedAt = t0 - 2 * hour)

        assertEquals(HandoverVerdict.UNKNOWN, handOver().getOrThrow().handover.verdict)
    }

    @Test
    fun noReadingsAtAllIsRecordedAsUnknownNotAFailedChain() = runBlocking {
        prepare(emptyList())

        val result = handOver().getOrThrow()

        assertEquals(HandoverVerdict.UNKNOWN, result.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun aHandoverCanBeRecordedWhenTheHistoryCouldNotBeFetched() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = orchestrator.processHandover(
            shipmentId = "SHIP-1",
            workerSigned = true,
            pharmacistSigned = true,
            historySynced = false,
            timestamp = t0 + 11 * minute
        ).getOrThrow()

        assertEquals(HandoverVerdict.UNKNOWN, result.handover.verdict)
    }

    @Test
    fun anEditedChainIsStillAFailWhenTheHistoryCouldNotBeFetched() = runBlocking {
        prepare(emptyList())
        val chain = HashChainService.buildChain(
            steadyReadings(t0, 10 * minute, 5.0).mapIndexed { index, reading ->
                reading.copy(id = "R-$index", shipmentId = "SHIP-1")
            }
        )
        telemetry.saveTemperatures(
            chain.mapIndexed { index, event -> if (index == 3) event.copy(temperature = 4.0) else event }
        ).getOrThrow()

        val result = orchestrator.processHandover(
            shipmentId = "SHIP-1",
            workerSigned = true,
            pharmacistSigned = true,
            historySynced = false,
            timestamp = t0 + 11 * minute
        ).getOrThrow()

        assertEquals(HandoverVerdict.FAIL, result.handover.verdict)
    }

    @Test
    fun aSecondHandoverForTheSameShipmentIsRefusedNotSilentlyLost() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))
        handOver(declared = HandoverVerdict.UNKNOWN, reason = "Logger damaged").getOrThrow()

        val second = handOver(declared = HandoverVerdict.FAIL, reason = "Seal broken")

        assertTrue(second.isFailure)
        assertEquals("Logger damaged", handovers.getHandover("SHIP-1").first()?.reason)
    }

    @Test
    fun anOutcomeTheShipmentCannotReachWritesNothing() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0), status = ShipmentStatus.QUARANTINED)

        assertTrue(handOver().isFailure)
        assertNull(handovers.getHandover("SHIP-1").first())
    }

    // Per-product rules

    @Test
    fun theShipmentsOwnProfileDecidesTheVerdict() = runBlocking {
        val shortFreeze = steadyReadings(t0, 2 * minute, 5.0) + hotThenSafe(t0 + 3 * minute, 2 * minute, -3.0)
        prepare(shortFreeze, profileId = ProductProfiles.FREEZE_TOLERANT.id)

        val result = handOver(at = t0 + 6 * minute).getOrThrow()

        assertEquals(HandoverVerdict.PASS, result.handover.verdict)
    }

    @Test
    fun theSameFreezeFailsAFreezeSensitiveProduct() = runBlocking {
        val shortFreeze = steadyReadings(t0, 2 * minute, 5.0) + hotThenSafe(t0 + 3 * minute, 2 * minute, -3.0)
        prepare(shortFreeze, profileId = ProductProfiles.FREEZE_SENSITIVE_VACCINE.id)

        val result = handOver(at = t0 + 6 * minute).getOrThrow()

        assertEquals(HandoverVerdict.FAIL, result.handover.verdict)
    }

    // Independent evidence

    @Test
    fun aVialMonitorPastTheDiscardPointFailsTheHandover() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver(vvmStage = VvmStage.STAGE_3).getOrThrow()

        assertEquals(HandoverVerdict.FAIL, result.handover.verdict)
        assertEquals(VvmStage.STAGE_3, result.handover.vvmStage)
        assertTrue(result.handover.reason!!.contains("vial monitor"))
    }

    @Test
    fun aVialMonitorPastTheDiscardPointAlsoFails() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        assertEquals(HandoverVerdict.FAIL, handOver(vvmStage = VvmStage.STAGE_4).getOrThrow().handover.verdict)
    }

    @Test
    fun aVerdictStricterThanTheChecksNeedsTheReceiversOwnReason() = runBlocking {
        prepare(steadyReadings(t0, 3 * minute, 5.0) + steadyReadings(t0 + 60 * minute, 5 * minute, 5.0))

        val result = handOver(at = t0 + 66 * minute, declared = HandoverVerdict.FAIL)

        assertTrue(result.isFailure)
        assertNull(handovers.getHandover("SHIP-1").first())
    }

    @Test
    fun theReceiversOwnReasonIsKeptOverTheComputedOne() = runBlocking {
        prepare(hotThenSafe(t0, 10 * minute, 11.0))

        val result = handOver(reason = "Box left in the sun").getOrThrow()

        assertEquals("Box left in the sun", result.handover.reason)
    }

    @Test
    fun aVialMonitorStillUsableDoesNotFailTheHandover() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver(vvmStage = VvmStage.STAGE_2).getOrThrow()

        assertEquals(HandoverVerdict.PASS, result.handover.verdict)
    }

    @Test
    fun aFailedShakeTestFailsTheHandover() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver(shakeTest = ShakeTestResult.FAILED).getOrThrow()

        assertEquals(HandoverVerdict.FAIL, result.handover.verdict)
        assertEquals(ShakeTestResult.FAILED, result.handover.shakeTest)
        assertTrue(result.handover.reason!!.contains("shake test"))
    }

    @Test
    fun aPassedShakeTestIsRecordedAndDoesNotChangeThePass() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver(shakeTest = ShakeTestResult.PASSED).getOrThrow()

        assertEquals(HandoverVerdict.PASS, result.handover.verdict)
        assertEquals(ShakeTestResult.PASSED, result.handover.shakeTest)
    }

    // What the receiver can declare

    @Test
    fun theReceiverCanRecordAFailureTheReadingsDidNotShow() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver(declared = HandoverVerdict.FAIL, reason = "Seal broken").getOrThrow()

        assertEquals(HandoverVerdict.FAIL, result.handover.verdict)
        assertEquals("Seal broken", result.handover.reason)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun theReceiverCanRecordUnknownWithAReason() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        val result = handOver(declared = HandoverVerdict.UNKNOWN, reason = "Logger damaged").getOrThrow()

        assertEquals(HandoverVerdict.UNKNOWN, result.handover.verdict)
        assertEquals("Logger damaged", result.handover.reason)
    }

    @Test
    fun aDeclaredFailureNeedsAReason() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        assertTrue(handOver(declared = HandoverVerdict.FAIL).isFailure)
        assertTrue(handOver(declared = HandoverVerdict.FAIL, reason = "   ").isFailure)
        assertEquals(ShipmentStatus.ARRIVED, storedStatus())
    }

    @Test
    fun aDeclaredUnknownNeedsAReason() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        assertTrue(handOver(declared = HandoverVerdict.UNKNOWN).isFailure)
    }

    @Test
    fun theReceiverCannotPassABreach() = runBlocking {
        prepare(hotThenSafe(t0, 10 * minute, 11.0))

        val result = handOver(declared = HandoverVerdict.PASS, reason = "Looks fine")

        assertTrue(result.isFailure)
        assertNull(handovers.getHandover("SHIP-1").first())
        assertEquals(ShipmentStatus.ARRIVED, storedStatus())
    }

    @Test
    fun theReceiverCannotPassARecordWithAGap() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))

        assertTrue(handOver(at = t0 + 6 * hour, declared = HandoverVerdict.PASS).isFailure)
    }

    @Test
    fun theReceiverCannotDowngradeAFailureToUnknown() = runBlocking {
        prepare(hotThenSafe(t0, 10 * minute, 11.0))

        assertTrue(handOver(declared = HandoverVerdict.UNKNOWN, reason = "Not sure").isFailure)
    }

    // Lifecycle

    @Test
    fun aShipmentStillInTransitPassesThroughArrivalAndInspection() = runBlocking {
        val seen = mutableListOf<ShipmentStatus>()
        val recording = object : com.coldchain.handshake.repository.ShipmentRepository by shipments {
            override suspend fun saveShipment(shipment: Shipment): Result<Unit> {
                seen.add(shipment.status)
                return shipments.saveShipment(shipment)
            }
        }
        prepare(steadyReadings(t0, 10 * minute, 5.0), status = ShipmentStatus.IN_TRANSIT)

        HandoverOrchestrator(recording, telemetry, handovers)
            .processHandover("SHIP-1", workerSigned = true, pharmacistSigned = true, timestamp = t0 + 11 * minute)
            .getOrThrow()

        assertEquals(listOf(ShipmentStatus.ARRIVED, ShipmentStatus.INSPECTED, ShipmentStatus.ACCEPTED), seen)
    }

    @Test
    fun aShipmentThatNeverLeftCannotBeHandedOver() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0), status = ShipmentStatus.DISPATCHED)

        assertTrue(handOver().exceptionOrNull() is IllegalTransitionException)
    }

    @Test
    fun anAcceptedShipmentCannotBeHandedOverAgain() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))
        handOver().getOrThrow()

        assertTrue(handOver().isFailure)
        assertEquals(ShipmentStatus.ACCEPTED, storedStatus())
    }

    @Test
    fun aShipmentQuarantinedOnTheRoadStaysQuarantinedAfterHandover() = runBlocking {
        prepare(hotThenSafe(t0, 10 * minute, 11.0), status = ShipmentStatus.QUARANTINED)

        val result = handOver().getOrThrow()

        assertEquals(HandoverVerdict.FAIL, result.handover.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun aQuarantinedShipmentCanHaveAReplacementRequested() = runBlocking {
        prepare(hotThenSafe(t0, 10 * minute, 11.0))
        handOver().getOrThrow()

        val updated = orchestrator.requestReplacement("SHIP-1").getOrThrow()

        assertEquals(ShipmentStatus.REPLACEMENT_REQUESTED, updated.status)
        assertEquals(ShipmentStatus.REPLACEMENT_REQUESTED, storedStatus())
    }

    @Test
    fun anAcceptedShipmentCannotHaveAReplacementRequested() = runBlocking {
        prepare(steadyReadings(t0, 10 * minute, 5.0))
        handOver().getOrThrow()

        assertTrue(orchestrator.requestReplacement("SHIP-1").isFailure)
    }
}
