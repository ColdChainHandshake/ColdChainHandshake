package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.GpsPoint
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.recording.RecordedLocation
import com.coldchain.handshake.recording.RecordedLocations
import com.coldchain.handshake.recording.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** The newest location the recording service queued for SHIP-1, and whether it records location right now. */
    private val recorded = MutableStateFlow<RecordedLocation?>(null)
    private val locating = MutableStateFlow(false)

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
            account, shipments, workflow, simulator, history, server, telemetry, recorder,
            object : RecordedLocations {
                override val active: StateFlow<Boolean> = locating
                override fun latest(shipmentId: String) = if (shipmentId == "SHIP-1") recorded else flowOf(null)
            },
            { alertChecks++ },
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

    // Found on devices (27 Nov): courier A handed SHIP-2DE57A to courier B while it was IN_TRANSIT, and A's phone kept
    // that status after the receiver accepted it. Opening a shipment this phone already holds asks the server first.
    @Test
    fun openingAShipmentThisPhoneHoldsShowsTheServersStatusFromTheStart() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("courier", MemberRole.COURIER)
        workflow.open("SHIP-1").getOrThrow()
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(status = ShipmentStatus.ACCEPTED)
        server.custody["SHIP-1"] = CustodyState(3, "receiver")
        val vm = viewModel()

        vm.onCodeScanned("SHIP-1").join()

        assertEquals(ShipmentStatus.ACCEPTED, vm.state.value.shipment?.status)
        assertEquals(ShipmentStatus.ACCEPTED, shipments.getShipment("SHIP-1").first()?.status)
    }

    @Test
    fun withNoSignalAShipmentThisPhoneHoldsStillOpensAsThisPhoneHasIt() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("courier", MemberRole.COURIER)
        workflow.open("SHIP-1").getOrThrow()
        server.offlineError = IOException("No signal")
        val vm = viewModel()

        vm.onCodeScanned("SHIP-1").join()

        assertEquals(ShipmentStatus.IN_TRANSIT, vm.state.value.shipment?.status)
        assertNull(vm.state.value.notice)
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

        // Since 27 Nov (finding 11) the recording service notices custody move, as RecordingLoopTest shows, and says
        // so through the simulator; this screen no longer reads custody every 3.5 s itself. Before, this test moved
        // custody on the server and waited for the screen's own poll.
        server.custody["SHIP-1"] = CustodyState(2, "receiver")
        simulator.onHandedOver(simulator.activeShipment.value!!)
        advanceTimeBy(100)

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
        // Within the watcher's longest wait (30 s since finding 11; it was one 3 s round before).
        advanceTimeBy(31_000)

        assertTrue(vm.state.value.isCustodian)
        assertFalse(vm.state.value.watching)
        visible.cancel()
    }

    /** The courier's phone sends reading [seq] of SHIP-1, a minute after the one before. */
    private fun courierSends(genesis: String, seq: Int) {
        val chain = HashChainService.buildChain(
            (1..seq).map {
                TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", 1_700_000_000_000L + it * 60_000L, 5.0, "", "", SyncStatus.PENDING)
            },
            genesis
        )
        val watcher = server.currentUser
        server.currentUser = "courier"
        kotlinx.coroutines.runBlocking {
            server.appendReadings("SHIP-1", listOf(chain.last()), chain.getOrNull(seq - 2)?.currentHash ?: genesis, "courier-phone", "s")
                .getOrThrow()
        }
        server.currentUser = watcher
    }

    // Finding 11. On the dev project on 27 Nov one phone watching a shipment made about 89 requests a minute: custody
    // twice, the location, the head, the readings and the shipment, every ~4 s. While nothing changes it now asks
    // only for the head and custody, less and less often, up to every 30 s.
    @Test
    fun whileWatchingAndNothingChangesTheServerIsAskedLessAndLess() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }

        advanceTimeBy(120_000)
        val before = server.requests
        advanceTimeBy(60_000)
        val inTheThirdMinute = server.requests - before

        visible.cancel()
        assertTrue("$inTheThirdMinute requests in the third minute", inTheThirdMinute <= 6)
    }

    // What the watcher's note rests on: when this phone last heard from the server, and whether it can't now. A quiet
    // courier and the watcher's own lost connection must not look alike.
    @Test
    fun whileWatchingTheScreenKnowsWhenItLastReachedTheServerAndWhenItCannot() = runTest {
        server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }
        advanceTimeBy(1_000)
        val reached = vm.state.value.serverCheckedAt
        assertTrue("checked: $reached", reached != null)
        assertFalse(vm.state.value.serverUnreachable)

        server.offlineError = IOException("No signal")
        advanceTimeBy(35_000)
        assertTrue(vm.state.value.serverUnreachable)
        assertEquals(reached, vm.state.value.serverCheckedAt)

        server.offlineError = null
        advanceTimeBy(35_000)
        assertFalse(vm.state.value.serverUnreachable)
        visible.cancel()
    }

    @Test
    fun aReadingArrivingWhileWatchingIsFetchedAndTheWatcherAsksQuicklyAgain() = runTest {
        val genesis = server.seedShipment("SHIP-1", custodian = "courier")
        signInAs("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }
        advanceTimeBy(120_000)

        // Backed off to 30 s by now, so the first new reading is fetched within 30 s...
        courierSends(genesis, 1)
        var waited = 0L
        while (telemetry.getTemperatures("SHIP-1").first().isEmpty() && waited <= 30_000) {
            advanceTimeBy(500)
            waited += 500
        }
        assertTrue("fetched after $waited ms", waited <= 30_000)

        // ...and having found it, the watcher asks again 3 s later.
        courierSends(genesis, 2)
        advanceTimeBy(3_500)
        assertEquals(2, telemetry.getTemperatures("SHIP-1").first().size)
        visible.cancel()
    }

    // 5.3, multi-leg custody: a second courier watching on Transit when custody comes to them starts recording, after
    // fetching the history so their phone carries the same chain on.
    @Test
    fun whileWatchingCustodyComingToMeAsACourierStartsRecordingHere() = runTest {
        val genesis = server.seedShipment("SHIP-1", custodian = "courier")
        courierSends(genesis, 1)
        courierSends(genesis, 2)
        signInAs("courier-b", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        val visible = backgroundScope.launch { vm.runWhileVisible() }
        advanceTimeBy(3_500)
        assertFalse(simulator.isRunning.value)

        server.custody["SHIP-1"] = CustodyState(2, "courier-b")
        advanceTimeBy(31_000)

        assertTrue(vm.state.value.isCustodian)
        assertFalse(vm.state.value.watching)
        assertTrue("recording on this phone", simulator.isRunning.value)
        assertFalse(simulator.isMonitorOnly.value)
        assertEquals(1, backgroundStarts)
        // The two readings courier A sent, then any this phone has made since, as one chain from the server's genesis.
        val held = telemetry.getTemperatures("SHIP-1").first().sortedBy { it.seq }
        assertTrue("${held.size} readings", held.size >= 2)
        assertTrue(HashChainService.verifyChain(held, genesis).valid)
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
        assertNull(vm.state.value.myLocation)
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

    // Location moved from this screen to the recording service on 27 Nov (MASTER 5.5 item 2): the screen no longer
    // sends it, it shows what the service recorded. Sending is in SyncServiceTest, recording in LocationRecorderTest
    // and RecordingServiceTest. This replaces theCustodianReportsTheirLocation, which tested the screen's own upload.
    @Test
    fun theCustodianSeesTheNewestLocationThisPhoneRecordedAndWhetherItWasSent() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        vm.takeCustody().join()
        assertNull(vm.state.value.myLocation)

        recorded.value = RecordedLocation(9.98, 76.28, 12f, 1_700_000_200_000, sent = false)
        assertEquals(false, vm.state.value.myLocation?.sent)

        recorded.value = recorded.value!!.copy(sent = true)
        assertEquals(true, vm.state.value.myLocation?.sent)
    }

    // The location card says location is recorded only while the service really records it. Allowed is not enough:
    // after a restart in the background, or with location turned off on the phone, Android refuses it.
    @Test
    fun theScreenSaysLocationIsRecordedOnlyWhileTheServiceRecordsIt() = runTest {
        val vm = viewModel()
        assertFalse(vm.state.value.locating)

        locating.value = true
        assertTrue(vm.state.value.locating)

        locating.value = false
        assertFalse(vm.state.value.locating)
    }

    @Test
    fun allowingLocationLaterAsksTheRecordingAlreadyRunningToAddIt() = runTest {
        dispatched("SHIP-1")
        signInAs("courier", MemberRole.COURIER)
        val vm = viewModel()
        vm.onCodeScanned("SHIP-1").join()
        vm.takeCustody().join()
        val before = backgroundStarts

        vm.keepRecordingInBackground()

        assertEquals(before + 1, backgroundStarts)
    }

    @Test
    fun allowingLocationWithNothingRecordingStartsNothing() = runTest {
        val vm = viewModel()

        vm.keepRecordingInBackground()

        assertEquals(0, backgroundStarts)
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
