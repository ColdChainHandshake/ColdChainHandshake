package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.GpsPoint
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.recording.RecordingStore
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.repository.impl.InMemoryShipmentRepository
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.ui.navigation.ActiveShipmentRestorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The Transit screen without the screen: taking custody, opening a shipment by its code, noticing custody move on
 * the server, and coming back after the process was killed. Its polling runs only while the screen is visible,
 * as it did when it lived in the screen's own effects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransitViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val account = FakeAccountRepository()
    private val shipments = InMemoryShipmentRepository()
    private val telemetry = InMemoryTelemetryRepository()
    private val server = FakeColdChainServer(currentUser = "courier")
    private val workflow = ShipmentWorkflow(server, shipments) { "device-1" }
    // Its own real dispatcher: its recording loop never ends, so on the test clock runTest would wait on it forever.
    private var simulator = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)
    private val history = ShipmentHistorySyncCoordinator(telemetry, server)
    private var alertChecks = 0
    /** Which shipment is recording, kept on the phone; and how often the background recording was started. */
    private val recordingStore = RecordingStore.inMemory()
    private var backgroundStarts = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        simulator.stopContinuousSimulation()
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()): TransitViewModel {
        val recorder = CourierRecorder(simulator, recordingStore) { backgroundStarts++ }
        return TransitViewModel(
            account, shipments, workflow, simulator, history, server, telemetry, recorder, "device-1", { alertChecks++ },
            ActiveShipmentRestorer(simulator, shipments, server, account, recorder), saved
        )
    }

    private fun signInAs(user: String, role: MemberRole) {
        server.currentUser = user
        account.setState(AccountState.Ready(user, null, UserProfile(user, role)))
    }

    /** A shipment the sender has dispatched, which nobody has taken yet. */
    private fun dispatched(id: String) {
        server.seedShipment(id, custodian = "sender")
        server.shipments[id] = server.shipments.getValue(id).copy(status = ShipmentStatus.DISPATCHED)
        server.custody.remove(id)
    }

    @Test
    fun takingCustodyStartsRecordingOnThisPhone() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()

        vm.takeCustody().join()

        assertEquals("SHIP-1", simulator.activeShipment.value?.id)
        assertTrue(simulator.isRunning.value)
        assertFalse(simulator.isMonitorOnly.value)
        assertFalse(vm.state.value.watching)
        assertNull(vm.state.value.notice)
        assertEquals(1, alertChecks)
        assertEquals("courier", server.custody["SHIP-1"]?.custodianId)
        assertEquals("recording goes on in the background", 1, backgroundStarts)
        assertEquals("SHIP-1", recordingStore.shipmentId)
    }

    @Test
    fun aRefusedTakeCustodySaysWhyAndStartsNothing() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        server.offlineError = IOException("No signal")

        vm.takeCustody().join()

        assertEquals("No signal", vm.state.value.notice)
        assertFalse(simulator.isRunning.value)
        assertEquals(0, alertChecks)
        assertEquals(0, backgroundStarts)
        assertNull(recordingStore.shipmentId)
    }

    @Test
    fun aCodeOpensOnlyAShipmentThisPhoneOrTheServerKnows() = runTest {
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()

        vm.onCodeScanned("CCH:SHIP:SHIP-NOPE:Hub:Clinic:LOG-1").join()

        assertNull(simulator.activeShipment.value)
        assertTrue(shipments.getAllShipments().first().isEmpty())
        assertNotNull(vm.state.value.notice)
    }

    // 5.4: a code is opaque, so the old "CCH:SHIP:<id>:…" text is not read for the ID inside it.
    @Test
    fun anOldStyleCodeIsNotReadForTheShipmentIdInsideIt() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()

        vm.onCodeScanned("CCH:SHIP:SHIP-1:Hub:Clinic:LOG-1").join()

        assertNull(simulator.activeShipment.value)
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun aKnownCodeOpensTheShipmentToWatch() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()

        vm.onCodeScanned("SHIP-1").join()

        assertEquals("SHIP-1", vm.state.value.shipment?.id)
        assertTrue(vm.state.value.watching)
        assertTrue(simulator.isMonitorOnly.value)
    }

    @Test
    fun whenCustodyMovesAwayRecordingStopsAndTheHandoverIsShown() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        vm.takeCustody().join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }

        server.custody["SHIP-1"] = CustodyState(2, "receiver")
        advanceTimeBy(4_000)

        assertEquals("SHIP-1", vm.state.value.handedOverShipmentId)
        assertFalse(simulator.isRunning.value)
        assertNull(simulator.activeShipment.value)
        visible.cancel()
    }

    @Test
    fun whileWatchingCustodyComingToMeMakesMeTheCustodian() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }
        advanceTimeBy(3_500)
        assertFalse(vm.state.value.isCustodian)

        server.custody["SHIP-1"] = CustodyState(2, "receiver")
        advanceTimeBy(3_500)

        assertTrue(vm.state.value.isCustodian)
        assertFalse(vm.state.value.watching)
        visible.cancel()
    }

    @Test
    fun whileWatchingTheCouriersLocationIsFetched() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        server.gpsPoints += GpsPoint("G-1", "SHIP-1", 1.5, 2.5, 10f, 1_700_000_100_000)
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }

        advanceTimeBy(500)

        assertEquals(1.5, vm.state.value.remoteGps?.latitude ?: 0.0, 0.0)
        visible.cancel()
    }

    // The ViewModel outlives a tab switch, so one shipment's courier location must not show for the next.
    @Test
    fun openingAnotherShipmentClearsTheLastOnesLocationAndCustody() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        server.seedShipment("SHIP-2", custodian = "courier")
        server.gpsPoints += GpsPoint("G-1", "SHIP-1", 1.5, 2.5, 10f, 1_700_000_100_000)
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }
        advanceTimeBy(500)
        assertNotNull(vm.state.value.remoteGps)

        vm.onCodeScanned("SHIP-2").join()
        advanceTimeBy(500)

        assertEquals("SHIP-2", vm.state.value.shipment?.id)
        assertNull(vm.state.value.remoteGps)
        assertNull(vm.state.value.gpsUploadStatus)
        visible.cancel()
    }

    @Test
    fun nothingIsPolledOnceTheScreenIsAway() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        vm.takeCustody().join()
        backgroundScope.launch { vm.runWhileVisible() }.cancel()

        server.custody["SHIP-1"] = CustodyState(2, "receiver")
        advanceTimeBy(30_000)

        assertNull(vm.state.value.handedOverShipmentId)
    }

    @Test
    fun theCustodianReportsTheirLocation() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        vm.takeCustody().join()

        vm.uploadLocation(1.0, 2.0, 5f, 1_700_000_200_000).join()

        assertEquals(1, server.gpsPoints.size)
        assertTrue(vm.state.value.gpsUploadStatus!!.startsWith("Synced"))
    }

    // Before 26 Nov recording always waited for the person after process death. Now it resumes when the process died
    // while recording (the recording store says so), and still waits when the person had paused it.
    @Test
    fun afterProcessDeathWhileRecordingTheShipmentComesBackAndRecordingResumes() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        first.onCodeScanned("SHIP-1").join()
        first.takeCustody().join()
        simulator.stopContinuousSimulation()

        // The process dies: the simulator and the ViewModel are new; the saved state, the database and the recording
        // store remain.
        simulator = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

        assertEquals("SHIP-1", restored.state.value.shipment?.id)
        assertEquals("SHIP-1", simulator.activeShipment.value?.id)
        assertTrue(simulator.isRunning.value)
        assertEquals("recording goes on in the background again", 2, backgroundStarts)
        assertTrue(restored.state.value.isCustodian)
        assertFalse(restored.state.value.watching)
        assertFalse(simulator.isMonitorOnly.value)
    }

    @Test
    fun afterProcessDeathWithRecordingPausedTheShipmentComesBackButRecordingWaitsForThePerson() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        first.onCodeScanned("SHIP-1").join()
        first.takeCustody().join()
        first.pauseOrResumeRecording()

        simulator = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

        assertEquals("SHIP-1", simulator.activeShipment.value?.id)
        assertFalse(simulator.isRunning.value)
        assertTrue(restored.state.value.isCustodian)
        // The custodian can resume recording; someone watching couldn't.
        assertFalse(simulator.isMonitorOnly.value)
    }

    @Test
    fun afterProcessDeathSomeoneWatchingIsStillOnlyWatching() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("receiver", MemberRole.RECEIVER)
        val saved = SavedStateHandle()
        viewModel(saved).onCodeScanned("SHIP-1").join()

        simulator = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

        assertEquals("SHIP-1", restored.state.value.shipment?.id)
        assertTrue(restored.state.value.watching)
        assertTrue(simulator.isMonitorOnly.value)
    }
}
