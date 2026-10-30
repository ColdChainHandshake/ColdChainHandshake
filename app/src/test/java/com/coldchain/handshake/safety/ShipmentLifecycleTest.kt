package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
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
    fun aShipmentsLoggerCannotChangeOnceItIsMoving() = runBlocking {
        val repository = ShipmentRepositoryImpl(FakeShipmentDao())
        repository.adoptRemoteShipment(shipment(IN_TRANSIT)).getOrThrow()

        assertTrue(repository.saveShipment(shipment(IN_TRANSIT).copy(loggerId = "LOG-2")).isFailure)
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
