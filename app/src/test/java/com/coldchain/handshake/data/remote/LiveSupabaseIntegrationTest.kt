package com.coldchain.handshake.data.remote

import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.IntegrityVerificationState
import com.coldchain.handshake.crypto.ShipmentIntegrityEvaluator
import com.coldchain.handshake.crypto.UploadAudit
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.ReadingUploader
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * A whole handover through real PostgREST and GoTrue, with three accounts on three clients, using the
 * app's own remote, uploader and checks. Run it only against a project you own, such as the local stack:
 *
 * ```
 * CCH_LIVE_SUPABASE_URL=http://127.0.0.1:54321 CCH_LIVE_SUPABASE_KEY=<anon key> ./gradlew testDebugUnitTest
 * ```
 *
 * It signs up three new accounts with passwords each run, so the project must allow sign-up without email
 * confirmation. The build excludes it otherwise, and refuses the demo project.
 */
class LiveSupabaseIntegrationTest {

    private val url = System.getenv("CCH_LIVE_SUPABASE_URL") ?: error("Set CCH_LIVE_SUPABASE_URL")
    private val key = System.getenv("CCH_LIVE_SUPABASE_KEY") ?: error("Set CCH_LIVE_SUPABASE_KEY")
    private val run = UUID.randomUUID().toString().take(8)

    private fun client(): SupabaseClient = createSupabaseClient(url, key) {
        defaultSerializer = KotlinXSerializer(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
        install(Postgrest)
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            autoLoadFromStorage = false
            alwaysAutoRefresh = false
            enableLifecycleCallbacks = false
        }
    }

    private class SoftwareSigner : DeviceSigner {
        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)
        override fun sign(payload: ByteArray): String = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(payload)
            Base64.getEncoder().encodeToString(sign())
        }
    }

    private inner class Person(name: String, val role: MemberRole) {
        val email = "$name-$run@example.com"
        val client = client()
        val remote: ColdChainRemote = SupabaseColdChainRemote(client)
        val deviceId = UUID.randomUUID().toString()
        val signer = SoftwareSigner()

        suspend fun join() {
            client.auth.signUpWith(Email) {
                email = this@Person.email
                password = "password-$run"
            }
            remote.completeProfile(role.name.lowercase(), role).getOrThrow()
            remote.registerDevice(deviceId, signer.publicKey).getOrThrow()
        }

        val userId: String get() = client.auth.currentUserOrNull()!!.id
    }

    private fun chain(genesis: String, shipmentId: String, count: Int): List<TemperatureEvent> {
        val now = System.currentTimeMillis()
        return HashChainService.buildChain(
            (count downTo 1).map { back ->
                TemperatureEvent(
                    "TE-$back", shipmentId, "LOG-$run", now - back * 60_000L, 5.0, "", "", SyncStatus.PENDING
                )
            },
            genesis
        )
    }

    @Test
    fun aShipmentGoesFromSenderToReceiverAndEveryShortcutIsRefused() = runBlocking {
        val sender = Person("sender", MemberRole.SENDER)
        val courier = Person("courier", MemberRole.COURIER)
        val receiver = Person("receiver", MemberRole.RECEIVER)
        listOf(sender, courier, receiver).forEach { it.join() }
        val shipmentId = "SHIP-$run"

        // The sender creates, names the people and dispatches.
        val created = sender.remote.createShipment(
            shipmentId, ProductProfiles.DEFAULT.id, "LOG-$run", "Depot", "Clinic"
        ).getOrThrow()
        sender.remote.addMember(shipmentId, courier.email, MemberRole.COURIER).getOrThrow()
        sender.remote.addMember(shipmentId, receiver.email, MemberRole.RECEIVER).getOrThrow()
        sender.remote.dispatchShipment(shipmentId).getOrThrow()

        // The genesis anyone can derive from the manifest matches the one the server built.
        val asCourierSees = courier.remote.getShipment(shipmentId).getOrThrow()!!
        assertEquals(created.genesisHash, asCourierSees.genesisHash)

        // Nobody else can take custody, and the courier can.
        assertEquals(RemoteRejection.NOT_ALLOWED, (receiver.remote.startCustody(shipmentId, receiver.deviceId).exceptionOrNull() as RemoteRejection).code)
        courier.remote.startCustody(shipmentId, courier.deviceId).getOrThrow()

        // The courier uploads signed readings through the app's own uploader.
        val readings = chain(created.genesisHash, shipmentId, 8)
        val uploaded = ReadingUploader(courier.remote, courier.signer, { courier.deviceId }, maxBatchSize = 5)
            .upload(shipmentId, readings).getOrThrow()
        assertEquals(8L, uploaded.head.count)

        // Shortcuts are refused: a stale head, an edited reading, a reading from someone without custody.
        val next = chain(created.genesisHash, shipmentId, 9).last()
        val stale = courier.remote.appendReadings(shipmentId, listOf(next), created.genesisHash, courier.deviceId, "s")
        assertEquals(RemoteRejection.STALE_HEAD, (stale.exceptionOrNull() as RemoteRejection).code)
        val edited = courier.remote.appendReadings(
            shipmentId, listOf(next.copy(temperature = 4.0)), uploaded.head.headHash, courier.deviceId, "s"
        )
        assertEquals(RemoteRejection.HASH, (edited.exceptionOrNull() as RemoteRejection).code)
        val notCustodian = receiver.remote.appendReadings(shipmentId, listOf(next), uploaded.head.headHash, receiver.deviceId, "s")
        assertEquals(RemoteRejection.NOT_ALLOWED, (notCustodian.exceptionOrNull() as RemoteRejection).code)

        // The receiver downloads the history, which matches the server, with every upload's signature good.
        val receiverPhone = InMemoryTelemetryRepository()
        val history = ShipmentHistorySyncCoordinator(receiverPhone, receiver.remote)
        history.hydrateHistory(shipmentId).getOrThrow()
        val events = receiverPhone.getTemperatures(shipmentId).first()
        val integrity = ShipmentIntegrityEvaluator.evaluate(events, true, created.genesisHash, history.getServerHead(shipmentId).value)
        assertEquals(IntegrityVerificationState.VERIFIED, integrity.state)
        assertTrue(events.all { it.receivedAt != null })
        val batches = receiver.remote.getBatches(shipmentId).getOrThrow()
        assertEquals(2, batches.size)
        assertTrue(UploadAudit.check(shipmentId, batches, events).isEmpty())

        // One phone can't confirm for someone else.
        val head = uploaded.head.headHash
        val borrowed = receiver.remote.confirmHandover(
            HandoverConfirmation(shipmentId, 1, courier.deviceId, head, verdict = HandoverVerdict.PASS)
        )
        assertEquals(RemoteRejection.DEVICE, (borrowed.exceptionOrNull() as RemoteRejection).code)

        // The sender can't confirm at all.
        val senderTries = sender.remote.confirmHandover(
            HandoverConfirmation(shipmentId, 1, sender.deviceId, head, verdict = HandoverVerdict.PASS)
        )
        assertEquals(RemoteRejection.NOT_ALLOWED, (senderTries.exceptionOrNull() as RemoteRejection).code)

        // Custody moves only once both people have confirmed the same history.
        val released = courier.remote.confirmHandover(
            HandoverConfirmation(shipmentId, 1, courier.deviceId, head, toUserId = receiver.userId)
        ).getOrThrow()
        assertEquals(HandoverState.WAITING, released.state)
        val accepted = receiver.remote.confirmHandover(
            HandoverConfirmation(shipmentId, 1, receiver.deviceId, head, verdict = HandoverVerdict.PASS)
        ).getOrThrow()
        assertEquals(HandoverState.TRANSFERRED, accepted.state)
        assertEquals(ShipmentStatus.ACCEPTED, sender.remote.getShipment(shipmentId).getOrThrow()?.status)
        assertEquals(receiver.userId, sender.remote.getCustody(shipmentId).getOrThrow()?.custodianId)

        // The old custodian can no longer add readings.
        val afterwards = courier.remote.appendReadings(shipmentId, listOf(next), head, courier.deviceId, "s")
        assertEquals(RemoteRejection.NOT_ALLOWED, (afterwards.exceptionOrNull() as RemoteRejection).code)
    }

    @Test
    fun someoneNotSignedInSeesNothing() = runBlocking {
        val anonymous = client()

        val rows = runCatching {
            anonymous.postgrest.from("readings").select().decodeList<JsonObject>()
        }

        assertTrue("anonymous read returned ${rows.getOrNull()?.size} rows", rows.isFailure || rows.getOrThrow().isEmpty())
    }

    @Test
    fun aStrangerSeesNoneOfAnotherShipment() = runBlocking {
        val sender = Person("owner", MemberRole.SENDER)
        val stranger = Person("stranger", MemberRole.COURIER)
        sender.join()
        stranger.join()
        val shipmentId = "SHIP-S-$run"
        sender.remote.createShipment(shipmentId, ProductProfiles.DEFAULT.id, "LOG-S-$run", "A", "B").getOrThrow()

        assertEquals(null, stranger.remote.getShipment(shipmentId).getOrThrow())
        assertEquals(null, stranger.remote.getChainHead(shipmentId).getOrThrow())
    }
}
