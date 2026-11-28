package com.coldchain.handshake.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityOptionsCompat
import com.coldchain.handshake.repository.ShipmentMember
import com.coldchain.handshake.logger.LoggerInfo
import com.coldchain.handshake.logger.LoggerLink
import com.coldchain.handshake.logger.VirtualLogger
import com.coldchain.handshake.repository.CustodyState
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
import com.coldchain.handshake.crypto.ShipmentIntegrityEvaluator
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
import com.coldchain.handshake.simulator.LocalLoggers
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.ui.screens.HandoverScreen
import com.coldchain.handshake.ui.screens.LOGGER_CHECK_WAITING
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import org.robolectric.Shadows.shadowOf
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.ui.screens.HandoverViewModel
import kotlinx.coroutines.asCoroutineDispatcher
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
    /** The simulator's own thread, so tearDown can wait for a reading being made before closing the database. */
    private val ticker = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Debug local mode's virtual loggers, as the app's module provides them. */
    private val localLoggers = LocalLoggers()
    private val simulator = TemperatureSimulator(
        telemetryRepository = telemetry,
        chaosEngineService = DebugTools.createChaosService(),
        safetyEngine = safetyEngine,
        dispatcher = ticker.asCoroutineDispatcher(),
        localLoggers = localLoggers
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
        deviceId = { "this-phone" },
        loggerLink = { reachableLogger }
    )

    /** The shipment's logger, when a test lets this phone reach it. */
    private var reachableLogger: LoggerLink? = null

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
        localLoggers,
        SavedStateHandle()
    ).also { viewModels += it }

    @After
    fun tearDown() {
        // The screen and its ViewModel go first, or what they're still collecting would outlive the database.
        showing = false
        compose.waitForIdle()
        viewModels.forEach { it.viewModelScope.cancel() }
        // A courier taking over starts recording, whose ticks write to the database.
        simulator.stopContinuousSimulation()
        ticker.shutdown()
        ticker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
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
        offlineOnArrival: Boolean = false,
        releasedTo: String = "receiver",
        permissions: ActivityResultRegistry? = null,
        thenOnTheServer: () -> Unit = {}
    ) {
        runBlocking {
            if (uploaded.isNotEmpty()) server.appendReadings("SHIP-1", uploaded, genesis, "courier-phone", "s").getOrThrow()
            if (courierReleased) courierReleases(releasedTo)
            thenOnTheServer()
            server.currentUser = user
            val opened = ShipmentWorkflow(server, shipments) { "this-phone" }.open("SHIP-1").getOrThrow()
            account.setState(AccountState.Ready(user, null, UserProfile(user, role)))
            screenAccount = account
            simulator.attachShipmentForMonitoring(opened)
        }
        if (offlineOnArrival) server.offlineError = IOException("No connection")
        val viewModel = screenViewModel()
        compose.setContent {
            if (permissions == null) {
                if (showing) HandoverScreen(viewModel = viewModel)
            } else {
                val registry: ActivityResultRegistry = permissions
                val owner = object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    if (showing) HandoverScreen(viewModel = viewModel)
                }
            }
        }
    }

    private fun courierReleases(to: String = "receiver") {
        val user = server.currentUser
        server.currentUser = "courier"
        runBlocking {
            server.confirmHandover(
                HandoverConfirmation("SHIP-1", 1, "courier-phone", server.heads.getValue("SHIP-1").headHash, toUserId = to)
            ).getOrThrow()
        }
        server.currentUser = user
    }

    /** Answers every permission request with yes, and keeps what was asked and how many confirmations were sent by then. */
    private inner class AnsweringPermissions : ActivityResultRegistry() {
        val asked = mutableListOf<Pair<List<String>, Int>>()
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            val wanted = (input as Array<*>).map { it as String }
            asked += wanted to server.confirmations.size
            dispatchResult(requestCode, wanted.associateWith { true })
        }
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

    // Found on the emulator (27 Nov): a second courier taking over on this screen was never asked for Nearby devices or
    // location, as Take Custody on Transit asks, so their recording ran only while the app was open.
    @Test
    fun aCourierTakingOverIsAskedForWhatRecordingNeedsBeforeConfirming() {
        server.members.getValue("SHIP-1") += ShipmentMember("courier-b", MemberRole.COURIER, "Cody")
        val permissions = AnsweringPermissions()
        showOnServer("courier-b", MemberRole.COURIER, courierReleased = true, releasedTo = "courier-b", permissions = permissions)
        val before = server.confirmations.size

        await("Record PASS handover").performClick()

        compose.waitUntil(10_000) { idleMain(); server.confirmations.any { it.userId == "courier-b" } }
        val (wanted, confirmationsThen) = permissions.asked.single()
        assertTrue(wanted.toString(), Manifest.permission.BLUETOOTH_CONNECT in wanted)
        assertTrue(wanted.toString(), Manifest.permission.ACCESS_FINE_LOCATION in wanted)
        assertEquals("asked before confirming", before, confirmationsThen)
        assertEquals("courier-b", server.custody.getValue("SHIP-1").custodianId)
    }

    @Test
    fun aReceiverTakingOverIsAskedForNothing() {
        val permissions = AnsweringPermissions()
        showOnServer("receiver", MemberRole.RECEIVER, courierReleased = true, permissions = permissions)

        await("Record PASS handover").performClick()

        await("Custody has moved. The shipment is now ACCEPTED.")
        assertTrue(permissions.asked.toString(), permissions.asked.isEmpty())
    }

    // Found on the emulator (27 Nov): SHIP-2DE57A, accepted with a PASS, showed "UNKNOWN (gap in the readings)" on a
    // phone that opened it two hours later, because the silence after its last reading was counted up to then.
    @Test
    fun aShipmentOpenedAfterTheReceiverTookItIsCheckedUpToTheHandover() {
        val lastReading = now - 5 * minute

        showOnServer("sender", MemberRole.SENDER, uploaded = readings(lastAt = lastReading, count = 6, genesisHash = genesis)) {
            server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(status = ShipmentStatus.ACCEPTED)
            server.custody["SHIP-1"] = CustodyState(2, "receiver", sinceMs = lastReading + 30_000L)
        }

        await("Temperature Result: ✓ IN RANGE")
        assertTrue(compose.onAllNodes(hasText("UNKNOWN (gap", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun whileACourierHoldsItTheSilenceSinceTheLastReadingStillCounts() {
        showOnServer("sender", MemberRole.SENDER, uploaded = readings(lastAt = now - 5 * minute, count = 6, genesisHash = genesis))

        await("Temperature Result: UNKNOWN (gap in the readings)")
    }

    // 4.1: the screen shows the same logger check the confirmation records, with its reason (MASTER 5.4 item 4).
    @Test
    fun aLoggerOnRecordThatThisPhoneCannotReachIsShownAndHoldsTheVerdict() {
        showOnServer("receiver", MemberRole.RECEIVER, courierReleased = true) {
            server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(
                loggerPublicKeyHex = "04" + "6b".repeat(64), loggerStartSeq = 0
            )
        }

        await("The shipment's logger is on record, but this phone can't reach it, so it wasn't read.")
        await("FINAL VERDICT: UNKNOWN")
        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    // Gate 4 review (28 Nov): a logger whose records differ from the readings is shown, and holds the verdict at FAIL.
    @Test
    fun aLoggerWhoseHistoryDiffersFromTheReadingsIsShownAsAFail() {
        val uploaded = readings(genesisHash = genesis)
        val logger = VirtualLogger("LOG-1").apply {
            uploaded.forEach { record(it.timestamp, if (it.seq == 5L) 900 else (it.temperature * 100).toInt()) }
        }
        reachableLogger = logger
        showOnServer("receiver", MemberRole.RECEIVER, uploaded = uploaded, courierReleased = true) {
            server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(
                loggerPublicKeyHex = logger.publicKeyUncompressed.joinToString("") { "%02x".format(it) },
                loggerStartSeq = 0
            )
        }

        await("LOGGER: FAILED")
        await("FINAL VERDICT: FAIL")
        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    private fun enrol(logger: VirtualLogger) {
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(
            loggerPublicKeyHex = logger.publicKeyUncompressed.joinToString("") { "%02x".format(it) },
            loggerStartSeq = 0
        )
    }

    // Gate 4 review: the verdict fell back to "not checked" before the logger check came back, so it could show PASS
    // over an enrolled logger for a moment. Until the check is back, nothing is judged or offered.
    @Test
    fun beforeTheLoggerCheckComesBackNothingIsJudgedOrOffered() {
        val logger = VirtualLogger("LOG-1")
        reachableLogger = object : LoggerLink by logger {
            override suspend fun info(): Result<LoggerInfo> = kotlinx.coroutines.awaitCancellation()
        }
        showOnServer("receiver", MemberRole.RECEIVER, courierReleased = true) { enrol(logger) }

        await(LOGGER_CHECK_WAITING)
        await("Waiting for the Logger Check...").assertIsNotEnabled()
        assertTrue(compose.onAllNodes(hasText("FINAL VERDICT: PASS")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    // Gate 4 review G4-7: the screen read the logger again on every look at the server. It reads it once per head; a
    // confirmation, which refreshes the rest of the screen, doesn't read it again.
    @Test
    fun theScreenReadsTheLoggerOncePerHeadNotOnEachRefresh() {
        val uploaded = readings(genesisHash = genesis)
        val logger = VirtualLogger("LOG-1").apply { uploaded.forEach { record(it.timestamp, it.tempCentiC) } }
        var reads = 0
        reachableLogger = object : LoggerLink by logger {
            override suspend fun info(): Result<LoggerInfo> { reads++; return logger.info() }
        }
        showOnServer("courier", MemberRole.COURIER, uploaded = uploaded) { enrol(logger) }
        // Read before the history reached this phone, then again once it had: two heads.
        await("LOGGER: PASSED")
        val readsAtTheHead = reads

        await("Confirm Handover").performClick()
        await(HandoverViewModel.RELEASED_NOTICE)
        idleMain()

        assertEquals(readsAtTheHead, reads)
    }

    // Gate 4 re-check R4-1: the screen keeps a logger check per head, not per shipment. A reading the logger never made
    // reaching the server after an honest check must turn the PASSED it showed into FAILED.
    @Test
    fun aNewHeadWithAReadingTheLoggerNeverMadeTurnsThePassIntoAFail() {
        val uploaded = readings(genesisHash = genesis)
        val logger = VirtualLogger("LOG-1").apply { uploaded.forEach { record(it.timestamp, it.tempCentiC) } }
        reachableLogger = logger
        showOnServer("receiver", MemberRole.RECEIVER, uploaded = uploaded) { enrol(logger) }
        await("LOGGER: PASSED")

        // The courier's phone sends an 11th reading that its logger has no record of.
        val eleven = readings(lastAt = now, count = 11, genesisHash = genesis)
        server.currentUser = "courier"
        runBlocking {
            // readings() names events by how far back they are, so the 11th needs an ID of its own.
            server.appendReadings("SHIP-1", listOf(eleven.last().copy(id = "TE-11")), server.heads.getValue("SHIP-1").headHash, "courier-phone", "s").getOrThrow()
        }
        server.currentUser = "receiver"
        viewModels.last().retryHistory()

        await("LOGGER: FAILED")
        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    // Gate 4 re-check R4-2: in local mode the check runs again whenever the readings change, so a rewrite made while
    // Handover is open shows at once.
    @Test
    fun aRewriteWhileTheLocalHandoverIsOpenTurnsThePassIntoAFail() {
        recordLocallyAndShow()
        await("LOGGER: PASSED")

        runBlocking { DebugTools.rewriteHistoryFrom(db, "SHIP-1", fromSeq = 6, temperature = 4.0) }

        await("LOGGER: FAILED")
        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    /**
     * Debug local mode (4.1, MASTER 5.4 item 2): the simulator makes the readings, so the shipment's virtual logger
     * on this phone recorded them too. [beforeShowing] runs once they are stored.
     */
    private fun recordLocallyAndShow(beforeShowing: suspend () -> Unit = {}) {
        runBlocking {
            shipments.adoptRemoteShipment(localShipment).getOrThrow()
            simulator.attachShipment(localShipment, startTimestamp = now - 11 * minute)
            repeat(10) { simulator.tickOnce() }
            beforeShowing()
        }
        simulator.attachShipmentForMonitoring(localShipment)
        val viewModel = screenViewModel()
        compose.setContent { if (showing) HandoverScreen(viewModel = viewModel) }
    }

    @Test
    fun anHonestLocalTripShowsTheLoggerCheckPassed() {
        recordLocallyAndShow()

        await("LOGGER: PASSED")
        await("Record PASS handover")
    }

    @Test
    fun aChaosLabRewriteThatKeepsTheChainAddingUpIsCaughtByTheLoggerOnScreen() {
        recordLocallyAndShow {
            assertEquals(5, DebugTools.rewriteHistoryFrom(db, "SHIP-1", fromSeq = 6, temperature = 4.0))
        }

        // The record on this phone still adds up: only the logger knows what was measured.
        await(ShipmentIntegrityEvaluator.RECORD_UNBROKEN)
        await("LOGGER: FAILED")
        await("FINAL VERDICT: FAIL")
        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    // Gate 5 re-check, mutant 28 (S3 on screen): the verdict is worked out again at each look at the server, so a courier
    // falling silent turns PASS into UNKNOWN with no new reading arriving. The last reading is 6 s short of the product's
    // longest allowed silence; the history polls every 3 s, then 6 s.
    @Test
    fun aCourierFallingSilentTurnsThePassIntoUnknownWithNoNewReading() {
        val lastAt = System.currentTimeMillis() - ProductProfiles.DEFAULT.maxSilenceMs + 6_000
        showOnServer("receiver", MemberRole.RECEIVER, uploaded = readings(lastAt = lastAt, genesisHash = genesis), courierReleased = true)

        await("FINAL VERDICT: PASS")
        compose.waitUntil(25_000) {
            idleMain()
            compose.onAllNodes(hasText("FINAL VERDICT: UNKNOWN")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // Gate 5 review S8: a receiver can no longer read where the courier has been, so the screen says that plainly rather
    // than "no GPS fix recorded", which would be false.
    @Test
    fun aReceiverIsToldTheLocationIsNotTheirsToSee() {
        server.gpsPoints += com.coldchain.handshake.repository.GpsPoint("G-1", "SHIP-1", 1.5, 2.5, 10f, now - minute)
        showOnServer("receiver", MemberRole.RECEIVER, courierReleased = true)

        await(com.coldchain.handshake.ui.screens.LocationPrivacy.HIDDEN)
        assertTrue(compose.onAllNodes(hasText("No GPS fix recorded for this consignment.")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theSenderCanOnlyWatch() {
        showOnServer("sender", MemberRole.SENDER)

        await("Only the people handing over can confirm.")

        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Confirm Handover")).fetchSemanticsNodes().isEmpty())
    }
}
