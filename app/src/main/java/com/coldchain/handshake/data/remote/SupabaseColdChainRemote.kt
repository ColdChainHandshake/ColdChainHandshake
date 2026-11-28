package com.coldchain.handshake.data.remote

import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.CreatedShipment
import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.GpsPoint
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.HandoverOutcome
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.RemoteShipment
import com.coldchain.handshake.repository.ShipmentMember
import com.coldchain.handshake.repository.UploadedBatch
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime

/**
 * Talks to the functions and tables in `supabase/migrations`. Argument names here must match the SQL
 * exactly, because PostgREST picks a function by them. `ColdChainRemoteContractTest` checks that.
 */
class SupabaseColdChainRemote(private val client: SupabaseClient) : ColdChainRemote {

    override suspend fun completeProfile(displayName: String, role: MemberRole) = call {
        rpc("complete_profile") {
            put("p_display_name", displayName)
            put("p_role", role.wireName)
        }
        Unit
    }

    override suspend fun registerDevice(deviceId: String, publicKey: String) = call {
        rpc("register_device") {
            put("p_device_id", deviceId)
            put("p_public_key", publicKey)
        }
        Unit
    }

    override suspend fun createShipment(
        id: String,
        productProfileId: String,
        loggerId: String,
        origin: String,
        destination: String
    ) = call {
        val created = rpcReturning<CreatedShipmentRow>("create_shipment") {
            put("p_id", id)
            put("p_product_profile_id", productProfileId)
            put("p_logger_id", loggerId)
            put("p_origin", origin)
            put("p_destination", destination)
        }
        CreatedShipment(created.genesisHash, created.createdAtMs, created.senderId)
    }

    override suspend fun addMember(shipmentId: String, email: String, role: MemberRole) = call {
        rpc("add_shipment_member") {
            put("p_shipment_id", shipmentId)
            put("p_email", email)
            put("p_role", role.wireName)
        }
        Unit
    }

    override suspend fun dispatchShipment(shipmentId: String) = call {
        rpc("dispatch_shipment") { put("p_shipment_id", shipmentId) }
        Unit
    }

    override suspend fun startCustody(shipmentId: String, deviceId: String) = call {
        rpc("start_custody") {
            put("p_shipment_id", shipmentId)
            put("p_device_id", deviceId)
        }
        Unit
    }

    override suspend fun requestReplacement(shipmentId: String) = call {
        rpc("request_replacement") { put("p_shipment_id", shipmentId) }
        Unit
    }

    override suspend fun getShipment(shipmentId: String) = call {
        val row = client.postgrest.from("shipments")
            .select { filter { eq("id", shipmentId) } }
            .decodeSingleOrNull<ShipmentRow>()
            ?: return@call null

        // Custody starting is the server's own record of when the journey began.
        val started = client.postgrest.from("custody_events")
            .select(Columns.raw("server_time")) {
                filter {
                    eq("shipment_id", shipmentId)
                    eq("leg", 1)
                }
            }
            .decodeSingleOrNull<CustodyStartRow>()

        RemoteShipment(
            id = row.id,
            senderId = row.senderId,
            productProfileId = row.productProfileId,
            loggerId = row.loggerId,
            origin = row.origin,
            destination = row.destination,
            status = ShipmentStatus.valueOf(row.status),
            createdAtMs = row.createdAt.toEpochMillis(),
            journeyStartedAt = started?.serverTime?.toEpochMillis(),
            loggerPublicKeyHex = row.loggerPublicKey,
            loggerStartSeq = row.loggerStartSeq
        )
    }

    override suspend fun getMembers(shipmentId: String) = call {
        val members = client.postgrest.from("shipment_members")
            .select(Columns.raw("user_id,role")) { filter { eq("shipment_id", shipmentId) } }
            .decodeList<MemberRow>()

        // Profiles hang off the accounts table, so PostgREST can't embed them here.
        val names = client.postgrest.from("profiles")
            .select(Columns.raw("user_id,display_name")) { filter { isIn("user_id", members.map { it.userId }) } }
            .decodeList<MemberNameRow>()
            .associate { it.userId to it.displayName }

        members.map { ShipmentMember(it.userId, MemberRole.valueOf(it.role.uppercase()), names[it.userId]) }
    }

    override suspend fun getCustody(shipmentId: String) = call {
        client.postgrest.from("custody_events")
            .select(Columns.raw("leg,to_user,server_time")) {
                filter { eq("shipment_id", shipmentId) }
                order("leg", Order.DESCENDING)
                limit(1)
            }
            .decodeSingleOrNull<CustodyRow>()
            ?.let { CustodyState(it.leg, it.toUser, it.serverTime?.let(::serverTimeMs)) }
    }

    override suspend fun getChainHead(shipmentId: String) = call {
        client.postgrest.from("chain_heads")
            .select(Columns.raw("head_hash,count")) { filter { eq("shipment_id", shipmentId) } }
            .decodeSingleOrNull<ChainHeadRow>()
            ?.let { ServerChainHead(it.headHash, it.count) }
    }

    override suspend fun getReadingsAfter(shipmentId: String, afterSeq: Long) = call {
        val all = mutableListOf<TemperatureEvent>()
        var last = afterSeq

        // The server caps each response, and that cap can change, so only an empty page means the end.
        while (true) {
            val page = client.postgrest.from("readings")
                .select(Columns.raw("seq,logger_id,logger_ts,temp_centi_c,prev_hash,hash,received_at")) {
                    filter {
                        eq("shipment_id", shipmentId)
                        gt("seq", last)
                    }
                    order("seq", Order.ASCENDING)
                    limit(PAGE_SIZE)
                }
                .decodeList<ReadingRow>()
            if (page.isEmpty()) break

            all += page.map { it.toEvent(shipmentId) }
            last = page.last().seq
        }

        all.toList()
    }

    override suspend fun getBatches(shipmentId: String) = call {
        client.postgrest.from("reading_batches")
            .select(Columns.raw("first_seq,last_seq,head_hash,device_id,signature,devices(public_key)")) {
                filter { eq("shipment_id", shipmentId) }
                order("last_seq", Order.ASCENDING)
            }
            .decodeList<BatchRow>()
            .map { UploadedBatch(it.firstSeq, it.lastSeq, it.headHash, it.deviceId, it.signature, it.device?.publicKey) }
    }

    override suspend fun appendReadings(
        shipmentId: String,
        readings: List<TemperatureEvent>,
        expectedPrevHead: String,
        deviceId: String,
        signature: String
    ) = call {
        val head = rpcReturning<ChainHeadRow>("append_readings") {
            put("p_shipment_id", shipmentId)
            put("p_readings", buildJsonArray {
                readings.forEach { reading ->
                    addJsonObject {
                        put("seq", reading.seq)
                        put("logger_ts", reading.timestamp)
                        put("temp_centi_c", reading.tempCentiC)
                        put("prev_hash", reading.previousHash)
                        put("hash", reading.currentHash)
                    }
                }
            })
            put("p_expected_prev_head", expectedPrevHead)
            put("p_device_id", deviceId)
            put("p_signature", signature)
        }
        ServerChainHead(head.headHash, head.count)
    }

    override suspend fun confirmHandover(confirmation: HandoverConfirmation) = call {
        val outcome = rpcReturning<HandoverOutcomeRow>("confirm_handover") {
            put("p_shipment_id", confirmation.shipmentId)
            put("p_leg", confirmation.leg)
            put("p_device_id", confirmation.deviceId)
            put("p_head_hash", confirmation.headHash)
            put("p_to_user", confirmation.toUserId)
            put("p_verdict", confirmation.verdict?.name)
            put("p_reason", confirmation.reason)
            put("p_vvm_stage", confirmation.vvmStage.name)
            put("p_shake_test", confirmation.shakeTest.name)
        }
        HandoverOutcome(HandoverState.valueOf(outcome.state), ShipmentStatus.valueOf(outcome.status))
    }

    override suspend fun recordAlert(alert: Alert) = call {
        val recorded = rpcReturning<RecordedAlertRow>("record_alert") {
            put("p_shipment_id", alert.shipmentId)
            put("p_type", alert.type.name)
            put("p_excursion_start", alert.excursionStart ?: alert.timestamp)
            put("p_message", alert.message)
        }
        // Later calls use this device's ID, so a difference has to show now and not as a silent miss.
        check(recorded.id == alert.id) { "The server filed alert ${alert.id} as ${recorded.id}." }
    }

    override suspend fun escalateAlert(alertId: String, level: EscalationLevel) = call {
        rpc("escalate_alert") {
            put("p_id", alertId)
            put("p_level", level.name)
        }
        Unit
    }

    override suspend fun acknowledgeAlert(alertId: String) = call {
        rpc("acknowledge_alert") { put("p_id", alertId) }
        Unit
    }

    override suspend fun appendGpsPoints(shipmentId: String, deviceId: String, points: List<GpsPoint>) = call {
        rpc("append_gps_points") {
            put("p_shipment_id", shipmentId)
            put("p_device_id", deviceId)
            put("p_points", buildJsonArray {
                points.forEach { point ->
                    addJsonObject {
                        put("id", point.id)
                        put("lat", point.latitude)
                        put("lon", point.longitude)
                        put("accuracy_m", point.accuracyMeters)
                        put("device_ts", point.deviceTimestamp)
                    }
                }
            })
        }
        Unit
    }

    override suspend fun getLatestGpsPoint(shipmentId: String) = call {
        client.postgrest.from("gps_points")
            .select(Columns.raw("id,shipment_id,lat,lon,accuracy_m,device_ts")) {
                filter { eq("shipment_id", shipmentId) }
                order("device_ts", Order.DESCENDING)
                limit(1)
            }
            .decodeSingleOrNull<GpsRow>()
            ?.let { GpsPoint(it.id, it.shipmentId, it.lat, it.lon, it.accuracyM, it.deviceTs) }
    }

    private suspend fun rpc(function: String, parameters: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        client.postgrest.rpc(function, buildJsonObject(parameters))

    private suspend inline fun <reified T : Any> rpcReturning(
        function: String,
        noinline parameters: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ): T = rpc(function, parameters).decodeAs<T>()

    /**
     * A CC code means the server understood and refused, which callers must not treat as a dropped
     * connection and retry.
     */
    private suspend inline fun <T> call(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: PostgrestRestException) {
        val code = e.code
        Result.failure(if (code != null && code.startsWith("CC")) RemoteRejection(code, e.error) else e)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

    private fun String.toEpochMillis(): Long = OffsetDateTime.parse(this).toInstant().toEpochMilli()

    private fun ReadingRow.toEvent(shipmentId: String) = TemperatureEvent(
        id = "$shipmentId:$seq",
        shipmentId = shipmentId,
        loggerId = loggerId,
        timestamp = loggerTs,
        temperature = tempCentiC / 100.0,
        previousHash = prevHash,
        currentHash = hash,
        syncStatus = SyncStatus.SYNCED,
        seq = seq,
        receivedAt = receivedAt.toEpochMillis()
    )

    private val MemberRole.wireName get() = name.lowercase()

    @Serializable
    private data class CreatedShipmentRow(
        @SerialName("genesis_hash") val genesisHash: String,
        @SerialName("created_at_ms") val createdAtMs: Long,
        @SerialName("sender_id") val senderId: String
    )

    @Serializable
    private data class ShipmentRow(
        val id: String,
        @SerialName("sender_id") val senderId: String,
        @SerialName("product_profile_id") val productProfileId: String,
        @SerialName("logger_id") val loggerId: String,
        val origin: String,
        val destination: String,
        val status: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("logger_public_key") val loggerPublicKey: String? = null,
        @SerialName("logger_start_seq") val loggerStartSeq: Long? = null
    )

    @Serializable
    private data class MemberRow(@SerialName("user_id") val userId: String, val role: String)

    @Serializable
    private data class MemberNameRow(
        @SerialName("user_id") val userId: String,
        @SerialName("display_name") val displayName: String
    )

    @Serializable
    private data class CustodyStartRow(@SerialName("server_time") val serverTime: String)

    @Serializable
    private data class CustodyRow(
        val leg: Int,
        @SerialName("to_user") val toUser: String,
        @SerialName("server_time") val serverTime: String? = null
    )

    /** A `timestamptz` as PostgREST writes it (ISO 8601 with an offset); null if it can't be read. */
    private fun serverTimeMs(text: String): Long? =
        runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    @Serializable
    private data class ChainHeadRow(@SerialName("head_hash") val headHash: String, val count: Long)

    @Serializable
    private data class ReadingRow(
        val seq: Long,
        @SerialName("logger_id") val loggerId: String,
        @SerialName("logger_ts") val loggerTs: Long,
        @SerialName("temp_centi_c") val tempCentiC: Int,
        @SerialName("prev_hash") val prevHash: String,
        val hash: String,
        @SerialName("received_at") val receivedAt: String
    )

    @Serializable
    private data class BatchRow(
        @SerialName("first_seq") val firstSeq: Long,
        @SerialName("last_seq") val lastSeq: Long,
        @SerialName("head_hash") val headHash: String,
        @SerialName("device_id") val deviceId: String,
        val signature: String? = null,
        @SerialName("devices") val device: BatchDeviceRow? = null
    )

    @Serializable
    private data class BatchDeviceRow(@SerialName("public_key") val publicKey: String)

    @Serializable
    private data class HandoverOutcomeRow(val state: String, val status: String)

    @Serializable
    private data class RecordedAlertRow(val id: String)

    @Serializable
    private data class GpsRow(
        val id: String,
        @SerialName("shipment_id") val shipmentId: String,
        val lat: Double,
        val lon: Double,
        @SerialName("accuracy_m") val accuracyM: Float,
        @SerialName("device_ts") val deviceTs: Long
    )

    private companion object {
        const val PAGE_SIZE = 1000L
    }
}
