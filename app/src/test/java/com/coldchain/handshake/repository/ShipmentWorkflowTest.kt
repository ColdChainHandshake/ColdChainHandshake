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
