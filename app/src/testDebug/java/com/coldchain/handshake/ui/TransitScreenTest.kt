package com.coldchain.handshake.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.recording.RecordedLocation
import com.coldchain.handshake.recording.RecordedLocations
import com.coldchain.handshake.recording.RecordingStore
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
import com.coldchain.handshake.ui.navigation.ActiveShipmentRestorer
import com.coldchain.handshake.ui.screens.TransitScreen
import com.coldchain.handshake.ui.screens.TransitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Transit screen's part in background recording: what it asks for, and what it does when permissions change in
 * Settings while it is away. The ViewModel's own behaviour is in `TransitViewModelTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h6000dp")
class TransitScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = FakeAccountRepository().apply {
        setState(AccountState.Ready("courier", null, UserProfile("courier", MemberRole.COURIER)))
    }
    private val shipments = InMemoryShipmentRepository()
    private val telemetry = InMemoryTelemetryRepository()
    private val server = FakeColdChainServer(currentUser = "courier")
    private val workflow = ShipmentWorkflow(server, shipments) { "device-1" }
    private val simulator = TemperatureSimulator(telemetry, dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis)
    private val store = RecordingStore.inMemory()
    /** How often the background recording was asked to run: each ask lets it move into the foreground if it may. */
    private var backgroundAsks = 0
    private val recorder = CourierRecorder(simulator, store) { backgroundAsks++ }

    @After
    fun tearDown() = simulator.stopContinuousSimulation()

    private fun recordingWithoutNearbyDevices() {
        server.seedShipment("SHIP-1", custodian = "courier")
        recorder.start(runBlocking { workflow.open("SHIP-1").getOrThrow() })
        val viewModel = TransitViewModel(
            account, shipments, workflow, simulator, ShipmentHistorySyncCoordinator(telemetry, server), server,
            telemetry, recorder,
            object : RecordedLocations {
                override val active: StateFlow<Boolean> = MutableStateFlow(false)
                override fun latest(shipmentId: String) = flowOf<RecordedLocation?>(null)
            },
            {}, ActiveShipmentRestorer(simulator, shipments, server, account, recorder), SavedStateHandle()
        )
        compose.setContent { TransitScreen(viewModel = viewModel) }
        compose.waitForIdle()
    }

    private fun awayInSettings(allow: List<String>) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(compose.activity.application).grantPermissions(*allow.toTypedArray())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    // Found on the emulator (27 Nov): Nearby devices allowed in Settings while recording, and back in the app the
    // banner was gone but the recording service stayed out of the foreground, so it still stopped with the app.
    @Test
    fun nearbyDevicesAllowedInSettingsMovesTheRunningRecordingIntoTheForeground() {
        recordingWithoutNearbyDevices()
        val before = backgroundAsks

        awayInSettings(allow = listOf(Manifest.permission.BLUETOOTH_CONNECT))

        assertEquals(before + 1, backgroundAsks)
    }

    @Test
    fun comingBackWithNothingAllowedAsksNothingMore() {
        recordingWithoutNearbyDevices()
        val before = backgroundAsks

        awayInSettings(allow = emptyList())

        assertEquals(before, backgroundAsks)
    }
}
