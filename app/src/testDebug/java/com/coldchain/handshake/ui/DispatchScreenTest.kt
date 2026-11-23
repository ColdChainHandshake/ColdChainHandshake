package com.coldchain.handshake.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.alerts.AlertEscalationScheduler
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import org.robolectric.Shadows.shadowOf
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.ui.screens.DispatchScreen
import com.coldchain.handshake.ui.screens.DispatchViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h6000dp")
class DispatchScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    // Everything the screen uses is built here, with no global state and no real server client, so the result
    // doesn't depend on which test ran before (the order dependence recorded on 7 Nov).
    private val server = FakeColdChainServer(currentUser = "sender")
    private val simulator = TemperatureSimulator(telemetry, wallClock = System::currentTimeMillis)
    private val viewModel = DispatchViewModel(
        account = FakeAccountRepository(),
        shipments = shipments,
        workflow = ShipmentWorkflow(server, shipments) { "device-1" },
        simulator = simulator,
        history = ShipmentHistorySyncCoordinator(telemetry, server),
        saved = SavedStateHandle()
    )

    private var navigatedToTransit = false
    private var showing by mutableStateOf(true)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        compose.setContent {
            if (showing) DispatchScreen(onNavigateToTransit = { navigatedToTransit = true }, viewModel = viewModel)
        }
    }

    @After
    fun tearDown() {
        // The screen goes first, or what it's still collecting would outlive the database.
        showing = false
        compose.waitForIdle()
        simulator.stopContinuousSimulation()
        db.close()
    }

    // The ViewModel resumes on the main looper, which Robolectric pauses and Compose's waitUntil doesn't run.
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun stored(): Shipment? {
        idleMain()
        return runBlocking { shipments.getAllShipments().first().singleOrNull() }
    }

    private fun click(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performClick()
    }

    private fun createShipment(product: String = ProductProfiles.DEFAULT.name) {
        click("Demo Preset")
        compose.onNode(hasText(product) and isSelectable()).performClick()
        click("Create Shipment")
        compose.waitUntil(10_000) { stored() != null }
    }

    private fun startJourney() {
        createShipment()
        click("Dispatch Shipment (Seal Cargo)")
        compose.waitUntil(10_000) { stored()?.status == ShipmentStatus.DISPATCHED }
        click("Start Journey → Enter IN_TRANSIT")
        compose.waitUntil(10_000) { stored()?.status == ShipmentStatus.IN_TRANSIT }
        compose.waitUntil(10_000) { idleMain(); navigatedToTransit }
    }

    @Test
    fun aNewShipmentKeepsTheChosenProduct() {
        createShipment(ProductProfiles.FREEZE_TOLERANT.name)

        assertEquals(ProductProfiles.FREEZE_TOLERANT.id, stored()?.productProfileId)
    }

    @Test
    fun startingTheJourneyRecordsWhenItStarted() {
        val before = System.currentTimeMillis()

        startJourney()

        val startedAt = stored()?.journeyStartedAt
        assertNotNull(startedAt)
        assertTrue(startedAt!! >= before)
    }

    @Test
    fun startingTheJourneyStartsWatchingForASilentLogger() {
        startJourney()

        val checks = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AlertEscalationScheduler.WORK_NAME)
            .get()
        assertTrue(checks.isNotEmpty())
    }
}
