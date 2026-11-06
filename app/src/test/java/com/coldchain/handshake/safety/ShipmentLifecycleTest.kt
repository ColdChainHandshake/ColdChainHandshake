package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.ShipmentStatus.ACCEPTED
import com.coldchain.handshake.models.ShipmentStatus.ARRIVED
import com.coldchain.handshake.models.ShipmentStatus.CREATED
import com.coldchain.handshake.models.ShipmentStatus.DISPATCHED
import com.coldchain.handshake.models.ShipmentStatus.INSPECTED
import com.coldchain.handshake.models.ShipmentStatus.IN_TRANSIT
import com.coldchain.handshake.models.ShipmentStatus.QUARANTINED
import com.coldchain.handshake.models.ShipmentStatus.REPLACEMENT_REQUESTED
import com.coldchain.handshake.repository.FakeShipmentDao
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipmentLifecycleTest {

    private val legalMoves = setOf(
        CREATED to DISPATCHED,
        DISPATCHED to IN_TRANSIT,
        DISPATCHED to QUARANTINED,
        IN_TRANSIT to ARRIVED,
        IN_TRANSIT to QUARANTINED,
        ARRIVED to INSPECTED,
        ARRIVED to QUARANTINED,
        INSPECTED to ACCEPTED,
        INSPECTED to QUARANTINED,
        QUARANTINED to REPLACEMENT_REQUESTED
    )

    private fun shipment(status: ShipmentStatus) = Shipment("SHIP-1", "QR", "LOG-1", "A", "B", "W-1", status)

    @Test
    fun everyPairOfStatusesIsEitherLegalOrRejected() {
        ShipmentStatus.entries.forEach { from ->
            ShipmentStatus.entries.forEach { to ->
                val legal = (from to to) in legalMoves
                val move = "$from -> $to"

                assertEquals(move, legal, ShipmentLifecycle.canTransition(from, to))

                val result = ShipmentLifecycle.transition(shipment(from), to)
                assertEquals(move, legal, result.isSuccess)
                if (legal) {
                    assertEquals(move, to, result.getOrThrow().status)
                } else {
                    assertTrue(move, result.exceptionOrNull() is IllegalTransitionException)
                }
            }
        }
    }

    @Test
    fun acceptedAndReplacementRequestedAreFinal() {
        assertTrue(ShipmentLifecycle.allowedNext(ACCEPTED).isEmpty())
        assertTrue(ShipmentLifecycle.allowedNext(REPLACEMENT_REQUESTED).isEmpty())
    }

    @Test
    fun aTransitionKeepsEveryOtherField() {
        val before = shipment(IN_TRANSIT).copy(journeyStartedAt = 123L, productProfileId = "profile")

        val after = ShipmentLifecycle.transition(before, ARRIVED).getOrThrow()

        assertEquals(before.copy(status = ARRIVED), after)
    }

    // Inspection path

    @Test
    fun aShipmentInTransitWalksThroughArrivalToInspection() {
        val path = ShipmentLifecycle.pathToInspection(IN_TRANSIT).getOrThrow()

        assertEquals(listOf(ARRIVED, INSPECTED), path)
    }

    @Test
    fun aShipmentAlreadyArrivedOnlyNeedsInspection() {
        assertEquals(listOf(INSPECTED), ShipmentLifecycle.pathToInspection(ARRIVED).getOrThrow())
        assertEquals(emptyList<ShipmentStatus>(), ShipmentLifecycle.pathToInspection(INSPECTED).getOrThrow())
    }

    @Test
    fun aShipmentQuarantinedInTransitCanStillBeInspected() {
        assertEquals(emptyList<ShipmentStatus>(), ShipmentLifecycle.pathToInspection(QUARANTINED).getOrThrow())
    }

    @Test
    fun aShipmentThatNeverLeftOrIsFinishedCannotBeInspected() {
        listOf(CREATED, DISPATCHED, ACCEPTED, REPLACEMENT_REQUESTED).forEach { status ->
            assertTrue("$status", ShipmentLifecycle.pathToInspection(status).isFailure)
        }
    }

    // Quarantine

    @Test
    fun aBreachDoesNotReopenAFinishedShipment() {
        val evaluation = BreachEvaluation(hasBreach = true, cumulativeBreachDurationMs = 1L)

        assertEquals(ACCEPTED, QuarantineManager.applyQuarantine(shipment(ACCEPTED), evaluation).status)
        assertEquals(CREATED, QuarantineManager.applyQuarantine(shipment(CREATED), evaluation).status)
    }

    // The repository is the single choke point for status changes.

    @Test
    fun theRepositoryRejectsAnIllegalStatusChange() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.saveShipment(shipment(CREATED)).getOrThrow()

        val result = repository.saveShipment(shipment(ACCEPTED))

        assertTrue(result.exceptionOrNull() is IllegalTransitionException)
        assertEquals(CREATED, repository.getShipment("SHIP-1").first()?.status)
    }

    @Test
    fun theRepositoryAllowsLegalMovesAndUnchangedStatuses() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.saveShipment(shipment(CREATED)).getOrThrow()
        repository.saveShipment(shipment(DISPATCHED)).getOrThrow()

        val updatedOrigin = repository.saveShipment(shipment(DISPATCHED).copy(origin = "C"))

        assertTrue(updatedOrigin.isSuccess)
        assertEquals("C", repository.getShipment("SHIP-1").first()?.origin)
    }

    @Test
    fun aNewShipmentMustStartAsCreated() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())

        val result = repository.saveShipment(shipment(ACCEPTED))

        assertTrue(result.exceptionOrNull() is IllegalTransitionException)
        assertEquals(null, repository.getShipment("SHIP-1").first())
    }

    @Test
    fun aReceiverCanImportAShipmentAtTheStatusTheServerGives() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())

        assertTrue(repository.adoptRemoteShipment(shipment(IN_TRANSIT)).isSuccess)
    }

    @Test
    fun adoptingKeepsWhatThisDeviceAlreadyKnows() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.adoptRemoteShipment(
            shipment(IN_TRANSIT).copy(journeyStartedAt = 1_000L, genesisHash = "a".repeat(64))
        ).getOrThrow()

        repository.adoptRemoteShipment(shipment(ARRIVED)).getOrThrow()

        val stored = repository.getShipment("SHIP-1").first()
        assertEquals(1_000L, stored?.journeyStartedAt)
        assertEquals("a".repeat(64), stored?.genesisHash)
    }

    @Test
    fun aShipmentsProductCannotChangeOnceItExists() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.saveShipment(shipment(CREATED)).getOrThrow()

        val result = repository.saveShipment(shipment(CREATED).copy(productProfileId = "another"))

        assertTrue(result.isFailure)
        assertEquals(shipment(CREATED).productProfileId, repository.getShipment("SHIP-1").first()?.productProfileId)
    }

    @Test
    fun aShipmentsLoggerCanStillChangeBeforeDispatch() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.saveShipment(shipment(CREATED)).getOrThrow()

        assertTrue(repository.saveShipment(shipment(CREATED).copy(loggerId = "LOG-2")).isSuccess)
    }

    @Test
    fun adoptingFromTheServerCannotChangeTheProductOrTheLogger() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.adoptRemoteShipment(shipment(IN_TRANSIT)).getOrThrow()

        assertTrue(repository.adoptRemoteShipment(shipment(ARRIVED).copy(productProfileId = "another")).isFailure)
        assertTrue(repository.adoptRemoteShipment(shipment(ARRIVED).copy(loggerId = "LOG-2")).isFailure)
        assertEquals(IN_TRANSIT, repository.getShipment("SHIP-1").first()?.status)
    }

    @Test
    fun aShipmentsLoggerCannotChangeOnceItIsMoving() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.adoptRemoteShipment(shipment(IN_TRANSIT)).getOrThrow()

        assertTrue(repository.saveShipment(shipment(IN_TRANSIT).copy(loggerId = "LOG-2")).isFailure)
    }

    // The journey start opens the window the handover checks, so moving it later could hide the front of a journey
    // (the third Gate 2 review's probe d12). The genesis anchors the chain (probe d13).

    @Test
    fun aJourneyStartCannotMoveOrBeDroppedOnceSet() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.saveShipment(shipment(CREATED)).getOrThrow()
        repository.saveShipment(shipment(DISPATCHED)).getOrThrow()
        repository.saveShipment(shipment(IN_TRANSIT).copy(journeyStartedAt = 1_000L)).getOrThrow()

        assertTrue(repository.saveShipment(shipment(IN_TRANSIT).copy(journeyStartedAt = 1_000L + 3_600_000L)).isFailure)
        assertTrue(repository.saveShipment(shipment(ARRIVED).copy(journeyStartedAt = 500L)).isFailure)
        assertTrue(repository.saveShipment(shipment(ARRIVED)).isFailure)
        assertEquals(1_000L, repository.getShipment("SHIP-1").first()?.journeyStartedAt)
        assertTrue(repository.saveShipment(shipment(ARRIVED).copy(journeyStartedAt = 1_000L)).isSuccess)
    }

    @Test
    fun aBreachAfterTheJourneyStartedStillQuarantines() = runBlocking {
        // The start is locked, so the safety engine's save must carry it. This is the path Start Journey takes.
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        val t0 = 1_700_000_000_000L
        repository.saveShipment(shipment(CREATED)).getOrThrow()
        repository.saveShipment(shipment(DISPATCHED)).getOrThrow()
        repository.saveShipment(shipment(IN_TRANSIT).copy(journeyStartedAt = t0)).getOrThrow()
        val moving = repository.getShipment("SHIP-1").first()!!
        val hot = (0..10).map { i ->
            TemperatureEvent("H-$i", "SHIP-1", "LOG-1", t0 + i * 60_000L, 12.0, "", "", SyncStatus.PENDING)
        }

        val decision = SafetyEngine(FakeAlertRepository(), repository)
            .processTelemetryAndEnforceSafety(moving, hot, timestamp = t0 + 11 * 60_000L)

        assertEquals(true, decision.quarantineSaveResult?.isSuccess)
        assertEquals(QUARANTINED, repository.getShipment("SHIP-1").first()?.status)
        assertEquals(t0, repository.getShipment("SHIP-1").first()?.journeyStartedAt)
    }

    @Test
    fun aGenesisCannotChangeOrBeDroppedOnceSet() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.saveShipment(shipment(CREATED).copy(genesisHash = "a".repeat(64))).getOrThrow()

        assertTrue(repository.saveShipment(shipment(CREATED).copy(genesisHash = "b".repeat(64))).isFailure)
        assertTrue(repository.saveShipment(shipment(CREATED)).isFailure)
        assertEquals("a".repeat(64), repository.getShipment("SHIP-1").first()?.genesisHash)
    }

    @Test
    fun savingCannotGiveAShipmentAGenesisLater() = runBlocking {
        // Only the server supplies a genesis, through adoption. Probe d13 attached one mid-journey.
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.adoptRemoteShipment(shipment(IN_TRANSIT)).getOrThrow()

        assertTrue(repository.saveShipment(shipment(IN_TRANSIT).copy(genesisHash = "a".repeat(64))).isFailure)
        assertEquals(null, repository.getShipment("SHIP-1").first()?.genesisHash)
        assertTrue(repository.adoptRemoteShipment(shipment(IN_TRANSIT).copy(genesisHash = "a".repeat(64))).isSuccess)
    }

    @Test
    fun adoptingKeepsTheEarlierJourneyStartAndRefusesAnotherGenesis() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        val genesis = "a".repeat(64)
        repository.adoptRemoteShipment(shipment(IN_TRANSIT).copy(journeyStartedAt = 1_000L, genesisHash = genesis)).getOrThrow()

        repository.adoptRemoteShipment(
            shipment(ARRIVED).copy(journeyStartedAt = 1_000L + 3_600_000L, genesisHash = genesis)
        ).getOrThrow()

        val stored = repository.getShipment("SHIP-1").first()
        assertEquals(1_000L, stored?.journeyStartedAt)
        assertEquals(ARRIVED, stored?.status)
        assertTrue(repository.adoptRemoteShipment(shipment(ARRIVED).copy(genesisHash = "b".repeat(64))).isFailure)
        assertEquals(genesis, repository.getShipment("SHIP-1").first()?.genesisHash)
    }

    @Test
    fun aDeviceCanCatchUpWithAStatusRecordedElsewhere() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.adoptRemoteShipment(shipment(IN_TRANSIT)).getOrThrow()

        val result = repository.adoptRemoteShipment(shipment(ACCEPTED))

        assertTrue(result.isSuccess)
        assertEquals(ACCEPTED, repository.getShipment("SHIP-1").first()?.status)
    }

    @Test
    fun aDeviceCannotBeMovedBackwardsByARemoteStatus() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.adoptRemoteShipment(shipment(IN_TRANSIT)).getOrThrow()
        repository.adoptRemoteShipment(shipment(ACCEPTED)).getOrThrow()

        val result = repository.adoptRemoteShipment(shipment(IN_TRANSIT))

        assertTrue(result.exceptionOrNull() is IllegalTransitionException)
        assertEquals(ACCEPTED, repository.getShipment("SHIP-1").first()?.status)
    }

    @Test
    fun anAcceptedShipmentCannotBeReachedFromQuarantine() {
        assertFalse(ShipmentLifecycle.canReach(QUARANTINED, ACCEPTED))
        assertTrue(ShipmentLifecycle.canReach(CREATED, ACCEPTED))
        assertTrue(ShipmentLifecycle.canReach(IN_TRANSIT, IN_TRANSIT))
    }
}
