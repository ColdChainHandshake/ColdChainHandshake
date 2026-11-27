package com.coldchain.handshake.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.RoomTransactionRunner
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.PendingReleases
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.FakeNetworkMonitor
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ReadingUploader
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What keeps a courier's recording going with no screen open: sending readings, renewing a handover that waits for
 * the other person, noticing custody move on, and picking recording up again after the process was restarted.
 * The foreground service only hosts this loop. Real database, fake server, a wall clock the test moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingLoopTest {

    private val minute = 60_000L
    private val created = 1_700_000_000_000L
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val handovers = HandoverRepositoryImpl(db.handoverDao())
    private val server = FakeColdChainServer(currentUser = "courier").apply { seedShipment("SHIP-1", createdAt = created) }
    private val account = FakeAccountRepository().apply {
        setState(AccountState.Ready("courier", null, UserProfile("courier", MemberRole.COURIER)))
    }
    private val signer = object : DeviceSigner {
        override val publicKey = "public-key"
        override fun sign(payload: ByteArray) = "signature"
    }
    private val sync = SyncServiceImpl(
        db.shipmentDao(), db.temperatureEventDao(), db.alertDao(), db.handoverDao(), db.gpsPointDao(), server,
        ReadingUploader(server, signer, { "courier-phone" }), account, FakeNetworkMonitor(), { "courier-phone" }
    )
    private val history = ShipmentHistorySyncCoordinator(telemetry, server)
    private val pendingReleases = PendingReleases.inMemory()
    private val serverHandover = ServerHandover(
        remote = server,
        orchestrator = HandoverOrchestrator(shipments, telemetry, handovers, RoomTransactionRunner(db)),
        shipments = shipments,
        handovers = handovers,
        history = history,
        uploadPending = { sync.uploadReadings(it) },
        deviceId = { "courier-phone" },
        pendingReleases = pendingReleases
    )

    @Volatile private var clock = created + minute
    /** The simulator's own thread, so tearDown can wait for a reading being written before closing the database. */
    private val ticker = Executors.newSingleThreadExecutor()
    private val simulator = TemperatureSimulator(telemetry, dispatcher = ticker.asCoroutineDispatcher(), wallClock = { clock })
    private val store = RecordingStore.inMemory()
    private var backgroundStarts = 0
    private val recorder = CourierRecorder(simulator, store) { backgroundStarts++ }
    private var syncRequests = 0
    private val loop = RecordingLoop(
        simulator = simulator,
        recorder = recorder,
        sync = sync,
        handover = serverHandover,
        remote = server,
        account = account,
        shipments = shipments,
        store = store,
        syncRequests = { syncRequests++ },
        loopMs = 20L,
        accountWaitMs = 500L
    )
    private val running = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        runBlocking { running.coroutineContext.job.cancelAndJoin() }
        simulator.stopContinuousSimulation()
        ticker.shutdown()
        ticker.awaitTermination(5, TimeUnit.SECONDS)
        db.close()
    }

    private fun opened(): Shipment = runBlocking { ShipmentWorkflow(server, shipments) { "courier-phone" }.open("SHIP-1").getOrThrow() }

    private fun eventually(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
        assertTrue(what, condition())
    }

    private fun serverCount(): Int = server.heads.getValue("SHIP-1").count.toInt()

    @Test
    fun startingRecordsRemembersTheShipmentAndKeepsRunningInTheBackground() {
        recorder.start(opened())

        assertTrue(simulator.isRunning.value)
        assertEquals("SHIP-1", store.shipmentId)
        assertEquals(1, backgroundStarts)
    }

    @Test
    fun whileRecordingItSendsReadingsWithNoScreenOpen() {
        recorder.start(opened())
        running.async { loop.run() }

        eventually("the first reading reaches the server") { serverCount() == 1 }
        clock += minute
        eventually("the next minute's reading follows") { serverCount() == 2 }
    }

    @Test
    fun aHandoverWaitingForTheReceiverIsRenewedOnEachNewHead() = runBlocking {
        recorder.start(opened())
        running.async { loop.run() }
        eventually("the first reading is sent") { serverCount() == 1 }
        serverHandover.release("SHIP-1", "receiver").getOrThrow()

        clock += minute
        eventually("the release is renewed on the new head") {
            server.confirmations.lastOrNull()?.details?.headHash == server.heads.getValue("SHIP-1").headHash &&
                serverCount() == 2
        }
        assertEquals(2, server.confirmations.count { it.userId == "courier" })
        assertTrue("recording goes on", simulator.isRunning.value)
    }

    // Finding 11, the courier's side. On the dev project on 27 Nov the courier's phone, waiting for the receiver, made
    // about 48 requests a minute in the background, and 77 of its 87 confirmations in 10 minutes were refused as
    // already made (CC008): it confirmed again every 5 s on a head that hadn't moved. Only this phone, the custodian,
    // adds to the head, so with nothing new recorded there is nothing to confirm again.
    @Test
    fun whileWaitingWithNothingNewRecordedTheCourierDoesNotConfirmAgain() = runBlocking {
        recorder.start(opened())
        running.async { loop.run() }
        eventually("the first reading is sent") { serverCount() == 1 }
        serverHandover.release("SHIP-1", "receiver").getOrThrow()
        val confirmsAfterRelease = server.confirmCalls

        delay(400) // twenty rounds, and no new reading: the clock doesn't move

        assertEquals(confirmsAfterRelease, server.confirmCalls)
    }

    // With the loop's own custody read now once a minute, the renewal is what notices the receiver confirming.
    @Test
    fun whenTheReceiverConfirmsWhileTheCourierWaitsRecordingStopsAtOnce() = runBlocking {
        // The loop's own custody read is switched off here, so only the renewal can notice.
        val renewalsOnly = RecordingLoop(
            simulator, recorder, sync, serverHandover, server, account, shipments, store, { syncRequests++ },
            loopMs = 20L, accountWaitMs = 500L, custodyEveryRounds = Long.MAX_VALUE
        )
        recorder.start(opened())
        val ended = running.async { renewalsOnly.run() }
        eventually("the first reading is sent") { serverCount() == 1 }
        serverHandover.release("SHIP-1", "receiver").getOrThrow()

        server.currentUser = "receiver"
        server.confirmHandover(
            HandoverConfirmation("SHIP-1", 1, "receiver-phone", server.heads.getValue("SHIP-1").headHash, verdict = HandoverVerdict.PASS)
        ).getOrThrow()
        server.currentUser = "courier"
        assertEquals("receiver", server.custody.getValue("SHIP-1").custodianId)

        withTimeout(1_000) { ended.await() }
        assertFalse(simulator.isRunning.value)
        assertEquals("SHIP-1", simulator.handedOver.value)
    }

    // Found on the S24 (27 Nov): after the handover the courier's phone still held the shipment as IN_TRANSIT. A screen
    // showing a state the server has moved past isn't honest, so once custody moves this phone takes the server's
    // status, whichever check noticed.
    @Test
    fun whenTheReceiverPassesWhileTheCourierWaitsThisPhoneHoldsTheShipmentAsAccepted() = runBlocking {
        val renewalsOnly = RecordingLoop(
            simulator, recorder, sync, serverHandover, server, account, shipments, store, { syncRequests++ },
            loopMs = 20L, accountWaitMs = 500L, custodyEveryRounds = Long.MAX_VALUE
        )
        recorder.start(opened())
        val ended = running.async { renewalsOnly.run() }
        eventually("the first reading is sent") { serverCount() == 1 }
        serverHandover.release("SHIP-1", "receiver").getOrThrow()

        server.currentUser = "receiver"
        server.confirmHandover(
            HandoverConfirmation("SHIP-1", 1, "receiver-phone", server.heads.getValue("SHIP-1").headHash, verdict = HandoverVerdict.PASS)
        ).getOrThrow()
        server.currentUser = "courier"
        assertEquals(ShipmentStatus.ACCEPTED, server.shipments.getValue("SHIP-1").status)

        withTimeout(1_000) { ended.await() }
        assertEquals(ShipmentStatus.ACCEPTED, shipments.getShipment("SHIP-1").first()?.status)
        assertEquals(ShipmentStatus.ACCEPTED, simulator.activeShipment.value?.status)
    }

    @Test
    fun whenTheLoopsOwnCustodyReadFindsItMovedThisPhoneTakesTheServersStatus() = runBlocking {
        recorder.start(opened())
        val ended = running.async { loop.run() }
        eventually("the first reading is sent") { serverCount() == 1 }

        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(status = ShipmentStatus.QUARANTINED)
        server.custody["SHIP-1"] = CustodyState(2, "receiver")

        withTimeout(5_000) { ended.await() }
        assertEquals(ShipmentStatus.QUARANTINED, shipments.getShipment("SHIP-1").first()?.status)
        assertEquals(ShipmentStatus.QUARANTINED, simulator.activeShipment.value?.status)
    }

    // A second courier taking over leaves the shipment on the road: nothing is moved past what the server says.
    @Test
    fun handedToAnotherCourierTheShipmentStaysInTransitHere() = runBlocking {
        recorder.start(opened())
        val ended = running.async { loop.run() }
        eventually("the first reading is sent") { serverCount() == 1 }

        server.custody["SHIP-1"] = CustodyState(2, "courier-b")

        withTimeout(5_000) { ended.await() }
        assertFalse(simulator.isRunning.value)
        assertEquals(ShipmentStatus.IN_TRANSIT, shipments.getShipment("SHIP-1").first()?.status)
    }

    @Test
    fun afterARestartAShipmentHandedOverMeanwhileTakesTheServersStatus() = runBlocking {
        opened()
        store.shipmentId = "SHIP-1"
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(status = ShipmentStatus.ACCEPTED)
        server.custody["SHIP-1"] = CustodyState(2, "receiver")

        assertFalse(loop.restore())

        assertEquals(ShipmentStatus.ACCEPTED, shipments.getShipment("SHIP-1").first()?.status)
    }

    // Custody can only move away with this phone's own confirmation, so with no handover waiting it is read now and
    // then, as a safety net, rather than every other round.
    @Test
    fun withNoHandoverWaitingCustodyIsReadOnlyNowAndThen() = runBlocking {
        recorder.start(opened())
        running.async { loop.run() }
        eventually("the first reading is sent") { serverCount() == 1 }
        delay(100)
        val before = server.requests

        delay(400) // twenty rounds, nothing new to send

        val made = server.requests - before
        assertTrue("$made requests in twenty quiet rounds", made <= 3)
    }

    @Test
    fun whenCustodyMovesAwayRecordingStopsAndIsNotPickedUpAgain() = runBlocking {
        recorder.start(opened())
        val ended = running.async { loop.run() }
        eventually("the first reading is sent") { serverCount() == 1 }

        server.custody["SHIP-1"] = CustodyState(2, "receiver")

        withTimeout(5_000) { ended.await() }
        assertFalse(simulator.isRunning.value)
        assertNull(store.shipmentId)
    }

    @Test
    fun pausingEndsTheLoopAndForgetsTheShipment() = runBlocking {
        recorder.start(opened())
        val ended = running.async { loop.run() }

        recorder.pause()

        withTimeout(5_000) { ended.await() }
        assertNull(store.shipmentId)
    }

    @Test
    fun aSendThatFailsBooksABackgroundSyncForWhenTheConnectionReturns() {
        recorder.start(opened())
        server.offlineError = IOException("No signal")
        running.async { loop.run() }

        eventually("a sync is booked") { syncRequests > 0 }
        assertTrue("recording goes on without a connection", simulator.isRunning.value)
    }

    @Test
    fun afterARestartRecordingResumesForAShipmentThisPhoneStillHolds() = runBlocking {
        opened()
        store.shipmentId = "SHIP-1"

        assertTrue(loop.restore())

        assertTrue(simulator.isRunning.value)
        assertEquals("SHIP-1", simulator.activeShipment.value?.id)
        assertFalse(simulator.isMonitorOnly.value)
    }

    @Test
    fun afterARestartNothingResumesForAShipmentHandedOverMeanwhile() = runBlocking {
        opened()
        store.shipmentId = "SHIP-1"
        server.custody["SHIP-1"] = CustodyState(2, "receiver")

        assertFalse(loop.restore())

        assertFalse(simulator.isRunning.value)
        assertNull(store.shipmentId)
    }

    @Test
    fun afterARestartNothingResumesForSomeoneSignedOut() = runBlocking {
        opened()
        store.shipmentId = "SHIP-1"
        account.setState(AccountState.SignedOut)

        assertFalse(loop.restore())

        assertFalse(simulator.isRunning.value)
        assertNull(store.shipmentId)
    }

    @Test
    fun afterARestartNothingResumesForAShipmentNoLongerOnTheMove() = runBlocking {
        val shipment = opened()
        shipments.adoptRemoteShipment(shipment.copy(status = ShipmentStatus.ACCEPTED)).getOrThrow()
        store.shipmentId = "SHIP-1"

        assertFalse(loop.restore())

        assertFalse(simulator.isRunning.value)
        assertNull(store.shipmentId)
    }

    @Test
    fun afterARestartNothingResumesWhenNothingWasRecording() = runBlocking {
        opened()

        assertFalse(loop.restore())

        assertFalse(simulator.isRunning.value)
    }

    // Found on the S24 on 26 Nov: after a reinstall the app opened with no saved screen state, so nothing read the
    // remembered shipment and recording stayed stopped. Opening the app hands it to the background recording, whose
    // restore checks the shipment is still on the move and still this phone's.
    @Test
    fun openingTheAppWithARememberedShipmentStartsTheBackgroundRecording() {
        store.shipmentId = "SHIP-1"

        recorder.resumeInBackgroundIfRemembered()

        assertEquals(1, backgroundStarts)
    }

    @Test
    fun openingTheAppStartsNothingWhenNothingIsRemembered() {
        recorder.resumeInBackgroundIfRemembered()

        assertEquals(0, backgroundStarts)
    }

    @Test
    fun openingTheAppStartsNothingMoreWhileRecordingAlreadyRuns() {
        recorder.start(opened())

        recorder.resumeInBackgroundIfRemembered()

        assertEquals("only the start itself", 1, backgroundStarts)
    }

    // A restart in the background (Android restarting the service after its process was killed) brings recording back
    // without location, because Android lets a location service start only while the app is open. Opening the app
    // then asks the recording already running to add it: a new start of the service, not a second recording.
    @Test
    fun openingTheAppWhileRecordingAsksTheServiceAgainSoItCanAddLocation() {
        recorder.start(opened())

        recorder.onAppOpened()

        assertEquals(2, backgroundStarts)
        assertTrue(simulator.isRunning.value)
    }

    @Test
    fun openingTheAppWithNothingRecordingResumesOnlyARememberedShipment() {
        recorder.onAppOpened()
        assertEquals(0, backgroundStarts)

        store.shipmentId = "SHIP-1"
        recorder.onAppOpened()
        assertEquals(1, backgroundStarts)
    }

    @Test
    fun openingTheAppAgainResumesNothingForAShipmentNoLongerOnTheMove() {
        val accepted = opened().copy(status = ShipmentStatus.ACCEPTED)
        store.shipmentId = "SHIP-1"

        assertFalse(recorder.resumeIfItWasRecording(accepted))

        assertFalse(simulator.isRunning.value)
        assertNull(store.shipmentId)
        assertEquals(0, backgroundStarts)
    }

    @Test
    fun openingTheAppAgainResumesOnlyTheShipmentThatWasRecording() {
        val shipment = opened()
        store.shipmentId = "OTHER"

        assertFalse(recorder.resumeIfItWasRecording(shipment))
        assertFalse(simulator.isRunning.value)

        store.shipmentId = "SHIP-1"
        assertTrue(recorder.resumeIfItWasRecording(shipment))
        assertTrue(simulator.isRunning.value)
        assertEquals(1, backgroundStarts)
    }
}
