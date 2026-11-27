package com.coldchain.handshake.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.recording.RecordingStore
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.AlertRepositoryImpl
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.FakeNetworkMonitor
import com.coldchain.handshake.repository.ReadingUploader
import com.coldchain.handshake.debugtools.DebugTools
import com.coldchain.handshake.di.AppModule
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.ui.screens.HandoverScreen
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import org.robolectric.Shadows.shadowOf
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.ui.screens.HandoverViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Runs the real screen on a real database. Local mode is a shipment kept on this phone alone. Server
 * mode replaces only the server, with a fake that applies the same handover rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h6000dp")
class HandoverScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val now = System.currentTimeMillis()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val handovers = HandoverRepositoryImpl(db.handoverDao())
    private val alerts = AlertRepositoryImpl(db.alertDao())

    private val server = FakeColdChainServer(currentUser = "courier")
    private val genesis = server.seedShipment("SHIP-1", createdAt = now - 11 * minute)

    private var showing by mutableStateOf(true)
    private val account = FakeAccountRepository()

    private val sync = SyncServiceImpl(
        db.shipmentDao(), db.temperatureEventDao(), db.alertDao(), db.handoverDao(), db.gpsPointDao(), server,
        ReadingUploader(server, object : DeviceSigner {
            override val publicKey = "public-key"
            override fun sign(payload: ByteArray) = "signature"
        }, { "this-phone" }),
        account, FakeNetworkMonitor(), { "this-phone" }
    )

    // What the app's module builds, here from this test's repositories and fake server.
    private val safetyEngine = SafetyEngine(alerts, shipments)
    private val simulator = TemperatureSimulator(
        telemetryRepository = telemetry,
        chaosEngineService = DebugTools.createChaosService(),
        safetyEngine = safetyEngine
    )
    // In one transaction, as the app's module builds it: without it the handover row shows before the shipment's
    // final status, and a check between the two read INSPECTED (seen once in a full run on 26 Nov).
    private val orchestrator = AppModule.handoverOrchestratorFor(db, shipments, telemetry, handovers)
    private val history = ShipmentHistorySyncCoordinator(telemetry, server)
    private val serverHandover = ServerHandover(
        remote = server,
        orchestrator = orchestrator,
        shipments = shipments,
        handovers = handovers,
        history = history,
        uploadPending = { shipmentId -> sync.uploadReadings(shipmentId) },
        deviceId = { "this-phone" }
    )

    /** Signed out until a test opens a shipment from the server as someone. */
    private var screenAccount: AccountRepository = AccountRepository.NONE

    private val localShipment = Shipment(
        "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.ARRIVED, ProductProfiles.DEFAULT.id, now - 11 * minute
    )

    private fun readings(lastAt: Long = now - minute, count: Int = 10, genesisHash: String = HashChainService.UNBOUND_GENESIS) =
        HashChainService.buildChain(
            (count - 1 downTo 0).map { back ->
                TemperatureEvent("TE-$back", "SHIP-1", "LOG-1", lastAt - back * minute, 5.0, "", "", SyncStatus.PENDING)
            },
            genesisHash
        )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private val viewModels = mutableListOf<HandoverViewModel>()

    private fun screenViewModel() = HandoverViewModel(
        screenAccount,
        simulator,
        history,
        telemetry,
        alerts,
        handovers,
        server,
        serverHandover,
        orchestrator,
        CourierRecorder(simulator, RecordingStore.inMemory()) {},
        SavedStateHandle()
    ).also { viewModels += it }

    @After
    fun tearDown() {
        // The screen and its ViewModel go first, or what they're still collecting would outlive the database.
        showing = false
        compose.waitForIdle()
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private fun showLocal(events: List<TemperatureEvent> = readings(), monitorOnly: Boolean = true) {
        runBlocking {
            shipments.adoptRemoteShipment(localShipment).getOrThrow()
            telemetry.saveTemperatures(events).getOrThrow()
        }
        if (monitorOnly) {
            simulator.attachShipmentForMonitoring(localShipment)
        } else {
            simulator.attachShipment(localShipment)
        }
        val viewModel = screenViewModel()
        compose.setContent { if (showing) HandoverScreen(viewModel = viewModel) }
    }

    /**
     * The courier's phone uploaded these, and this person opened the shipment on their own phone. A
     * courier releasing is held against the readings at that moment, so it happens after the upload.
     */
    private fun showOnServer(
        user: String,
        role: MemberRole,
        uploaded: List<TemperatureEvent> = readings(genesisHash = genesis),
        courierReleased: Boolean = false,
        offlineOnArrival: Boolean = false
    ) {
        runBlocking {
            if (uploaded.isNotEmpty()) server.appendReadings("SHIP-1", uploaded, genesis, "courier-phone", "s").getOrThrow()
            if (courierReleased) courierReleases()
            server.currentUser = user
            val opened = ShipmentWorkflow(server, shipments) { "this-phone" }.open("SHIP-1").getOrThrow()
            account.setState(AccountState.Ready(user, null, UserProfile(user, role)))
            screenAccount = account
            simulator.attachShipmentForMonitoring(opened)
        }
        if (offlineOnArrival) server.offlineError = IOException("No connection")
        val viewModel = screenViewModel()
        compose.setContent { if (showing) HandoverScreen(viewModel = viewModel) }
    }

    private fun courierReleases() {
        val user = server.currentUser
        server.currentUser = "courier"
        runBlocking {
            server.confirmHandover(
                HandoverConfirmation("SHIP-1", 1, "courier-phone", server.heads.getValue("SHIP-1").headHash, toUserId = "receiver")
            ).getOrThrow()
        }
        server.currentUser = user
    }

    private fun await(text: String): SemanticsNodeInteraction {
        try {
            compose.waitUntil(10_000) { idleMain(); compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val shown = compose.onRoot(useUnmergedTree = true).printToString()
                .lines().filter { "Text = " in it }.joinToString(" | ") { it.trim() }
            throw AssertionError("\"$text\" never appeared. The screen shows: $shown", e)
        }
        return compose.onNodeWithText(text)
    }

    private fun choose(label: String) {
        compose.onNode(hasText(label) and isSelectable()).performClick()
    }

    private fun storedHandover() = idleMain().let { runBlocking { handovers.getHandover("SHIP-1").first() } }

    private fun storedStatus() = idleMain().let { runBlocking { shipments.getShipment("SHIP-1").first()?.status } }

    private fun awaitStored() {
        compose.waitUntil(10_000) { idleMain(); storedHandover() != null }
    }

    // The ViewModel resumes on the main looper, which Robolectric pauses and Compose's waitUntil doesn't run.
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // Local mode

    @Test
    fun aCleanJourneyRecordsTheReceiversInspection() {
        showLocal()

        choose("Stage 2 (usable)")
        choose("Passed")
        await("Record PASS handover").performClick()

        awaitStored()
        val stored = storedHandover()
        assertEquals(HandoverVerdict.PASS, stored?.verdict)
        assertEquals(VvmStage.STAGE_2, stored?.vvmStage)
        assertEquals(ShakeTestResult.PASSED, stored?.shakeTest)
        assertEquals(ShipmentStatus.ACCEPTED, storedStatus())
    }

    @Test
    fun theReceiversOwnVerdictAndReasonAreRecorded() {
        showLocal()

        await("Record PASS handover")
        choose("Fail: quarantine the shipment")
        compose.onNodeWithText("Reason").performTextInput("Seal broken")
        compose.onNodeWithText("Record FAIL handover").performClick()

        awaitStored()
        assertEquals(HandoverVerdict.FAIL, storedHandover()?.verdict)
        assertEquals("Seal broken", storedHandover()?.reason)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun aLocalHandoverSaysNobodyElseConfirms() {
        showLocal()

        await("Local only. On a real handover the courier and the receiver each confirm on their own phone, and the server moves custody.")
    }

    @Test
    fun whenThereAreNoReadingsTheReceiverCanStillRecordWhatTheyFound() {
        showLocal(events = emptyList())

        await("Record UNKNOWN handover").assertIsEnabled()
    }

    @Test
    fun aLoggerThatStoppedLongBeforeHandoverIsNotOfferedAsAPass() {
        showLocal(events = readings(lastAt = now - 30 * minute))

        await("Record UNKNOWN handover")

        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aRecordedHandoverIsShownAndCannotBeRecordedAgain() {
        showLocal()
        await("Record PASS handover")
        runBlocking {
            orchestrator.processHandover(
                shipmentId = "SHIP-1",
                workerSigned = true,
                pharmacistSigned = true,
                declaredVerdict = HandoverVerdict.UNKNOWN,
                reason = "Logger damaged"
            ).getOrThrow()
        }

        await("Recorded: UNKNOWN. Reason: Logger damaged")
        await("Handover Recorded").assertIsNotEnabled()
    }

    @Test
    fun recordingAHandoverStopsTheShipmentsLogger() {
        showLocal(monitorOnly = false)

        await("Record PASS handover").performClick()

        // Waiting on the status itself: the logger was already running before, so nothing else proves it stopped.
        compose.waitUntil(10_000) {
            idleMain()
            simulator.activeShipment.value?.status == ShipmentStatus.ACCEPTED
        }
        assertTrue(simulator.isMonitorOnly.value)
    }

    @Test
    fun aServerShipmentCannotBeConfirmedBySomeoneSignedOut() {
        runBlocking {
            server.appendReadings("SHIP-1", readings(genesisHash = genesis), genesis, "courier-phone", "s").getOrThrow()
            val opened = ShipmentWorkflow(server, shipments) { "this-phone" }.open("SHIP-1").getOrThrow()
            simulator.attachShipmentForMonitoring(opened)
        }
        val viewModel = screenViewModel()
        compose.setContent { if (showing) HandoverScreen(viewModel = viewModel) }

        await("Sign in to confirm this shipment. It's on the server.")

        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    // On the server

    @Test
    fun theReceiverConfirmsOnTheServerAndCustodyMoves() {
        showOnServer("receiver", MemberRole.RECEIVER, courierReleased = true)

        await("Record PASS handover").performClick()

        await("Custody has moved. The shipment is now ACCEPTED.")
        assertEquals("receiver", server.confirmations.last().userId)
        assertEquals(ShipmentStatus.ACCEPTED, storedStatus())
    }

    @Test
    fun theReceiverConfirmingFirstWaitsForTheCourier() {
        showOnServer("receiver", MemberRole.RECEIVER)

        await("Record PASS handover").performClick()

        await("Confirmed. Waiting for the other person to confirm on their own phone.")
        assertEquals(ShipmentStatus.IN_TRANSIT, storedStatus())
    }

    @Test
    fun withNoSignalTheReceiverCanStillRecordAFail() {
        showOnServer("receiver", MemberRole.RECEIVER, offlineOnArrival = true)

        await("Try Again")
        choose("Fail: quarantine the shipment")
        compose.onNodeWithText("Reason").performTextInput("Seal broken")
        compose.onNodeWithText("Record FAIL handover").performClick()

        await("No connection. Your FAIL is saved on this phone and will be sent. Custody hasn't moved.")
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
        assertTrue(server.confirmations.isEmpty())
    }

    @Test
    fun recordingWaitsWhileTheHistoryIsStillDownloading() {
        server.neverFinishes = true
        showOnServer("receiver", MemberRole.RECEIVER, uploaded = emptyList())

        await("Waiting for Complete History...").assertIsNotEnabled()
    }

    @Test
    fun theCustodianNamesWhoTakesOverAndConfirms() {
        showOnServer("courier", MemberRole.COURIER)

        await("Confirm Handover").performClick()

        await(HandoverViewModel.RELEASED_NOTICE)
        val confirmation = server.confirmations.single()
        assertEquals("courier", confirmation.userId)
        assertEquals("receiver", confirmation.details.toUserId)
        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theSenderCanOnlyWatch() {
        showOnServer("sender", MemberRole.SENDER)

        await("Only the people handing over can confirm.")

        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Confirm Handover")).fetchSemanticsNodes().isEmpty())
    }
}
