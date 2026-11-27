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
