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
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentMember
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.repository.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 5.3, multi-leg custody (finding 14): sender → courier A → courier B → receiver. Courier B takes over from courier A
 * like a receiver, on their own phone, then carries the same chain on and hands over again. The server moves custody
 * on each pair of confirmations and accepts the shipment only when a receiver takes it.
 *
 * Three phones, each with its own database, against one fake server that applies `confirm_handover`'s rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiLegHandoverTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val server = FakeColdChainServer(currentUser = "courier").apply {
        val genesis = seedShipment("SHIP-1")
        members.getValue("SHIP-1") += ShipmentMember("courier-b", MemberRole.COURIER, "Bea")
        this@MultiLegHandoverTest.genesis = genesis
    }
    private lateinit var genesis: String

    private val trip: List<TemperatureEvent> by lazy {
        HashChainService.buildChain(
            (1..12).map { TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", t0 + it * minute, 5.0, "", "", SyncStatus.PENDING) },
            genesis
        )
    }

    private val signer = object : DeviceSigner {
        override val publicKey = "public-key"
        override fun sign(payload: ByteArray) = "signature"
    }

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
        var now = t0
        val handover = ServerHandover(
            remote = server,
            orchestrator = HandoverOrchestrator(shipments, telemetry, handovers, RoomTransactionRunner(db)),
            shipments = shipments,
            handovers = handovers,
            history = history,
            uploadPending = { sync.uploadReadings(it) },
            deviceId = { deviceId },
            clock = { now }
        )

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

        /** This phone makes the [n]th reading of the trip and sends it. */
        fun recordAndSend(n: Int) = act {
            now = trip[n - 1].timestamp
            telemetry.saveTemperatures(listOf(trip[n - 1])).getOrThrow()
            sync.uploadReadings("SHIP-1")
        }

        fun release(to: String) = act { handover.release("SHIP-1", to).getOrThrow() }

        fun receive() = act {
            handover.receive("SHIP-1", HandoverVerdict.PASS, null, VvmStage.NOT_RECORDED, ShakeTestResult.NOT_DONE)
        }
    }

    private val courierA = Phone("courier", MemberRole.COURIER, "courier-a-phone")
    private val courierB = Phone("courier-b", MemberRole.COURIER, "courier-b-phone")
    private val receiver = Phone("receiver", MemberRole.RECEIVER, "receiver-phone")

    @After
    fun tearDown() {
        listOf(courierA, courierB, receiver).forEach { it.db.close() }
    }

    private fun custodian() = server.custody.getValue("SHIP-1").custodianId
    private fun serverCount() = server.heads.getValue("SHIP-1").count

    @Test
    fun aShipmentGoesFromOneCourierToAnotherAndThenToTheReceiver() {
        courierA.open()
        (1..5).forEach { courierA.recordAndSend(it).getOrThrow() }
        courierA.now = trip[4].timestamp + minute / 2
        assertEquals(HandoverState.WAITING, courierA.release(to = "courier-b").state)

        // Courier B checks the history on their own phone and confirms: custody moves, the shipment stays on the move.
        courierB.open()
        courierB.now = trip[4].timestamp + minute
        val taken = courierB.receive().getOrThrow() as HandoverResult.Confirmed
        assertEquals(HandoverState.TRANSFERRED, taken.outcome.state)
        assertEquals(ShipmentStatus.IN_TRANSIT, taken.outcome.status)
        assertEquals("courier-b", custodian())

        // Courier B's phone carries the same chain on from the history it downloaded.
        (6..8).forEach { courierB.recordAndSend(it).getOrThrow() }
        assertEquals(8L, serverCount())

        // Courier B hands over to the receiver, who accepts the whole trip.
        courierB.now = trip[7].timestamp + minute / 2
        assertEquals(HandoverState.WAITING, courierB.release(to = "receiver").state)
        receiver.open()
        receiver.now = trip[7].timestamp + minute
        val accepted = receiver.receive().getOrThrow() as HandoverResult.Confirmed

        assertEquals(HandoverState.TRANSFERRED, accepted.outcome.state)
        assertEquals(ShipmentStatus.ACCEPTED, server.shipments.getValue("SHIP-1").status)
        assertEquals("receiver", custodian())
        assertEquals(3, server.custody.getValue("SHIP-1").leg)
        assertEquals(8, runBlocking { receiver.telemetry.getTemperatures("SHIP-1").first().size })
    }

    @Test
    fun theFirstCourierCanNoLongerAddReadingsOnceTheSecondHasCustody() {
        courierA.open()
        (1..5).forEach { courierA.recordAndSend(it).getOrThrow() }
        courierA.release(to = "courier-b")
        courierB.open()
        courierB.now = trip[4].timestamp + minute
        courierB.receive().getOrThrow()

        val late = courierA.recordAndSend(6)

        assertTrue(late.isFailure)
        assertTrue(late.exceptionOrNull().toString(), late.exceptionOrNull() is RemoteRejection)
        assertEquals(5L, serverCount())
    }
}
