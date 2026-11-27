package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.crypto.ShipmentManifest
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage

enum class MemberRole {
    SENDER,
    COURIER,
    RECEIVER
}

data class ShipmentMember(
    val userId: String,
    val role: MemberRole,
    val displayName: String?
)

data class CreatedShipment(
    val genesisHash: String,
    val createdAtMs: Long,
    val senderId: String
)

data class RemoteShipment(
    val id: String,
    val senderId: String,
    val productProfileId: String,
    val loggerId: String,
    val origin: String,
    val destination: String,
    val status: ShipmentStatus,
    val createdAtMs: Long,
    val journeyStartedAt: Long?
) {

    /**
     * Derived from the manifest, so a member never has to take the server's word for where the chain
     * starts.
     */
    val genesisHash: String
        get() = HashChainService.genesisHash(
            ShipmentManifest(id, senderId, productProfileId, loggerId, createdAtMs)
        )
}

/**
 * One upload, as the server stored it. [publicKey] is null if the uploader's key can't be read.
 */
data class UploadedBatch(
    val firstSeq: Long,
    val lastSeq: Long,
    val headHash: String,
    val deviceId: String,
    val signature: String?,
    val publicKey: String?
)

/**
 * @property sinceMs When the custodian took over, by the server's clock; null when the server didn't say.
 */
data class CustodyState(
    val leg: Int,
    val custodianId: String,
    val sinceMs: Long? = null
)

enum class HandoverState {
    /** Recorded, but custody hasn't moved: someone still has to confirm this history. */
    WAITING,
    TRANSFERRED
}

data class HandoverOutcome(
    val state: HandoverState,
    val status: ShipmentStatus
)

/**
 * One person's confirmation of a handover. The custodian names [toUserId] and gives no verdict, and
 * the person receiving gives a [verdict]. [headHash] is the history it was made on.
 */
data class HandoverConfirmation(
    val shipmentId: String,
    val leg: Int,
    val deviceId: String,
    val headHash: String,
    val toUserId: String? = null,
    val verdict: HandoverVerdict? = null,
    val reason: String? = null,
    val vvmStage: VvmStage = VvmStage.NOT_RECORDED,
    val shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE
)

data class GpsPoint(
    val id: String,
    val shipmentId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val deviceTimestamp: Long
)

/**
 * The server said no. [code] is one of the CC codes in `0002_trust_core.sql`, and it means the request
 * was understood and refused, so trying again unchanged won't help.
 */
class RemoteRejection(val code: String, message: String) : Exception(message) {
    companion object {
        const val STALE_HEAD = "CC001"
        const val NOT_ALLOWED = "CC002"
        const val SEQUENCE = "CC003"
        const val HASH = "CC004"
        const val DEVICE = "CC005"
        const val BAD_INPUT = "CC006"
        const val WRONG_STATE = "CC007"
        const val ALREADY_CONFIRMED = "CC008"
        const val CLOCK = "CC009"
    }
}

/**
 * Everything the app asks of the server. Clients only read tables, and every change goes through a
 * function that decides whether this user may make it.
 */
interface ColdChainRemote {
    suspend fun completeProfile(displayName: String, role: MemberRole): Result<Unit>
    suspend fun registerDevice(deviceId: String, publicKey: String): Result<Unit>

    suspend fun createShipment(
        id: String,
        productProfileId: String,
        loggerId: String,
        origin: String,
        destination: String
    ): Result<CreatedShipment>
    suspend fun addMember(shipmentId: String, email: String, role: MemberRole): Result<Unit>
    suspend fun dispatchShipment(shipmentId: String): Result<Unit>
    suspend fun startCustody(shipmentId: String, deviceId: String): Result<Unit>
    suspend fun requestReplacement(shipmentId: String): Result<Unit>

    suspend fun getShipment(shipmentId: String): Result<RemoteShipment?>
    suspend fun getMembers(shipmentId: String): Result<List<ShipmentMember>>
    suspend fun getCustody(shipmentId: String): Result<CustodyState?>
    suspend fun getChainHead(shipmentId: String): Result<ServerChainHead?>

    /**
     * Fetches only what's new, so watching a shipment doesn't re-download its whole history. Returns
     * every reading after [afterSeq], however many that takes the server to send.
     */
    suspend fun getReadingsAfter(shipmentId: String, afterSeq: Long): Result<List<TemperatureEvent>>

    suspend fun getBatches(shipmentId: String): Result<List<UploadedBatch>>

    suspend fun appendReadings(
        shipmentId: String,
        readings: List<TemperatureEvent>,
        expectedPrevHead: String,
        deviceId: String,
        signature: String
    ): Result<ServerChainHead>

    suspend fun confirmHandover(confirmation: HandoverConfirmation): Result<HandoverOutcome>

    suspend fun recordAlert(alert: Alert): Result<Unit>
    suspend fun escalateAlert(alertId: String, level: EscalationLevel): Result<Unit>
    suspend fun acknowledgeAlert(alertId: String): Result<Unit>

    suspend fun appendGpsPoints(shipmentId: String, deviceId: String, points: List<GpsPoint>): Result<Unit>
    suspend fun getLatestGpsPoint(shipmentId: String): Result<GpsPoint?>
}
