package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.repository.impl.InMemoryShipmentRepository
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sender's Dispatch screen, without the screen: what each button does, and that a shipment just created
 * isn't lost when the phone rotates or the process is killed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatchViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val account = FakeAccountRepository()
    private val shipments = InMemoryShipmentRepository()
    private val telemetry = InMemoryTelemetryRepository()
    private val server = FakeColdChainServer(currentUser = "sender")
    private val workflow = ShipmentWorkflow(server, shipments) { "device-1" }
    // Its own real dispatcher: its recording loop never ends, so on the test clock runTest would wait on it forever.
    private val simulator = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)
    private val history = ShipmentHistorySyncCoordinator(telemetry, server)
    private val form = DispatchForm(
        productProfileId = ProductProfiles.FREEZE_TOLERANT.id,
        origin = "Hub",
        destination = "Clinic",
        workerId = "W-1",
        loggerId = "LOG-1"
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        simulator.stopContinuousSimulation()
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        DispatchViewModel(account, shipments, workflow, simulator, history, saved)

    private fun signIn() = account.setState(AccountState.Ready("sender", null, UserProfile("Sam", MemberRole.SENDER)))

    @Test
    fun signedOutAShipmentIsCreatedOnThisPhoneOnly() = runTest {
        val vm = viewModel()
        vm.create(form, courierEmail = "", receiverEmail = "").join()

        val created = vm.state.value.shipment
        assertNotNull(created)
        assertEquals(ProductProfiles.FREEZE_TOLERANT.id, created!!.productProfileId)
        assertEquals(created, shipments.getAllShipments().first().single())
        assertEquals("Created ${created.id} on this phone only", vm.state.value.banner)
        assertNull(created.genesisHash)
    }

    @Test
    fun signedInTheServerCreatesItAndBothPeopleAreAdded() = runTest {
        signIn()
        val vm = viewModel()
        vm.create(form, courierEmail = "courier@example.com", receiverEmail = "receiver@example.com").join()

        val created = vm.state.value.shipment!!
        assertNotNull(created.genesisHash)
        assertEquals("Created ${created.id}. The courier and receiver can open it now.", vm.state.value.banner)
        assertEquals(
            listOf(MemberRole.SENDER, MemberRole.COURIER, MemberRole.RECEIVER),
            server.getMembers(created.id).getOrThrow().map { it.role }
        )
    }

    @Test
    fun aRefusedPersonIsReportedAndTheShipmentStays() = runTest {
        signIn()
        val vm = viewModel()
        vm.create(form, courierEmail = "nobody@example.com", receiverEmail = "receiver@example.com").join()

        assertNotNull(vm.state.value.shipment)
        assertTrue(vm.state.value.banner!!.startsWith("Created ${vm.state.value.shipment!!.id}, but people weren't added"))
    }

    @Test
    fun aCreatedShipmentSurvivesRotationAndProcessDeath() = runTest {
        signIn()
        val saved = SavedStateHandle()
        viewModel(saved).create(form, "courier@example.com", "receiver@example.com").join()
        val created = shipments.getAllShipments().first().single()

        // A new ViewModel with the saved state is what Android builds after the process was killed.
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertEquals(created.id, restored.state.value.shipment?.id)
    }

    @Test
    fun dispatchingOnTheServerNeedsBothPeopleFirst() = runTest {
        signIn()
        val vm = viewModel()
        vm.create(form, courierEmail = "", receiverEmail = "").join()
        vm.dispatch().join()
        assertEquals(ShipmentStatus.CREATED, vm.state.value.shipment!!.status)
        assertEquals("Couldn't dispatch: Add a courier and a receiver first.", vm.state.value.banner)

        vm.addPeople("courier@example.com", "receiver@example.com").join()
        vm.dispatch().join()
        assertEquals(ShipmentStatus.DISPATCHED, vm.state.value.shipment!!.status)
    }

    @Test
    fun aLocalJourneyStartsRecordingAndOpensTransit() = runTest {
        val vm = viewModel()
        val before = System.currentTimeMillis()
        vm.create(form, "", "").join()
        vm.dispatch().join()
        vm.startLocalJourney().join()

        val moving = shipments.getAllShipments().first().single()
        assertEquals(ShipmentStatus.IN_TRANSIT, moving.status)
        assertTrue(moving.journeyStartedAt!! >= before)
        assertTrue(simulator.isRunning.value)
        assertEquals(DispatchEvent.OpenTransit, vm.events.first())
    }

    @Test
    fun aScannedLoggerCodeOnlyFillsTheForm() = runTest {
        val vm = viewModel()
        val outcome = vm.onCodeScanned("LOG-777")
        assertEquals(ScanOutcome.FillForm(loggerId = "LOG-777"), outcome)
        assertNull(vm.state.value.shipment)
        assertTrue(shipments.getAllShipments().first().isEmpty())
    }

    @Test
    fun aScannedShipmentIdOpensItForMonitoringAndNeverBuildsOne() = runTest {
        signIn()
        val vm = viewModel()
        vm.create(form, "", "").join()
        val id = vm.state.value.shipment!!.id
        vm.reset()

        assertEquals(ScanOutcome.Opened, viewModel().onCodeScanned(id))
        assertEquals(1, shipments.getAllShipments().first().size)
    }

    @Test
    fun resetClearsTheScreenButNotTheStoredShipment() = runTest {
        val vm = viewModel()
        vm.create(form, "", "").join()
        vm.reset()
        assertNull(vm.state.value.shipment)
        assertEquals(1, shipments.getAllShipments().first().size)
    }
}
