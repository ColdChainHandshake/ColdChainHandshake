package com.coldchain.handshake.ui.screens

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.AlertRepositoryImpl
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.crypto.HashChainService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The Handover screen without the screen, on a real database: what it loads while shown, the inspection answers
 * surviving process death, and each action. `HandoverScreenTest` still drives the screen itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandoverViewModelTest {

    private val minute = 60_000L
    private val now = System.currentTimeMillis()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val handovers = HandoverRepositoryImpl(db.handoverDao())
    private val alerts = AlertRepositoryImpl(db.alertDao())
    private val account = FakeAccountRepository()
    private val server = FakeColdChainServer(currentUser = "courier")
    private val genesis = server.seedShipment("SHIP-1", createdAt = now - 11 * minute)
    private val history = ShipmentHistorySyncCoordinator(telemetry, server)
    private val orchestrator = HandoverOrchestrator(shipments, telemetry, handovers)
    private val serverHandover = ServerHandover(
        server, orchestrator, shipments, handovers, history, { Result.success(Unit) }, { "this-phone" }
    )
    private val simulator = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)
    private val visible = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        visible.cancel()
        simulator.stopContinuousSimulation()
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = HandoverViewModel(
        account, simulator, history, telemetry, alerts, handovers, server, serverHandover, orchestrator, saved
    )

    private fun readings(count: Int = 10, genesisHash: String = genesis) = HashChainService.buildChain(
        (count - 1 downTo 0).map { back ->
            TemperatureEvent("TE-$back", "SHIP-1", "LOG-1", now - minute - back * minute, 5.0, "", "", SyncStatus.PENDING)
        },
        genesisHash
    )

    /** Signs [user] in, and opens the server's shipment on this phone the way the Transit screen does. */
    private fun openOnServer(user: String, role: MemberRole, uploaded: Boolean = true) = runBlocking {
        if (uploaded) server.appendReadings("SHIP-1", readings(), genesis, "courier-phone", "s").getOrThrow()
        server.currentUser = user
        account.setState(AccountState.Ready(user, null, UserProfile(user, role)))
        val opened = ShipmentWorkflow(server, shipments) { "this-phone" }.open("SHIP-1").getOrThrow()
        if (user == "courier") simulator.attachShipment(opened) else simulator.attachShipmentForMonitoring(opened)
    }

    private suspend fun HandoverViewModel.awaitState(test: (HandoverUiState) -> Boolean): HandoverUiState =
        withTimeout(5_000) { state.first(test) }

    @Test
    fun theInspectionAnswersSurviveProcessDeath() {
        val saved = SavedStateHandle()
        viewModel(saved).apply {
            setVvmStage(VvmStage.STAGE_2)
            setShakeTest(ShakeTestResult.PASSED)
        }

        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

        assertEquals(VvmStage.STAGE_2, restored.state.value.vvmStage)
        assertEquals(ShakeTestResult.PASSED, restored.state.value.shakeTest)
    }

    @Test
    fun whileShownItFetchesTheHistoryAndWhoIsOnTheShipment() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        assertNull(vm.state.value.custody)

        visible.launch { vm.runWhileVisible() }

        val loaded = vm.awaitState { it.custody != null && it.members.isNotEmpty() }
        assertEquals("courier", loaded.custody?.custodianId)
        assertEquals(3, loaded.members.size)
        withTimeout(5_000) { telemetry.getTemperatures("SHIP-1").first { it.size == 10 } }
        Unit
    }

    @Test
    fun releasingStopsRecordingAndSaysWhatHappened() = runBlocking {
        openOnServer("courier", MemberRole.COURIER)
        simulator.startContinuousSimulation(intervalMs = 60_000L)
        val vm = viewModel()

        vm.release("receiver").join()

        assertFalse(simulator.isRunning.value)
        val after = vm.state.value
        assertNull(after.error)
        assertTrue(after.notice != null)
        assertFalse(after.processing)
    }

    @Test
    fun withNoSignalAReceiversFailIsKeptOnThisPhone() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        visible.launch { vm.runWhileVisible() }
        withTimeout(5_000) { telemetry.getTemperatures("SHIP-1").first { it.size == 10 } }
        server.offlineError = IOException("No connection")

        vm.record(HandoverVerdict.FAIL, "Seal broken").join()

        assertTrue(vm.state.value.notice.orEmpty(), vm.state.value.notice.orEmpty().startsWith("No connection"))
    }

    @Test
    fun aLocalHandoverIsRecordedHereAndStopsTheLogger() = runBlocking {
        val local = Shipment("SHIP-L", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.ARRIVED, ProductProfiles.DEFAULT.id, now - 11 * minute)
        shipments.adoptRemoteShipment(local).getOrThrow()
        simulator.attachShipment(local)
        val vm = viewModel()

        vm.record(HandoverVerdict.FAIL, "Seal broken").join()

        val result = vm.state.value.result
        assertEquals(HandoverVerdict.FAIL, result?.handover?.verdict)
        assertTrue(simulator.isMonitorOnly.value)
    }
}
