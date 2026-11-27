package com.coldchain.handshake.repository

import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.safety.FakeShipmentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipmentWorkflowTest {

    private val server = FakeColdChainServer(currentUser = "sender")
    private val shipments = FakeShipmentRepository()
    private val workflow = ShipmentWorkflow(server, shipments) { "device-1" }
    private val draft = ShipmentDraft("SHIP-9", ProductProfiles.DEFAULT.id, "LOG-9", "Depot", "Clinic")

    private suspend fun stored(id: String = "SHIP-9") = shipments.getShipment(id).first()

    @Test
    fun aNewShipmentIsKeptHereAsTheServerHasIt() = runBlocking {
        val created = workflow.create(draft).getOrThrow()

        assertEquals(ShipmentStatus.CREATED, created.status)
        assertEquals(server.heads.getValue("SHIP-9").headHash, created.genesisHash)
        assertEquals(created, stored())
    }

    @Test
    fun openingGivesTheCopyThisPhoneKeptNotJustTheServers() = runBlocking {
        // A phone keeps the earlier of two journey starts, so the copy the screens work with must be the stored
        // one, or the next save made from it is refused for changing the start.
        val kept = ShipmentRepositoryImpl(FakeShipmentDao())
        val opener = ShipmentWorkflow(server, kept) { "device-1" }
        server.seedShipment("SHIP-1")
        val first = opener.open("SHIP-1").getOrThrow()
        server.shipments["SHIP-1"] =
            server.shipments.getValue("SHIP-1").copy(journeyStartedAt = first.journeyStartedAt!! + 600_000L)

        val reopened = opener.open("SHIP-1").getOrThrow()

        assertEquals(first.journeyStartedAt, reopened.journeyStartedAt)
        assertEquals(kept.getShipment("SHIP-1").first(), reopened)
    }

    @Test
    fun theQrCodeOnlyNamesTheShipment() = runBlocking {
        assertEquals("SHIP-9", workflow.create(draft).getOrThrow().qrCode)
    }

    @Test
    fun anUnknownProductIsRefusedBeforeAskingTheServer() {
        assertTrue(runCatching { runBlocking { workflow.create(draft.copy(productProfileId = "nothing")) } }.isFailure)
        assertTrue(server.shipments.isEmpty())
    }

    @Test
    fun aMistypedEmailKeepsTheShipment() = runBlocking {
        workflow.create(draft).getOrThrow()

        assertTrue(workflow.addPeople("SHIP-9", "nobody@example.com", "receiver@example.com").isFailure)
        assertEquals(ShipmentStatus.CREATED, stored()?.status)
    }

    @Test
    fun aShipmentCannotLeaveWithoutACourierAndAReceiver() = runBlocking {
        workflow.create(draft).getOrThrow()

        val failure = workflow.dispatch("SHIP-9").exceptionOrNull()

        assertEquals("Add a courier and a receiver first.", failure?.message)
        assertEquals(ShipmentStatus.CREATED, server.shipments.getValue("SHIP-9").status)
    }

    // 5.3, multi-leg custody: the sender names every courier of the trip in the courier field, separated by commas.
    @Test
    fun severalCourierEmailsAreEachAddedAsACourier() = runBlocking {
        server.knownEmails += "courier-b@example.com"
        workflow.create(draft).getOrThrow()

        workflow.addPeople("SHIP-9", " courier@example.com, courier-b@example.com ", "receiver@example.com").getOrThrow()

        val people = server.members.getValue("SHIP-9")
        assertEquals(
            listOf("courier@example.com", "courier-b@example.com"),
            people.filter { it.role == MemberRole.COURIER }.map { it.userId }
        )
        assertEquals(listOf("receiver@example.com"), people.filter { it.role == MemberRole.RECEIVER }.map { it.userId })
    }

    @Test
    fun dispatchingWaitsForTheServer() = runBlocking {
        workflow.create(draft).getOrThrow()
        workflow.addPeople("SHIP-9", "courier@example.com", "receiver@example.com").getOrThrow()

        workflow.dispatch("SHIP-9").getOrThrow()

        assertEquals(ShipmentStatus.DISPATCHED, stored()?.status)
    }

    @Test
    fun takingCustodyStartsTheJourneyAtTheServersTime() = runBlocking {
        workflow.create(draft).getOrThrow()
        workflow.addPeople("SHIP-9", "courier@example.com", "receiver@example.com").getOrThrow()
        workflow.dispatch("SHIP-9").getOrThrow()
        server.currentUser = "courier@example.com"

        val taken = workflow.takeCustody("SHIP-9").getOrThrow()

        assertEquals(ShipmentStatus.IN_TRANSIT, taken.status)
        assertEquals(server.shipments.getValue("SHIP-9").journeyStartedAt, taken.journeyStartedAt)
    }

    @Test
    fun aShipmentNotYetDispatchedCannotBeCollected() = runBlocking {
        workflow.create(draft).getOrThrow()

        assertTrue(workflow.takeCustody("SHIP-9").isFailure)
    }

    @Test
    fun aScannedCodeIsNeverTurnedIntoAShipment() = runBlocking {
        val result = workflow.open("CCH:SHIP:SHIP-X:Depot:Clinic:LOG-1")

        assertTrue(result.isFailure)
        assertNull(stored("SHIP-X"))
        assertTrue(shipments.savedShipments.isEmpty())
    }

    @Test
    fun onlyAQuarantinedShipmentCanBeReplaced() = runBlocking {
        workflow.create(draft).getOrThrow()

        assertTrue(workflow.requestReplacement("SHIP-9").isFailure)

        server.shipments["SHIP-9"] = server.shipments.getValue("SHIP-9").copy(status = ShipmentStatus.QUARANTINED)
        assertEquals(ShipmentStatus.REPLACEMENT_REQUESTED, workflow.requestReplacement("SHIP-9").getOrThrow().status)
    }

    @Test
    fun withNoSignalNothingChangesHere() = runBlocking {
        workflow.create(draft).getOrThrow()
        server.offlineError = java.io.IOException("No connection")

        assertTrue(workflow.dispatch("SHIP-9").isFailure)
        assertEquals(ShipmentStatus.CREATED, stored()?.status)
    }
}
