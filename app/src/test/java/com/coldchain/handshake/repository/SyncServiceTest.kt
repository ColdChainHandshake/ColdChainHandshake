package com.coldchain.handshake.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.data.network.NetworkMonitor
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Runs on a real Room database, so the queries that decide what's still to send are tested too. The
 * server is a fake that refuses what the real one refuses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val alerts = AlertRepositoryImpl(db.alertDao())
    private val handovers = HandoverRepositoryImpl(db.handoverDao())

    private val server = FakeColdChainServer(currentUser = "courier")
    private val genesis = server.seedShipment("SHIP-1")
    private val account = FakeAccountRepository(
        AccountState.Ready("courier", null, UserProfile("Cory", MemberRole.COURIER))
    )
    private val network = FakeNetworkMonitor()
    private val signer = object : DeviceSigner {
        override val publicKey = "public-key"
        override fun sign(payload: ByteArray) = "signature"
    }
    private val scopes = mutableListOf<CoroutineScope>()

    private fun service(watchNetwork: Boolean = false) = SyncServiceImpl(
        shipmentDao = db.shipmentDao(),
        temperatureEventDao = db.temperatureEventDao(),
        alertDao = db.alertDao(),
        handoverDao = db.handoverDao(),
        gpsPointDao = db.gpsPointDao(),
        remote = server,
        uploader = ReadingUploader(server, signer, { "device-1" }),
        account = account,
        networkMonitor = network,
        deviceId = { "device-1" },
        coroutineScope = if (watchNetwork) CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it } else null
    )

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
    }

    private suspend fun shipmentOnServer() {
        ShipmentWorkflow(server, shipments) { "device-1" }.open("SHIP-1").getOrThrow()
    }

    private fun chain(count: Int, shipmentId: String = "SHIP-1", genesisHash: String = genesis): List<TemperatureEvent> =
        HashChainService.buildChain(
            (1..count).map {
                TemperatureEvent(
                    "$shipmentId-TE-$it", shipmentId, "LOG-1", 1_700_000_000_000L + it * 60_000L, 5.0, "", "",
                    SyncStatus.PENDING
                )
            },
            genesisHash
        )

    private suspend fun recordReadings(count: Int = 3): List<TemperatureEvent> {
        shipmentOnServer()
        return chain(count).also { telemetry.saveTemperatures(it).getOrThrow() }
    }

    private suspend fun stored(shipmentId: String = "SHIP-1") = telemetry.getTemperatures(shipmentId).first()

    @Test
    fun readingsReachTheServerAndAreMarkedSynced() = runBlocking {
        recordReadings()

        service().syncPendingData().getOrThrow()

        assertEquals(3, server.readings.getValue("SHIP-1").size)
        assertTrue(stored().all { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun theServerHoldsExactlyWhatThisPhoneRecorded() = runBlocking {
        val local = recordReadings()

        service().syncPendingData().getOrThrow()

        val remote = server.readings.getValue("SHIP-1")
        assertEquals(local.map { it.currentHash }, remote.map { it.currentHash })
        assertEquals(local.map { it.timestamp }, remote.map { it.timestamp })
    }

    @Test
    fun aFailedUploadStaysQueuedAndGoesLater() = runBlocking {
        recordReadings()
        server.offlineError = IOException("No connection")

        assertTrue(service().syncPendingData().isFailure)
        assertTrue(stored().all { it.syncStatus == SyncStatus.FAILED })

        server.offlineError = null
        service().syncPendingData().getOrThrow()

        assertEquals(3, server.readings.getValue("SHIP-1").size)
    }

    @Test
    fun aLostReplyIsNotSentTwice() = runBlocking {
        recordReadings()
        server.loseNextAppendReply = true
        val sync = service()

        assertTrue(sync.syncPendingData().isFailure)
        sync.syncPendingData().getOrThrow()

        assertEquals(3, server.readings.getValue("SHIP-1").size)
        assertEquals(1, server.appendCalls)
        assertTrue(stored().all { it.syncStatus == SyncStatus.SYNCED })
    }

    @Test
    fun aRefusalIsFinalAndSaysWhy() = runBlocking {
        recordReadings()
        server.custody["SHIP-1"] = CustodyState(2, "someone-else")
        val sync = service()

        assertTrue(sync.syncPendingData().isFailure)
        sync.syncPendingData()

        assertTrue(stored().all { it.syncStatus == SyncStatus.REJECTED })
        assertEquals(1, server.appendCalls)
        assertNotNull(sync.getLastRejection().first())
    }

    /** Locations the recording service queued for SHIP-1, one a minute. */
    private suspend fun queueLocations(count: Int, shipmentId: String = "SHIP-1"): List<GpsPoint> =
        (1..count).map { GpsPoint("gps-$shipmentId-$it", shipmentId, 9.98 + it / 1000.0, 76.28, 12f, 1_700_000_000_000L + it * 60_000L) }
            .onEach { db.gpsPointDao().insert(it.toEntity()) }

    // MASTER 5.5 item 2: GPS points queued in Room and sent like readings, so a trip with no signal still has its route.
    @Test
    fun locationsQueuedWithNoSignalReachTheServerOnceItCanBeReached() = runBlocking {
        shipmentOnServer()
        val queued = queueLocations(3)
        server.offlineError = IOException("No connection")

        assertTrue(service().syncPendingData().isFailure)
        assertTrue(server.gpsPoints.isEmpty())
        assertEquals(3, db.gpsPointDao().getPending().size)

        server.offlineError = null
        service().syncPendingData().getOrThrow()

        assertEquals(queued, server.gpsPoints)
        assertTrue(db.gpsPointDao().getPending().isEmpty())
    }

    // The server takes locations only from the current custodian (CC002), so once custody has moved on a queued point
    // can never be sent. It is set aside rather than tried on every sync for ever, and it isn't shown as a refusal of
    // the person's readings.
    @Test
    fun aLocationTheServerRefusesIsSetAsideNotRetried() = runBlocking {
        shipmentOnServer()
        queueLocations(2)
        server.custody["SHIP-1"] = CustodyState(2, "receiver")
        val sync = service()

        sync.syncPendingData()
        sync.syncPendingData()

        assertTrue(server.gpsPoints.isEmpty())
        assertTrue(db.gpsPointDao().getPending().isEmpty())
        assertNull(sync.getLastRejection().first())
    }

    // Gate 5 review S8: a courier's route is theirs. Once the trip is over on this phone, the points it sent, and those
    // the server refused, are deleted here; any still waiting to be sent, and other shipments', stay.
    @Test
    fun afterTheTripThePointsThisPhoneSentOrHadRefusedAreDeletedHere() = runBlocking {
        shipmentOnServer()
        queueLocations(3)
        val sync = service()
        sync.syncPendingData().getOrThrow()
        db.gpsPointDao().insert(GpsPoint("gps-SHIP-1-refused", "SHIP-1", 9.9, 76.2, 12f, 1_700_000_800_000L).toEntity())
        db.gpsPointDao().updateSyncStatuses(listOf("gps-SHIP-1-refused"), SyncStatus.REJECTED)
        db.gpsPointDao().insert(GpsPoint("gps-SHIP-1-waiting", "SHIP-1", 9.9, 76.2, 12f, 1_700_000_900_000L).toEntity())
        queueLocations(1, "SHIP-2")

        sync.forgetSentLocation("SHIP-1")

        val left = db.openHelper.readableDatabase.query("SELECT id FROM gps_points ORDER BY id").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }
        assertEquals(listOf("gps-SHIP-1-waiting", "gps-SHIP-2-1"), left)
    }

    @Test
    fun aLocationForAShipmentKeptOnThisPhoneIsNeverSent() = runBlocking {
        shipments.saveShipment(Shipment("LOCAL-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.CREATED)).getOrThrow()
        queueLocations(1, "LOCAL-1")

        service().syncPendingData().getOrThrow()

        assertTrue(server.gpsPoints.isEmpty())
    }

    @Test
    fun aShipmentKeptOnThisPhoneIsNeverSent() = runBlocking {
        shipments.saveShipment(Shipment("LOCAL-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.CREATED)).getOrThrow()
        telemetry.saveTemperatures(chain(2, "LOCAL-1", HashChainService.UNBOUND_GENESIS)).getOrThrow()

        service().syncPendingData().getOrThrow()

        assertEquals(0, server.appendCalls)
        assertTrue(stored("LOCAL-1").all { it.syncStatus == SyncStatus.PENDING })
    }

    @Test
    fun nothingIsSentWhileSignedOut() = runBlocking {
        recordReadings()
        account.setState(AccountState.SignedOut)

        service().syncPendingData().getOrThrow()

        assertEquals(0, server.appendCalls)
    }

    @Test
    fun thePhoneIsRegisteredOnceBeforeItsFirstUpload() = runBlocking {
        recordReadings()
        val sync = service()

        sync.syncPendingData().getOrThrow()
        telemetry.saveTemperatures(emptyList()).getOrThrow()
        sync.syncPendingData().getOrThrow()

        assertEquals(1, account.deviceRegistrations)
    }

    @Test
    fun aRestoredConnectionSendsWithoutBeingAsked() = runBlocking {
        recordReadings()
        network.setOnline(false)
        service(watchNetwork = true)

        network.setOnline(true)

        withTimeout(10_000) { while (server.readings.getValue("SHIP-1").size < 3) delay(20) }
    }

    @Test
    fun syncsRunningAtOnceSendEverythingOnce() = runBlocking {
        recordReadings()
        val sync = service()

        (1..4).map { async(Dispatchers.Default) { sync.syncPendingData() } }.awaitAll()
        sync.syncPendingData().getOrThrow()

        assertEquals(3, server.readings.getValue("SHIP-1").size)
        assertEquals(1, server.appendCalls)
    }

    @Test
    fun oneShipmentCanBeSentStraightAway() = runBlocking {
        recordReadings()

        service().uploadReadings("SHIP-1").getOrThrow()

        assertEquals(3, server.readings.getValue("SHIP-1").size)
    }

    private fun alert(level: EscalationLevel = EscalationLevel.WORKER, acknowledged: Boolean = false) = Alert(
        "SHIP-1:DATA_GAP:1000", "SHIP-1", AlertType.DATA_GAP, "Gap", 2_000L, level, acknowledged,
        excursionStart = 1_000L
    )

    @Test
    fun anAlertIsRecordedEscalatedAndAcknowledged() = runBlocking {
        shipmentOnServer()
        server.currentUser = "receiver"
        account.setState(AccountState.Ready("receiver", null, UserProfile("Rae", MemberRole.RECEIVER)))
        alerts.saveAlert(alert(EscalationLevel.SUPERVISOR, acknowledged = true)).getOrThrow()

        service().syncPendingData().getOrThrow()

        val onServer = server.alerts.getValue("SHIP-1:DATA_GAP:1000")
        assertEquals(EscalationLevel.SUPERVISOR, onServer.escalationLevel)
        assertTrue(onServer.acknowledged)
        assertTrue(db.alertDao().getPendingAlerts().isEmpty())
    }

    @Test
    fun aCourierAcknowledgingIsRefusedAndNotRetried() = runBlocking {
        shipmentOnServer()
        alerts.saveAlert(alert(acknowledged = true)).getOrThrow()
        val sync = service()

        assertTrue(sync.syncPendingData().isFailure)

        assertEquals(SyncStatus.REJECTED, db.alertDao().getAlertDirect("SHIP-1:DATA_GAP:1000")?.syncStatus)
        assertTrue(sync.syncPendingData().isSuccess)
    }

    private fun handover(verdict: HandoverVerdict = HandoverVerdict.FAIL) = Handover(
        id = "H-1",
        shipmentId = "SHIP-1",
        workerSigned = true,
        pharmacistSigned = true,
        integrityVerified = false,
        temperaturePassed = false,
        verdict = verdict,
        timestamp = 3_000L,
        reason = "Seal broken"
    )

    @Test
    fun aConfirmationRecordedWithNoSignalIsSentLater() = runBlocking {
        shipmentOnServer()
        server.currentUser = "receiver"
        handovers.saveHandover(handover()).getOrThrow()

        service().syncPendingData().getOrThrow()

        val sent = server.confirmations.single()
        assertEquals("receiver", sent.userId)
        assertEquals(HandoverVerdict.FAIL, sent.details.verdict)
        assertEquals("Seal broken", sent.details.reason)
        assertTrue(db.handoverDao().getPendingHandovers().isEmpty())
    }

    @Test
    fun aPassRecordedWithoutTheServerIsNeverSent() = runBlocking {
        shipmentOnServer()
        server.currentUser = "receiver"
        handovers.saveHandover(handover(HandoverVerdict.PASS)).getOrThrow()

        service().syncPendingData().getOrThrow()

        assertTrue(server.confirmations.isEmpty())
        assertEquals(SyncStatus.REJECTED, db.handoverDao().getHandoverDirect("SHIP-1")?.syncStatus)
    }

    @Test
    fun aConfirmationTheServerAlreadyHasCountsAsSent() = runBlocking {
        shipmentOnServer()
        server.currentUser = "receiver"
        handovers.saveHandover(handover()).getOrThrow()
        val sync = service()
        sync.syncPendingData().getOrThrow()
        db.handoverDao().updateSyncStatus("H-1", SyncStatus.PENDING, 0L)

        sync.syncPendingData().getOrThrow()

        assertEquals(SyncStatus.SYNCED, db.handoverDao().getHandoverDirect("SHIP-1")?.syncStatus)
        assertEquals(1, server.confirmations.size)
    }
}

class FakeNetworkMonitor(isInitiallyOnline: Boolean = true) : NetworkMonitor {
    private var online: Boolean = isInitiallyOnline
    private val _flow = MutableSharedFlow<Boolean>(replay = 1)

    init {
        _flow.tryEmit(isInitiallyOnline)
    }

    suspend fun setOnline(online: Boolean) {
        this.online = online
        _flow.emit(online)
    }

    override fun isOnline(): Boolean = online

    override fun observeNetworkState(): Flow<Boolean> = _flow
}
