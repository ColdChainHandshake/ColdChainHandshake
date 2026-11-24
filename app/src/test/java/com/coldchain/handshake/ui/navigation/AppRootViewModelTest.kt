package com.coldchain.handshake.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.repository.impl.InMemoryShipmentRepository
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which screen the app opens on. The rule that matters most: someone's records never show until the phone has
 * checked they belong to the person signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppRootViewModelTest {

    private val account = FakeAccountRepository()
    private val session = FakeSession()
    private val ready = AccountState.Ready("user-1", "a@example.test", UserProfile("Ana", MemberRole.COURIER))
    private val shipments = InMemoryShipmentRepository()
    private val telemetry = InMemoryTelemetryRepository()
    private val server = FakeColdChainServer(currentUser = "user-1")
    private var simulator = newSimulator()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        simulator.stopContinuousSimulation()
        Dispatchers.resetMain()
    }

    private fun newSimulator() = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        AppRootViewModel(account, session, simulator, ActiveShipmentRestorer(simulator, shipments, server, account), saved)

    /** The process dies: the simulator and the ViewModel are new, the saved state and the database remain. */
    private fun afterProcessDeath(saved: SavedStateHandle): AppRootViewModel {
        simulator = newSimulator()
        return viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
    }

    private suspend fun localShipment(): Shipment =
        Shipment("SHIP-1", "QR", "LOG-1", "A", "B", "W-1").also { shipments.saveShipment(it).getOrThrow() }

    @Test
    fun nobodySignedInSeesSignIn() = runTest {
        assertEquals(RootScreen.SignIn, viewModel().screen.value)
    }

    @Test
    fun aSignedInPersonSeesNothingUntilThePhoneHasCheckedTheyOwnItsRecords() = runTest {
        val vm = viewModel()
        account.setState(ready)
        assertEquals(RootScreen.Loading, vm.screen.value)

        session.confirmedOwner.value = "someone-else"
        assertEquals(RootScreen.Loading, vm.screen.value)

        session.confirmedOwner.value = "user-1"
        assertEquals(RootScreen.Main("Ana, Courier", "Sign Out", localOnly = false), vm.screen.value)
    }

    @Test
    fun aDeviceProblemOrARefusedSignOutIsWhatTheStatusLineSays() = runTest {
        session.confirmedOwner.value = "user-1"
        account.setState(ready.copy(deviceProblem = "This phone is registered to another account."))
        val vm = viewModel()
        assertEquals("This phone is registered to another account.", (vm.screen.value as RootScreen.Main).statusLine)

        session.signOutResult = Result.failure(IllegalStateException("3 readings still have to reach the server."))
        vm.signOut()
        assertEquals("3 readings still have to reach the server.", (vm.screen.value as RootScreen.Main).statusLine)
    }

    @Test
    fun eachAccountStateHasItsScreen() = runTest {
        val vm = viewModel()
        account.setState(AccountState.NeedsProfile("user-1", null))
        assertEquals(RootScreen.NeedsProfile, vm.screen.value)
        account.setState(AccountState.Unreachable("user-1", null))
        assertEquals(RootScreen.Unreachable, vm.screen.value)
        account.setState(AccountState.Loading)
        assertEquals(RootScreen.Loading, vm.screen.value)
    }

    @Test
    fun localModeIsLabelledAndSurvivesTheProcessBeingKilled() = runTest {
        val saved = SavedStateHandle()
        viewModel(saved).useWithoutAccount()

        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertEquals(RootScreen.Main("Local only. Nothing here reaches the server.", "Sign In", localOnly = true), restored.screen.value)

        restored.leaveLocalMode()
        assertEquals(RootScreen.SignIn, restored.screen.value)
    }

    @Test
    fun theReasonSomeoneWasSignedStraightOutIsPassedOn() = runTest {
        val vm = viewModel()
        session.localOwnerProblem.value = "This phone holds another person's records."
        assertEquals("This phone holds another person's records.", vm.ownerProblem.value)
    }

    // Found on the S24 (24 Nov): killed while on Handover or Alerts, the app came back with "No Active Shipment",
    // because only the Transit screen put the saved shipment back.
    @Test
    fun aShipmentOpenOnAnyScreenComesBackAfterTheProcessIsKilled() = runTest {
        val shipment = localShipment()
        val saved = SavedStateHandle()
        viewModel(saved).useWithoutAccount()
        simulator.attachShipment(shipment)

        afterProcessDeath(saved)

        assertEquals("SHIP-1", simulator.activeShipment.value?.id)
        assertFalse(simulator.isMonitorOnly.value)
        // Recording waits for the person, as on Transit.
        assertFalse(simulator.isRunning.value)
    }

    @Test
    fun aShipmentBeingWatchedComesBackOnlyWatched() = runTest {
        val shipment = localShipment()
        val saved = SavedStateHandle()
        viewModel(saved).useWithoutAccount()
        simulator.attachShipmentForMonitoring(shipment)

        afterProcessDeath(saved)

        assertEquals("SHIP-1", simulator.activeShipment.value?.id)
        assertTrue(simulator.isMonitorOnly.value)
    }

    @Test
    fun nothingComesBackUntilThePhoneHasCheckedWhoIsSignedIn() = runTest {
        server.seedShipment("SHIP-1", custodian = "user-1")
        val shipment = ShipmentWorkflow(server, shipments) { "device-1" }.open("SHIP-1").getOrThrow()
        account.setState(ready)
        session.confirmedOwner.value = "user-1"
        val saved = SavedStateHandle()
        viewModel(saved)
        simulator.attachShipmentForMonitoring(shipment)

        session.confirmedOwner.value = null
        afterProcessDeath(saved)
        assertNull(simulator.activeShipment.value)

        session.confirmedOwner.value = "user-1"
        assertEquals("SHIP-1", simulator.activeShipment.value?.id)
        // For a shipment on the server, the server's custody decides, not how it was last shown.
        assertFalse(simulator.isMonitorOnly.value)
    }

    @Test
    fun aShipmentThisPhoneNoLongerHoldsDoesNotComeBack() = runTest {
        val saved = SavedStateHandle()
        viewModel(saved).useWithoutAccount()
        simulator.attachShipment(Shipment("SHIP-GONE", "QR", "LOG-1", "A", "B", "W-1"))

        afterProcessDeath(saved)

        assertNull(simulator.activeShipment.value)
    }

    private class FakeSession : SessionGate {
        override val confirmedOwner = MutableStateFlow<String?>(null)
        override val localOwnerProblem = MutableStateFlow<String?>(null)
        var signOutResult: Result<Unit> = Result.success(Unit)
        override suspend fun signOut() = signOutResult
    }
}
