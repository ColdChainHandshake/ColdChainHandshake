package com.coldchain.handshake.handover

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.ColdChainDatabase
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.RoomTransactionRunner
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.FakeNetworkMonitor
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ReadingUploader
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.repository.UserProfile
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

/**
 * The handover window: the receiver's verdict covers readings up to the receiver's own confirmation, so a receiver
 * who inspects for longer than the gap limit needs the courier to keep recording until custody moves. The courier's
 * confirmation names who takes over and is renewed on each new head, because the server moves custody only when both
 * confirmations are on the history as it stands.
 *
 * Two phones, each with its own database, against one fake server that applies `confirm_handover`'s rules. Each
 * phone's calls run as its own person.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandoverWindowTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val server = FakeColdChainServer(currentUser = "courier")
    private val genesis = server.seedShipment("SHIP-1")

    /** The whole trip's readings, one a minute from the minute after creation. A phone stores them as they happen. */
    private val trip: List<TemperatureEvent> = HashChainService.buildChain(
        (1..30).map {
            TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", t0 + it * minute, 5.0, "", "", SyncStatus.PENDING)
        },
        genesis
    )

    private val signer = object : DeviceSigner {
        override val publicKey = "public-key"
        override fun sign(payload: ByteArray) = "signature"
    }

    /** One person's phone: its own database, uploader, history and handover, signed in as [user]. */
    private inner class Phone(val user: String, role: MemberRole, val deviceId: String) {
        val db: ColdChainDatabase = DatabaseProvider.createInMemoryDatabase(context)
        val shipments = ShipmentRepositoryImpl(db.shipmentDao())
        val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
        val handovers = HandoverRepositoryImpl(db.handoverDao())
        val account = FakeAccountRepository().apply { setState(AccountState.Ready(user, null, UserProfile(user, role))) }
        val sync = SyncServiceImpl(
            db.shipmentDao(), db.temperatureEventDao(), db.alertDao(), db.handoverDao(), db.gpsPointDao(), server,
            ReadingUploader(server, signer, { deviceId }), account, FakeNetworkMonitor(), { deviceId }
        )
        val history = ShipmentHistorySyncCoordinator(telemetry, server)
        val pendingReleases = PendingReleases.inMemory()
        var now = t0

        val handover = ServerHandover(
            remote = server,
            orchestrator = HandoverOrchestrator(shipments, telemetry, handovers, RoomTransactionRunner(db)),
            shipments = shipments,
            handovers = handovers,
            history = history,
            uploadPending = { sync.uploadReadings(it) },
            deviceId = { deviceId },
            clock = { now },
            pendingReleases = pendingReleases
        )

        /** Runs [block] as this phone's person, as the server sees it. */
        fun <T> act(block: suspend Phone.() -> T): T = runBlocking {
            val before = server.currentUser
            server.currentUser = user
            try {
                block()
            } finally {
                server.currentUser = before
            }
        }

        fun open() = act { ShipmentWorkflow(server, shipments) { deviceId }.open("SHIP-1").getOrThrow() }

        /** The courier's phone makes the [n]th reading of the trip, at its own minute. */
        fun record(n: Int) = act {
            now = trip[n - 1].timestamp
            telemetry.saveTemperatures(listOf(trip[n - 1])).getOrThrow()
        }

        fun receive(verdict: HandoverVerdict?, reason: String? = null) = act {
            handover.receive("SHIP-1", verdict, reason, VvmStage.NOT_RECORDED, ShakeTestResult.NOT_DONE)
        }
    }

    private val courier = Phone("courier", MemberRole.COURIER, "courier-phone")
    private val receiver = Phone("receiver", MemberRole.RECEIVER, "receiver-phone")

    @After
    fun tearDown() {
        courier.db.close()
        receiver.db.close()
    }

    /** Readings 1 to 5, sent, then the courier names the receiver half a minute after the fifth. */
    private fun courierRecordsFiveAndReleases() {
        courier.open()
        (1..5).forEach { courier.record(it) }
        courier.now = trip[4].timestamp + minute / 2
        val released = courier.act { handover.release("SHIP-1", "receiver").getOrThrow() }
        assertEquals(HandoverState.WAITING, released.state)
    }

    private fun serverStatus() = server.shipments.getValue("SHIP-1").status

    @Test
    fun aCourierWhoConfirmsEarlyAndStopsRecordingCannotGetAPass() {
        courierRecordsFiveAndReleases()

        // The courier's phone records nothing more. The receiver inspects for ten minutes, then confirms.
        receiver.open()
        receiver.now = trip[4].timestamp + 10 * minute

        val declaredPass = receiver.receive(HandoverVerdict.PASS)
        assertTrue("a PASS can't be recorded over a gap", declaredPass.isFailure)

        val computed = receiver.receive(verdict = null).getOrThrow() as HandoverResult.Confirmed
        assertEquals(HandoverVerdict.UNKNOWN, computed.handover?.verdict)
        assertNotEquals(ShipmentStatus.ACCEPTED, serverStatus())
        assertTrue(server.confirmations.none { it.details.verdict == HandoverVerdict.PASS })
    }

    @Test
    fun aReceiverCanInspectForTenMinutesWhileTheCourierKeepsRecording() {
        courierRecordsFiveAndReleases()
        receiver.open()

        // Each minute the courier's phone records, sends, and renews its confirmation on the new head.
        for (n in 6..15) {
            courier.record(n)
            courier.act { handover.renewRelease("SHIP-1").getOrThrow() }
        }

        receiver.now = trip[14].timestamp + minute / 2
        val result = receiver.receive(HandoverVerdict.PASS).getOrThrow() as HandoverResult.Confirmed

        assertEquals(HandoverState.TRANSFERRED, result.outcome.state)
        assertEquals(ShipmentStatus.ACCEPTED, serverStatus())
        assertEquals("receiver", server.custody.getValue("SHIP-1").custodianId)
        assertEquals(15, server.heads.getValue("SHIP-1").count)
        val receivers = server.confirmations.filter { it.userId == "receiver" }
        assertEquals(server.heads.getValue("SHIP-1").headHash, receivers.single().details.headHash)
    }

    @Test
    fun theCourierStopsRenewingOnceCustodyHasMoved() {
        courierRecordsFiveAndReleases()
        receiver.open()
        courier.record(6)
        courier.act { handover.renewRelease("SHIP-1").getOrThrow() }
        receiver.now = trip[5].timestamp + minute / 2
        receiver.receive(HandoverVerdict.PASS).getOrThrow()
        val confirmationsBefore = server.confirmations.size

        val renewed = courier.act { handover.renewRelease("SHIP-1") }

        assertTrue(renewed.isSuccess)
        assertNull("nothing was renewed", renewed.getOrThrow())
        assertEquals(confirmationsBefore, server.confirmations.size)
        assertNull("the release is forgotten", courier.pendingReleases.get("SHIP-1"))
    }

    @Test
    fun renewingWithNothingNewSendsNoSecondConfirmation() {
        courierRecordsFiveAndReleases()
        val confirmationsBefore = server.confirmations.size

        val renewed = courier.act { handover.renewRelease("SHIP-1").getOrThrow() }

        assertEquals(HandoverState.WAITING, renewed?.state)
        assertEquals(confirmationsBefore, server.confirmations.size)
    }

    @Test
    fun aReadingThatLandsWhileTheReceiverConfirmsIsCheckedAndConfirmedAgain() {
        courierRecordsFiveAndReleases()
        receiver.open()
        courier.record(6)
        courier.act { handover.renewRelease("SHIP-1").getOrThrow() }

        // Between the receiver's phone fetching the history and its confirmation reaching the server, the courier's
        // phone sends reading 7 and renews.
        server.beforeNextConfirm = {
            courier.record(7)
            courier.act { handover.renewRelease("SHIP-1").getOrThrow() }
        }
        receiver.now = trip[6].timestamp + minute / 2
        val result = receiver.receive(HandoverVerdict.PASS).getOrThrow() as HandoverResult.Confirmed

        assertEquals(HandoverState.TRANSFERRED, result.outcome.state)
        assertEquals(7, server.heads.getValue("SHIP-1").count)
        val receivers = server.confirmations.filter { it.userId == "receiver" }
        assertEquals(server.heads.getValue("SHIP-1").headHash, receivers.single().details.headHash)
    }

    @Test
    fun aFrozenReadingThatLandsWhileTheReceiverConfirmsIsNotPassedOver() {
        courierRecordsFiveAndReleases()
        receiver.open()

        // The profile allows no time at all below 2 °C, so one frozen reading fails the trip.
        val frozen = HashChainService.appendHash(
            TemperatureEvent("TE-COLD", "SHIP-1", "LOG-1", trip[5].timestamp, -3.0, "", "", SyncStatus.PENDING, seq = 6),
            trip[4].currentHash
        )
        server.beforeNextConfirm = {
            courier.act {
                now = frozen.timestamp
                telemetry.saveTemperatures(listOf(frozen)).getOrThrow()
                handover.renewRelease("SHIP-1").getOrThrow()
            }
        }
        receiver.now = trip[5].timestamp + minute / 2

        val result = receiver.receive(HandoverVerdict.PASS)

        assertTrue("the PASS is refused once the frozen reading is checked", result.isFailure)
        assertNotEquals(ShipmentStatus.ACCEPTED, serverStatus())
        assertTrue(server.confirmations.none { it.details.verdict == HandoverVerdict.PASS })
    }
}
