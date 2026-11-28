package com.coldchain.handshake.handover

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.RoomTransactionRunner
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.logger.LoggerBatch
import com.coldchain.handshake.logger.LoggerCheckOutcome
import com.coldchain.handshake.logger.LoggerLink
import com.coldchain.handshake.logger.LoggerStatus
import com.coldchain.handshake.logger.VirtualLogger
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.FakeNetworkMonitor
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ReadingUploader
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.repository.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * One phone, on a real database, against a fake server that applies the same handover rules as
 * `confirm_handover`. The other person's phone is played by calling the fake server directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerHandoverTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val handovers = HandoverRepositoryImpl(db.handoverDao())

    private val server = FakeColdChainServer(currentUser = "courier")
    private val genesis = server.seedShipment("SHIP-1")
    private val account = FakeAccountRepository()
    private val signer = object : DeviceSigner {
        override val publicKey = "public-key"
        override fun sign(payload: ByteArray) = "signature"
    }
    private val sync = SyncServiceImpl(
        db.shipmentDao(), db.temperatureEventDao(), db.alertDao(), db.handoverDao(), db.gpsPointDao(), server,
        ReadingUploader(server, signer, { "device-1" }), account, FakeNetworkMonitor(), { "device-1" }
    )
    private val history = ShipmentHistorySyncCoordinator(telemetry, server)
    private val orchestrator = HandoverOrchestrator(shipments, telemetry, handovers, RoomTransactionRunner(db))
    private var now = t0 + 11 * minute

    private val handover = ServerHandover(
        remote = server,
        orchestrator = orchestrator,
        shipments = shipments,
        handovers = handovers,
        history = history,
        uploadPending = { sync.uploadReadings(it) },
        deviceId = { "device-1" },
        clock = { now }
    )

    @After
    fun tearDown() {
        db.close()
    }

    private fun chain(count: Int, temperature: Double = 5.0): List<TemperatureEvent> = HashChainService.buildChain(
        (1..count).map {
            TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", t0 + it * minute, temperature, "", "", SyncStatus.PENDING)
        },
        genesis
    )

    private fun signInAs(user: String, role: MemberRole) {
        server.currentUser = user
        account.setState(AccountState.Ready(user, null, UserProfile(user, role)))
    }

    /** The courier's phone has already uploaded these. */
    private suspend fun courierUploaded(readings: List<TemperatureEvent>) {
        val user = server.currentUser
        server.currentUser = "courier"
        server.appendReadings("SHIP-1", readings, genesis, "courier-phone", "signature").getOrThrow()
        server.currentUser = user
    }

    /** The courier names the receiver on their own phone. */
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

    private suspend fun openAsReceiver() {
        signInAs("receiver", MemberRole.RECEIVER)
        ShipmentWorkflow(server, shipments) { "device-1" }.open("SHIP-1").getOrThrow()
    }

    private suspend fun receive(verdict: HandoverVerdict? = null, reason: String? = null) =
        handover.receive("SHIP-1", verdict, reason, VvmStage.NOT_RECORDED, ShakeTestResult.NOT_DONE)

    private suspend fun localStatus() = shipments.getShipment("SHIP-1").first()?.status

    @Test
    fun custodyMovesOnlyOnceBothPeopleHaveConfirmed() = runBlocking {
        courierUploaded(chain(10))
        openAsReceiver()

        val first = receive().getOrThrow() as HandoverResult.Confirmed

        assertEquals(HandoverState.WAITING, first.outcome.state)
        assertEquals(ShipmentStatus.IN_TRANSIT, localStatus())

        courierReleases()
        handover.refresh("SHIP-1").getOrThrow()

        assertEquals(ShipmentStatus.ACCEPTED, localStatus())
    }

    // 4.1: one logger outcome decides both what the screen shows and what is recorded. A logger enrolled on the
    // shipment's record that this phone can't reach leaves the record unconfirmed, so no PASS is recorded over it.
    @Test
    fun anEnrolledLoggerThisPhoneCannotReachKeepsTheHandoverFromPassing() = runBlocking {
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(
            loggerPublicKeyHex = "04" + "6b".repeat(64), loggerStartSeq = 0
        )
        courierUploaded(chain(10))
        courierReleases()
        openAsReceiver()

        assertEquals(LoggerCheckOutcome.UNKNOWN, handover.loggerCheck("SHIP-1").outcome)
        val declaredPass = receive(HandoverVerdict.PASS)
        assertTrue("a PASS isn't recorded over an unread logger: $declaredPass", declaredPass.isFailure)

        val result = receive().getOrThrow() as HandoverResult.Confirmed
        assertEquals(HandoverVerdict.UNKNOWN, server.confirmations.last().details.verdict)
        assertTrue(ShipmentStatus.ACCEPTED != localStatus())
        assertEquals(HandoverState.TRANSFERRED, result.outcome.state)
    }

    // Gate 4 review (28 Nov): nothing gave the handover a logger to read, so a tampered or truncated logger history had
    // never reached a recorded verdict. Here the receiver's phone reaches the shipment's logger (a virtual one, as in
    // debug local mode), enrolled on the record, and what the logger shows decides what is recorded.
    private val logger = VirtualLogger("LOG-1").apply { (1..10).forEach { record(t0 + it * minute, 500) } }

    /** The courier's readings, made from the logger's records one to one: reading n is record n. */
    private fun readingsFromTheLogger(edited: Int? = null) = HashChainService.buildChain(
        (1..10).map {
            val temperature = if (it == edited) 5.2 else 5.0
            TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", t0 + it * minute, temperature, "", "", SyncStatus.PENDING)
        },
        genesis
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private suspend fun receivedReaching(
        link: LoggerLink,
        readings: List<TemperatureEvent> = readingsFromTheLogger()
    ): Result<HandoverResult> {
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(
            loggerPublicKeyHex = hex(logger.publicKeyUncompressed), loggerStartSeq = 0
        )
        courierUploaded(readings)
        courierReleases()
        openAsReceiver()
        val reaching = ServerHandover(
            remote = server, orchestrator = orchestrator, shipments = shipments, handovers = handovers,
            history = history, uploadPending = { sync.uploadReadings(it) }, deviceId = { "device-1" },
            clock = { now }, loggerLink = { link }
        )
        return reaching.receive("SHIP-1", null, null, VvmStage.NOT_RECORDED, ShakeTestResult.NOT_DONE)
    }

    private fun recordedVerdict() = server.confirmations.last().details.verdict

    @Test
    fun anHonestLoggerHistoryLetsThePassBeRecorded() = runBlocking {
        receivedReaching(logger).getOrThrow()

        assertEquals(HandoverVerdict.PASS, recordedVerdict())
        assertEquals(ShipmentStatus.ACCEPTED, localStatus())
    }

    @Test
    fun aReadingThatDiffersFromTheLoggersRecordIsRecordedAsAFail() = runBlocking {
        receivedReaching(logger, readings = readingsFromTheLogger(edited = 5)).getOrThrow()

        assertEquals(HandoverVerdict.FAIL, recordedVerdict())
        assertNotEquals(ShipmentStatus.ACCEPTED, localStatus())
    }

    @Test
    fun aLoggerHistoryCutShortOfItsHeadIsRecordedAsAFail() = runBlocking {
        val truncating = object : LoggerLink by logger {
            override suspend fun status() = Result.success(LoggerStatus(1, 8))
            override suspend fun readHistory(fromSeq: Long, maxRecords: Int) =
                if (fromSeq > 8) Result.success(null)
                else logger.readHistory(fromSeq, minOf(maxRecords, (9 - fromSeq).toInt()))
        }

        receivedReaching(truncating).getOrThrow()

        assertEquals(HandoverVerdict.FAIL, recordedVerdict())
    }

    @Test
    fun aReplayedLoggerHeadIsRecordedAsAFail() = runBlocking {
        val oldHead = logger.challenge(ByteArray(16) { 7 }).getOrThrow()
        val replaying = object : LoggerLink by logger {
            override suspend fun challenge(nonce: ByteArray) = Result.success(oldHead)
        }

        receivedReaching(replaying).getOrThrow()

        assertEquals(HandoverVerdict.FAIL, recordedVerdict())
    }

    // Gate 4 review, mutant M6: a logger that shows none of its history, claiming its head is where custody started.
    @Test
    fun aLoggerWithholdingItsWholeHistoryIsNotAPass() = runBlocking {
        val withholding = object : LoggerLink by logger {
            override suspend fun status() = Result.success(LoggerStatus(1, 0))
            override suspend fun readHistory(fromSeq: Long, maxRecords: Int) = Result.success<LoggerBatch?>(null)
        }

        receivedReaching(withholding).getOrThrow()

        assertNotEquals(HandoverVerdict.PASS, recordedVerdict())
    }

    // Gate 4 review, mutant A2: when the shipment's enrolment can't be read, the logger isn't "not checked".
    @Test
    fun anEnrolmentThatCannotBeReadIsUnknownNotNotChecked() = runBlocking {
        courierUploaded(chain(10))
        openAsReceiver()
        server.failReads = null
        val unreadable = object : com.coldchain.handshake.repository.ColdChainRemote by server {
            override suspend fun getShipment(shipmentId: String) =
                Result.failure<com.coldchain.handshake.repository.RemoteShipment?>(java.io.IOException("No signal"))
        }
        val handoverOffline = ServerHandover(
            remote = unreadable, orchestrator = orchestrator, shipments = shipments, handovers = handovers,
            history = history, uploadPending = { Result.success(Unit) }, deviceId = { "device-1" }, clock = { now }
        )

        assertEquals(LoggerCheckOutcome.UNKNOWN, handoverOffline.loggerCheck("SHIP-1").outcome)
    }

    // Gate 4 review, mutant A3 and G4-5: a key on record that can't be read is still a logger on record, so it isn't
    // "none enrolled". The server's CHECK refuses such a key today; this guards the phone's side.
    @Test
    fun aMalformedKeyOnRecordIsUnknownNotNotChecked() = runBlocking {
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(loggerPublicKeyHex = "04zz", loggerStartSeq = 0)
        courierUploaded(chain(10))
        courierReleases()
        openAsReceiver()

        assertEquals(LoggerCheckOutcome.UNKNOWN, handover.loggerCheck("SHIP-1").outcome)
        assertTrue(receive(HandoverVerdict.PASS).isFailure)
    }

    @Test
    fun aMalformedKeyOnRecordWithTheLoggerInReachIsNotAPass() = runBlocking {
        val reaching = ServerHandover(
            remote = server, orchestrator = orchestrator, shipments = shipments, handovers = handovers,
            history = history, uploadPending = { sync.uploadReadings(it) }, deviceId = { "device-1" },
            clock = { now }, loggerLink = { logger }
        )
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(loggerPublicKeyHex = "not hex", loggerStartSeq = 0)
        courierUploaded(readingsFromTheLogger())
        courierReleases()
        openAsReceiver()

        assertTrue(reaching.loggerCheck("SHIP-1").outcome in setOf(LoggerCheckOutcome.UNKNOWN, LoggerCheckOutcome.FAILED))
    }

    // Gate 4 review G4-8: a key with no start sequence can't say which record is reading 1. Taking 0 would compare a
    // reused logger's older records with this trip's readings, so the check doesn't guess.
    @Test
    fun aKeyOnRecordWithoutItsStartSequenceIsUnknown() = runBlocking {
        server.shipments["SHIP-1"] = server.shipments.getValue("SHIP-1").copy(
            loggerPublicKeyHex = hex(logger.publicKeyUncompressed), loggerStartSeq = null
        )
        courierUploaded(readingsFromTheLogger())
        courierReleases()
        openAsReceiver()
        val reaching = ServerHandover(
            remote = server, orchestrator = orchestrator, shipments = shipments, handovers = handovers,
            history = history, uploadPending = { sync.uploadReadings(it) }, deviceId = { "device-1" },
            clock = { now }, loggerLink = { logger }
        )

        val result = reaching.loggerCheck("SHIP-1")

        assertEquals(LoggerCheckOutcome.UNKNOWN, result.outcome)
        assertTrue(result.reasons.single(), result.reasons.single().contains("start"))
    }

    @Test
    fun withNoLoggerEnrolledTheLoggerIsNotCheckedAndThePassStands() = runBlocking {
        courierUploaded(chain(10))
        courierReleases()
        openAsReceiver()

        assertEquals(LoggerCheckOutcome.NOT_CHECKED, handover.loggerCheck("SHIP-1").outcome)
        receive(HandoverVerdict.PASS).getOrThrow()

        assertEquals(HandoverVerdict.PASS, server.confirmations.last().details.verdict)
        assertEquals(ShipmentStatus.ACCEPTED, localStatus())
    }

    @Test
    fun aReceiverConfirmingSecondCompletesTheHandover() = runBlocking {
        courierUploaded(chain(10))
        courierReleases()
        openAsReceiver()

        val result = receive().getOrThrow() as HandoverResult.Confirmed

        assertEquals(HandoverState.TRANSFERRED, result.outcome.state)
        assertEquals(ShipmentStatus.ACCEPTED, localStatus())
        assertEquals(SyncStatus.SYNCED, db.handoverDao().getHandoverDirect("SHIP-1")?.syncStatus)
    }

    @Test
    fun aPassIsNeverStoredBeforeTheServerAgrees() = runBlocking {
        courierUploaded(chain(10))
        courierReleases()
        openAsReceiver()
        server.failNextConfirm = IOException("Connection dropped")

        val result = receive()

        assertTrue(result.isFailure)
        assertNull(handovers.getHandover("SHIP-1").first())
        assertEquals(ShipmentStatus.IN_TRANSIT, localStatus())
    }

    @Test
    fun withNoSignalAFailIsKeptHereAndSentLater() = runBlocking {
        courierUploaded(chain(10))
        openAsReceiver()
        server.offlineError = IOException("No connection")

        val result = receive(HandoverVerdict.FAIL, "Seal broken").getOrThrow()

        assertTrue(result is HandoverResult.QueuedOffline)
        assertEquals(ShipmentStatus.QUARANTINED, localStatus())
        assertTrue(server.confirmations.isEmpty())

        server.offlineError = null
        sync.syncPendingData().getOrThrow()

        assertEquals(HandoverVerdict.FAIL, server.confirmations.single().details.verdict)
    }

    @Test
    fun withNoSignalAPassCannotBeRecorded() = runBlocking {
        courierUploaded(chain(10))
        openAsReceiver()
        server.offlineError = IOException("No connection")

        assertTrue(receive(HandoverVerdict.PASS).isFailure)
        assertNull(handovers.getHandover("SHIP-1").first())
    }

    @Test
    fun withNoSignalAndNoDeclaredVerdictNothingIsStoredAsAPass() = runBlocking {
        // The third Gate 2 review's mutant X3: only the offline queue's unsynced history keeps this from being a PASS.
        val readings = chain(10)
        courierUploaded(readings)
        openAsReceiver()
        telemetry.saveTemperatures(readings.map { it.copy(syncStatus = SyncStatus.SYNCED) }).getOrThrow()
        server.offlineError = IOException("No connection")

        receive(verdict = null)

        assertNotEquals(HandoverVerdict.PASS, handovers.getHandover("SHIP-1").first()?.verdict)
        assertNotEquals(ShipmentStatus.ACCEPTED, localStatus())
    }

    @Test
    fun anEditedReadingOnThisPhoneCannotPass() = runBlocking {
        val real = chain(10)
        courierUploaded(real)
        openAsReceiver()
        telemetry.saveTemperatures(listOf(real[3].copy(id = "edited", temperature = 4.0, syncStatus = SyncStatus.SYNCED)))

        assertTrue(receive(HandoverVerdict.PASS).isFailure)
        assertTrue(receive(HandoverVerdict.FAIL, "Readings don't match").isSuccess)
    }

    @Test
    fun aRemovedTailIsDownloadedAgainAndCounted() = runBlocking {
        courierUploaded(chain(12, temperature = 12.0).take(12))
        openAsReceiver()
        telemetry.saveTemperatures(chain(12, temperature = 12.0).take(4).map { it.copy(syncStatus = SyncStatus.SYNCED) })
        now = t0 + 13 * minute

        assertTrue(receive(HandoverVerdict.PASS).isFailure)
        assertEquals(12, telemetry.getTemperatures("SHIP-1").first().size)
    }

    @Test
    fun theCustodianSendsEverythingBeforeConfirming() = runBlocking {
        signInAs("courier", MemberRole.COURIER)
        ShipmentWorkflow(server, shipments) { "device-1" }.open("SHIP-1").getOrThrow()
        telemetry.saveTemperatures(chain(6)).getOrThrow()

        val outcome = handover.release("SHIP-1", "receiver").getOrThrow()

        assertEquals(HandoverState.WAITING, outcome.state)
        assertEquals(6, server.readings.getValue("SHIP-1").size)
        val confirmation = server.confirmations.single()
        assertEquals("receiver", confirmation.details.toUserId)
        assertEquals(server.heads.getValue("SHIP-1").headHash, confirmation.details.headHash)
    }

    @Test
    fun theSenderCannotConfirmAndNothingIsStored() = runBlocking {
        courierUploaded(chain(10))
        signInAs("sender", MemberRole.SENDER)
        ShipmentWorkflow(server, shipments) { "device-1" }.open("SHIP-1").getOrThrow()

        val failure = receive().exceptionOrNull()

        assertEquals(RemoteRejection.NOT_ALLOWED, (failure as RemoteRejection).code)
        assertNull(handovers.getHandover("SHIP-1").first())
    }

    @Test
    fun confirmingTheSameHistoryTwiceIsNotAnError() = runBlocking {
        courierUploaded(chain(10))
        openAsReceiver()
        receive().getOrThrow()

        assertTrue(receive().isSuccess)
        assertEquals(1, server.confirmations.size)
    }
}
