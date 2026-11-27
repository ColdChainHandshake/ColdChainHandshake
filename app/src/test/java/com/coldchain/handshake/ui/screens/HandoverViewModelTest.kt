package com.coldchain.handshake.ui.screens

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.recording.RecordingStore
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.ShipmentMember
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
        // A ViewModel is cleared when its screen goes; here nothing clears them, so their work is stopped before the next
        // test sets Dispatchers.Main again (one still emitting meanwhile fails that test).
        created.forEach { it.viewModelScope.cancel() }
        simulator.stopContinuousSimulation()
        Dispatchers.resetMain()
        db.close()
    }

    private val created = mutableListOf<HandoverViewModel>()
    /** Which shipment this phone records, and how often the background recording was asked for. */
    private val recordingStore = RecordingStore.inMemory()
    private var backgroundStarts = 0
    private val recorder = CourierRecorder(simulator, recordingStore) { backgroundStarts++ }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = HandoverViewModel(
        account, simulator, history, telemetry, alerts, handovers, server, serverHandover, orchestrator, recorder, saved
    ).also { created += it }

    // 5.3, multi-leg custody: a second courier takes over on their own phone, which records from then on. Courier A's
    // phone stops when it sees custody move, so courier B's must start at once or the trip gets a gap.
    @Test
    fun aCourierTakingOverFromAnotherCourierStartsRecordingOnTheirPhone() = runBlocking {
        server.members.getValue("SHIP-1") += ShipmentMember("courier-b", MemberRole.COURIER, "Bea")
        openOnServer("courier-b", MemberRole.COURIER)
        server.currentUser = "courier"
        server.confirmHandover(
            HandoverConfirmation("SHIP-1", 1, "courier-phone", server.heads.getValue("SHIP-1").headHash, toUserId = "courier-b")
        ).getOrThrow()
        server.currentUser = "courier-b"
        val vm = viewModel()

        vm.record(HandoverVerdict.PASS, null).join()

        assertEquals("courier-b", server.custody.getValue("SHIP-1").custodianId)
        assertEquals(ShipmentStatus.IN_TRANSIT, server.shipments.getValue("SHIP-1").status)
        assertTrue("recording on courier B's phone", simulator.isRunning.value)
        assertFalse(simulator.isMonitorOnly.value)
        assertEquals("SHIP-1", recordingStore.shipmentId)
        assertEquals(1, backgroundStarts)
    }

    @Test
    fun aReceiverTakingOverRecordsNothing() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        server.currentUser = "courier"
        server.confirmHandover(
            HandoverConfirmation("SHIP-1", 1, "courier-phone", server.heads.getValue("SHIP-1").headHash, toUserId = "receiver")
        ).getOrThrow()
        server.currentUser = "receiver"
        val vm = viewModel()

        vm.record(HandoverVerdict.PASS, null).join()

        assertEquals(ShipmentStatus.ACCEPTED, server.shipments.getValue("SHIP-1").status)
        assertFalse(simulator.isRunning.value)
        assertEquals(0, backgroundStarts)
    }

    // Gate 5 review, mutant 20 and S6 (28 Nov): a receiver's FAIL leaves the shipment QUARANTINED, which counts as on
    // the move, and the take-over read the person's profile role. Someone whose profile says courier but who is this
    // shipment's receiver started recording a shipment that had arrived. Their role on the shipment decides.
    @Test
    fun theShipmentsReceiverRecordsNothingAfterAFailWhateverTheirProfileSays() = runBlocking {
        openOnServer("receiver", MemberRole.COURIER)
        server.currentUser = "courier"
        server.confirmHandover(
            HandoverConfirmation("SHIP-1", 1, "courier-phone", server.heads.getValue("SHIP-1").headHash, toUserId = "receiver")
        ).getOrThrow()
        server.currentUser = "receiver"
        val vm = viewModel()

        vm.record(HandoverVerdict.FAIL, "Box crushed").join()

        assertEquals("receiver", server.custody.getValue("SHIP-1").custodianId)
        assertEquals(ShipmentStatus.QUARANTINED, server.shipments.getValue("SHIP-1").status)
        assertFalse(simulator.isRunning.value)
        assertEquals(0, backgroundStarts)
    }

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

    // The ViewModel outlives a tab switch, so nothing about one shipment may stay on screen for the next.
    @Test
    fun openingAnotherShipmentClearsWhatBelongedToTheLastOne() = runBlocking {
        val first = Shipment("SHIP-A", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.ARRIVED, ProductProfiles.DEFAULT.id, now - 11 * minute)
        val second = first.copy(id = "SHIP-B")
        shipments.adoptRemoteShipment(first).getOrThrow()
        shipments.adoptRemoteShipment(second).getOrThrow()
        simulator.attachShipment(first)
        val vm = viewModel()
        vm.setVvmStage(VvmStage.STAGE_2)
        vm.record(HandoverVerdict.FAIL, "Seal broken").join()
        assertEquals("SHIP-A", vm.state.value.result?.handover?.shipmentId)

        simulator.attachShipment(second)

        val after = vm.awaitState { it.shipment?.id == "SHIP-B" }
        assertNull(after.result)
        assertNull(after.notice)
        assertNull(after.error)
        assertNull(after.custody)
        assertEquals(VvmStage.NOT_RECORDED, after.vvmStage)
    }

    @Test
    fun inspectionAnswersSavedForOneShipmentAreNotRestoredForAnother() {
        val first = Shipment("SHIP-A", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.ARRIVED, ProductProfiles.DEFAULT.id)
        simulator.attachShipment(first)
        val saved = SavedStateHandle()
        viewModel(saved).setShakeTest(ShakeTestResult.PASSED)

        simulator.attachShipment(first.copy(id = "SHIP-B"))
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

        assertEquals(ShakeTestResult.NOT_DONE, restored.state.value.shakeTest)
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

    // Gate 5 review S3 (28 Nov): the screen judged the record with a "now" read only when data changed, so a courier
    // going silent never turned PASS into UNKNOWN on screen. Each look at the server, changed or not, moves it on.
    @Test
    fun eachLookAtTheServerMovesTheTimeTheRecordIsJudgedAt() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        visible.launch { vm.runWhileVisible() }

        val first = vm.awaitState { it.checkedAt != null }.checkedAt!!
        val later = withTimeout(10_000) { vm.state.first { (it.checkedAt ?: 0L) > first } }.checkedAt!!

        assertTrue("$first then $later", later > first)
        assertEquals(10, telemetry.getTemperatures("SHIP-1").first().size)
    }

    // The receiver's verdict covers readings up to their own confirmation, so the courier keeps recording until
    // custody moves (HandoverWindowTest). Before 26 Nov this test asserted that releasing stopped recording.
    @Test
    fun releasingKeepsRecordingUntilCustodyMovesAndSaysWhatHappened() = runBlocking {
        openOnServer("courier", MemberRole.COURIER)
        simulator.startContinuousSimulation(intervalMs = 60_000L)
        val vm = viewModel()

        vm.release("receiver").join()

        assertTrue(simulator.isRunning.value)
        val after = vm.state.value
        assertNull(after.error)
        assertTrue(after.notice.orEmpty(), after.notice.orEmpty().contains("recording", ignoreCase = true))
        assertFalse(after.processing)
    }

    @Test
    fun whileShownItKeepsDownloadingTheReadingsTheCourierSends() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        visible.launch { vm.runWhileVisible() }
        withTimeout(5_000) { telemetry.getTemperatures("SHIP-1").first { it.size == 10 } }

        courierSendsTheNextReading()

        withTimeout(10_000) { telemetry.getTemperatures("SHIP-1").first { it.size == 11 } }
        Unit
    }

    @Test
    fun aReceiverWhoseConfirmationNewReadingsOvertookIsAskedToConfirmAgain() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        visible.launch { vm.runWhileVisible() }
        withTimeout(5_000) { telemetry.getTemperatures("SHIP-1").first { it.size == 10 } }
        vm.record(HandoverVerdict.PASS, null).join()
        assertTrue(vm.state.value.notice.orEmpty(), vm.state.value.notice.orEmpty().startsWith("Confirmed"))

        // The courier hasn't confirmed yet and records another reading, so the receiver's confirmation is on an old head.
        courierSendsTheNextReading()

        val after = withTimeout(10_000) { vm.state.first { it.notice.orEmpty().contains("confirm again") } }
        assertTrue(after.notice!!.contains("New readings arrived"))
    }

    // Found on the emulator (27 Nov): twice a tap on the Handover form landed ~170 px low, each time within 2 s of a new
    // reading arriving. The head is set the moment new readings are saved, and the stored readings follow a moment
    // later, so the screen could compare the new head with the old readings: "this device has N readings but the
    // server holds N+1", shown as tampering, with a longer message that moved the form.
    @Test
    fun whileReadingsArriveTheScreenNeverComparesTheNewHeadWithTheOldReadings() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        val seenBehind = mutableListOf<String>()
        val watching = visible.launch {
            vm.shownHistory.collect { shown ->
                val onServer = shown.serverHead?.count
                if (onServer != null && shown.readings.size < onServer) seenBehind += "${shown.readings.size} < $onServer"
            }
        }
        visible.launch { vm.runWhileVisible() }
        withTimeout(5_000) { telemetry.getTemperatures("SHIP-1").first { it.size == 10 } }

        repeat(3) { n ->
            courierSendsReading(seq = 11L + n)
            withTimeout(10_000) { vm.serverHead.first { it?.count == 11L + n } }
            withTimeout(5_000) { vm.readings.first { it.size == 11 + n } }
        }

        watching.cancel()
        assertTrue("the screen saw fewer readings than the head: $seenBehind", seenBehind.isEmpty())
    }

    // The wait above must not hide a real gap: a device still short of the server's readings after the moment it takes
    // them to reach the screen is shown the mismatch, which is what catches a removed tail.
    @Test
    fun aDeviceStillShortOfTheServersReadingsIsShownTheMismatch() = runBlocking {
        openOnServer("receiver", MemberRole.RECEIVER)
        val vm = viewModel()
        val shown = mutableListOf<ShownHistory>()
        val watching = visible.launch { vm.shownHistory.collect { shown += it } }
        history.hydrateHistory("SHIP-1").getOrThrow()
        withTimeout(5_000) { vm.shownHistory.first { it.readings.size == 10 && it.serverHead?.count == 10L } }

        // The newest stored reading disappears from this phone, and nothing fetches it again.
        db.runInTransaction { db.openHelper.writableDatabase.execSQL("DELETE FROM temperature_events WHERE seq = 10") }

        val mismatch = kotlinx.coroutines.withTimeoutOrNull(HandoverViewModel.CATCH_UP_MS + 5_000) {
            vm.shownHistory.first { it.readings.size == 9 && it.serverHead?.count == 10L }
        } ?: throw AssertionError("shown: ${shown.map { "${it.readings.size}/${it.serverHead?.count}" }}")
        watching.cancel()
        assertEquals(9, mismatch.readings.size)
    }

    /** The courier's phone sends reading [seq], half a minute after the one before. */
    private suspend fun courierSendsReading(seq: Long) {
        val held = telemetry.getTemperatures("SHIP-1").first().sortedBy { it.seq }
        val last = held.last()
        val next = HashChainService.appendHash(
            TemperatureEvent("TE-$seq", "SHIP-1", "LOG-1", last.timestamp + minute / 2, 5.0, "", "", SyncStatus.PENDING, seq = seq),
            last.currentHash
        )
        val user = server.currentUser
        server.currentUser = "courier"
        server.appendReadings("SHIP-1", listOf(next), last.currentHash, "courier-phone", "s").getOrThrow()
        server.currentUser = user
    }

    /** The courier's phone sends the reading after the ten from [readings], half a minute after the last. */
    private suspend fun courierSendsTheNextReading() {
        val last = readings().last()
        val next = HashChainService.appendHash(
            TemperatureEvent("TE-next", "SHIP-1", "LOG-1", last.timestamp + minute / 2, 5.0, "", "", SyncStatus.PENDING, seq = 11),
            last.currentHash
        )
        val user = server.currentUser
        server.currentUser = "courier"
        server.appendReadings("SHIP-1", listOf(next), last.currentHash, "courier-phone", "s").getOrThrow()
        server.currentUser = user
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
