package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.ClockSkewDetector
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.awaitCancellation

/**
 * An in-memory stand-in that refuses what the real server refuses, so client code is tested against
 * rejections and not just happy paths. The real rules and their tests are in `supabase/`, and this
 * copy only needs to be faithful enough to exercise the client.
 *
 * @property currentUser Who is calling, as a signed-in session would say.
 */
class FakeColdChainServer(var currentUser: String = "courier") : ColdChainRemote {

    val readings = mutableMapOf<String, MutableList<TemperatureEvent>>()
    val heads = mutableMapOf<String, ServerChainHead>()
    val custody = mutableMapOf<String, CustodyState>()
    val shipments = mutableMapOf<String, RemoteShipment>()
    val alerts = mutableMapOf<String, Alert>()
    val confirmations = mutableListOf<Confirmation>()
    val members = mutableMapOf<String, MutableList<ShipmentMember>>()
    val gpsPoints = mutableListOf<GpsPoint>()
    val signatures = mutableListOf<String>()
    val batches = mutableMapOf<String, MutableList<UploadedBatch>>()
    val devices = mutableMapOf<String, String>()
    val knownEmails = mutableSetOf("courier@example.com", "receiver@example.com")
    var appendCalls = 0
    var readCalls = 0

    /** When set, every call fails this way, as a dropped connection would. */
    var offlineError: Throwable? = null

    /** When set, fetching a head never returns, as a download still running would. */
    var neverFinishes = false

    /** When set, the next confirmation fails this way before reaching the server. */
    var failNextConfirm: Throwable? = null

    /**
     * When set, runs once as the next confirmation arrives, before the server looks at it: what another phone did
     * between this phone fetching the history and its confirmation landing.
     */
    var beforeNextConfirm: (suspend () -> Unit)? = null

    /** When set, the next append is stored but its reply is lost, as a dropped connection would. */
    var loseNextAppendReply = false

    /** When set, fetching readings fails this way while other calls still work. */
    var failReads: Throwable? = null

    /** A confirmation, as the server stores it: who made it, and on which head. */
    data class Confirmation(val userId: String, val role: MemberRole, val details: HandoverConfirmation)

    fun seedShipment(
        id: String,
        custodian: String = currentUser,
        logger: String = "LOG-1",
        createdAt: Long = CREATED_AT,
        productProfileId: String = "vaccine-2-8-freeze-sensitive"
    ): String {
        val shipment = RemoteShipment(
            id, "sender", productProfileId, logger, "A", "B", ShipmentStatus.IN_TRANSIT, createdAt, createdAt
        )
        val genesis = shipment.genesisHash
        shipments[id] = shipment
        heads[id] = ServerChainHead(genesis, 0)
        readings[id] = mutableListOf()
        custody[id] = CustodyState(1, custodian)
        members[id] = mutableListOf(
            ShipmentMember("sender", MemberRole.SENDER, "Sam"),
            ShipmentMember(custodian, MemberRole.COURIER, "Cory"),
            ShipmentMember("receiver", MemberRole.RECEIVER, "Rae")
        )
        return genesis
    }

    /** Every request made, answered or not, as the server's request log would count them. */
    var requests = 0
    /** Every `confirm_handover` made, refused or not. */
    var confirmCalls = 0

    private inline fun <T> call(block: () -> T): Result<T> {
        requests++
        offlineError?.let { return Result.failure(it) }
        return runCatching(block)
    }

    private fun refuse(code: String, message: String): Nothing = throw RemoteRejection(code, message)

    override suspend fun completeProfile(displayName: String, role: MemberRole) = call { }

    override suspend fun registerDevice(deviceId: String, publicKey: String) = call {
        val existing = devices[deviceId]
        if (existing != null && existing != publicKey) refuse(RemoteRejection.DEVICE, "Key cannot change.")
        devices[deviceId] = publicKey
    }

    override suspend fun createShipment(
        id: String,
        productProfileId: String,
        loggerId: String,
        origin: String,
        destination: String,
        loggerPublicKeyHex: String?,
        loggerStartSeq: Long?
    ) = call {
        val genesis = seedShipment(id, currentUser, loggerId)
        shipments[id] = shipments.getValue(id).copy(
            loggerPublicKeyHex = loggerPublicKeyHex?.lowercase(),
            loggerStartSeq = loggerStartSeq,
            productProfileId = productProfileId,
            origin = origin,
            destination = destination,
            status = ShipmentStatus.CREATED,
            journeyStartedAt = null
        )
        heads[id] = ServerChainHead(shipments.getValue(id).genesisHash, 0)
        members[id] = mutableListOf(ShipmentMember(currentUser, MemberRole.SENDER, null))
        custody.remove(id)
        CreatedShipment(genesis, CREATED_AT, currentUser)
    }

    override suspend fun addMember(shipmentId: String, email: String, role: MemberRole) = call {
        if (email !in knownEmails) refuse(RemoteRejection.BAD_INPUT, "No account has that email address.")
        members.getOrPut(shipmentId) { mutableListOf() } += ShipmentMember(email, role, null)
        Unit
    }

    override suspend fun getMembers(shipmentId: String) = call { members[shipmentId].orEmpty().toList() }

    override suspend fun dispatchShipment(shipmentId: String) = call {
        moveTo(shipmentId, ShipmentStatus.CREATED, ShipmentStatus.DISPATCHED)
    }

    override suspend fun startCustody(shipmentId: String, deviceId: String) = call {
        moveTo(shipmentId, ShipmentStatus.DISPATCHED, ShipmentStatus.IN_TRANSIT)
        custody[shipmentId] = CustodyState(1, currentUser)
        shipments[shipmentId] = shipments.getValue(shipmentId).copy(journeyStartedAt = CREATED_AT + 1)
    }

    override suspend fun requestReplacement(shipmentId: String) = call {
        moveTo(shipmentId, ShipmentStatus.QUARANTINED, ShipmentStatus.REPLACEMENT_REQUESTED)
    }

    private fun moveTo(shipmentId: String, from: ShipmentStatus, to: ShipmentStatus) {
        val shipment = shipments[shipmentId] ?: refuse(RemoteRejection.NOT_ALLOWED, "Unknown shipment.")
        if (shipment.status != from) refuse(RemoteRejection.WRONG_STATE, "This shipment can't do that now.")
        shipments[shipmentId] = shipment.copy(status = to)
    }

    override suspend fun getShipment(shipmentId: String) = call { shipments[shipmentId] }

    override suspend fun getCustody(shipmentId: String) = call { custody[shipmentId] }

    override suspend fun getChainHead(shipmentId: String): Result<ServerChainHead?> {
        if (neverFinishes) awaitCancellation()
        return call { heads[shipmentId] }
    }

    override suspend fun getReadingsAfter(shipmentId: String, afterSeq: Long) = call {
        failReads?.let { throw it }
        readCalls++
        // As the real client returns them: already on the server, with the time it received them.
        readings[shipmentId].orEmpty().filter { it.seq > afterSeq }
            .map { it.copy(syncStatus = SyncStatus.SYNCED, receivedAt = it.receivedAt ?: it.timestamp) }
    }

    override suspend fun getBatches(shipmentId: String) = call { batches[shipmentId].orEmpty().toList() }

    override suspend fun appendReadings(
        shipmentId: String,
        readings: List<TemperatureEvent>,
        expectedPrevHead: String,
        deviceId: String,
        signature: String
    ): Result<ServerChainHead> {
        val result = call {
            appendCalls++
            val head = heads[shipmentId] ?: refuse(RemoteRejection.NOT_ALLOWED, "Unknown shipment.")
            if (custody[shipmentId]?.custodianId != currentUser) {
                refuse(RemoteRejection.NOT_ALLOWED, "Only the current custodian can add readings.")
            }
            if (head.headHash != expectedPrevHead) refuse(RemoteRejection.STALE_HEAD, "The history has moved on.")

            // As append_readings: later than the reading before, and no more than the clock tolerance before the
            // shipment was created. The server leaves readings dated in the future out of "the reading before";
            // this fake has no clock, so it treats none as future.
            var previousTs = this.readings.getValue(shipmentId).maxOfOrNull { it.timestamp }
            val earliest = shipments[shipmentId]?.createdAtMs?.minus(ClockSkewDetector.TOLERANCE_MS)

            var running = head
            for (reading in readings) {
                if (reading.seq != running.count + 1) refuse(RemoteRejection.SEQUENCE, "Sequence gap.")
                if (reading.previousHash != running.headHash) refuse(RemoteRejection.HASH, "Wrong link.")
                val backDated = previousTs?.let { reading.timestamp <= it } == true ||
                    earliest?.let { reading.timestamp < it } == true
                if (backDated) refuse(RemoteRejection.CLOCK, "Reading ${reading.seq} is back-dated.")
                if (HashChainService.createHash(reading, reading.previousHash) != reading.currentHash) {
                    refuse(RemoteRejection.HASH, "Hash mismatch.")
                }
                running = ServerChainHead(reading.currentHash, reading.seq)
                previousTs = reading.timestamp
            }

            this.readings.getValue(shipmentId).addAll(readings)
            heads[shipmentId] = running
            signatures += signature
            batches.getOrPut(shipmentId) { mutableListOf() } +=
                UploadedBatch(readings.first().seq, running.count, running.headHash, deviceId, signature, devices[deviceId])
            running
        }

        if (loseNextAppendReply) {
            loseNextAppendReply = false
            return Result.failure(IllegalStateException("Connection dropped before the reply arrived."))
        }
        return result
    }

    override suspend fun confirmHandover(confirmation: HandoverConfirmation): Result<HandoverOutcome> {
        confirmCalls++
        beforeNextConfirm?.let {
            beforeNextConfirm = null
            val caller = currentUser
            it()
            currentUser = caller
        }
        return confirm(confirmation)
    }

    private fun confirm(confirmation: HandoverConfirmation) = call {
        failNextConfirm?.let {
            failNextConfirm = null
            throw it
        }
        val id = confirmation.shipmentId
        val shipment = shipments[id] ?: refuse(RemoteRejection.NOT_ALLOWED, "Unknown shipment.")
        val current = custody[id]
        val role = members[id].orEmpty().firstOrNull { it.userId == currentUser }?.role
        val isCustodian = current?.custodianId == currentUser

        if (role == null || (role == MemberRole.SENDER && !isCustodian)) {
            refuse(RemoteRejection.NOT_ALLOWED, "Only the people handing over can confirm.")
        }
        if (current?.leg != confirmation.leg ||
            shipment.status !in listOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.QUARANTINED)
        ) {
            refuse(RemoteRejection.WRONG_STATE, "This handover is not open.")
        }
        if (confirmation.headHash != heads.getValue(id).headHash) {
            refuse(RemoteRejection.STALE_HEAD, "The history has moved on.")
        }

        val stored = if (isCustodian) {
            val named = members.getValue(id).firstOrNull { it.userId == confirmation.toUserId }
            if (named == null || named.userId == currentUser || named.role == MemberRole.SENDER) {
                refuse(RemoteRejection.BAD_INPUT, "The custodian must name who takes over.")
            }
            confirmation.copy(verdict = null, reason = null)
        } else {
            val verdict = confirmation.verdict ?: refuse(RemoteRejection.BAD_INPUT, "A verdict is needed.")
            if (verdict != HandoverVerdict.PASS && confirmation.reason.isNullOrBlank()) {
                refuse(RemoteRejection.BAD_INPUT, "A $verdict verdict needs a reason.")
            }
            confirmation.copy(toUserId = null)
        }

        val onThisLeg = confirmations.filter { it.details.shipmentId == id && it.details.leg == confirmation.leg }
        if (onThisLeg.any { it.userId == currentUser && it.details.headHash == confirmation.headHash }) {
            refuse(RemoteRejection.ALREADY_CONFIRMED, "You have already confirmed this history.")
        }
        confirmations += Confirmation(currentUser, role, stored)

        val all = onThisLeg + confirmations.last()
        val onThisHead = all.filter { it.details.headHash == heads.getValue(id).headHash }
        val releasing = onThisHead.firstOrNull { it.userId == current?.custodianId }
        val receiving = onThisHead.firstOrNull { it.userId == releasing?.details?.toUserId }

        if (all.any {
                val verdict = it.details.verdict
                verdict != null && verdict != HandoverVerdict.PASS &&
                    (it.role == MemberRole.RECEIVER || it.userId == releasing?.details?.toUserId)
            } && shipments.getValue(id).status == ShipmentStatus.IN_TRANSIT
        ) {
            shipments[id] = shipments.getValue(id).copy(status = ShipmentStatus.QUARANTINED)
        }

        if (receiving == null) {
            HandoverOutcome(HandoverState.WAITING, shipments.getValue(id).status)
        } else {
            custody[id] = CustodyState(confirmation.leg + 1, receiving.userId, sinceMs = System.currentTimeMillis())
            if (receiving.role == MemberRole.RECEIVER && shipments.getValue(id).status == ShipmentStatus.IN_TRANSIT) {
                shipments[id] = shipments.getValue(id).copy(status = ShipmentStatus.ACCEPTED)
            }
            HandoverOutcome(HandoverState.TRANSFERRED, shipments.getValue(id).status)
        }
    }

    override suspend fun recordAlert(alert: Alert) = call {
        alerts.putIfAbsent(alert.id, alert)
        Unit
    }

    override suspend fun escalateAlert(alertId: String, level: EscalationLevel) = call {
        alerts[alertId]?.let { alerts[alertId] = it.copy(escalationLevel = level) }
        Unit
    }

    override suspend fun acknowledgeAlert(alertId: String) = call {
        if (currentUser == "courier") refuse(RemoteRejection.NOT_ALLOWED, "Only the sender or receiver can acknowledge.")
        alerts[alertId]?.let { alerts[alertId] = it.copy(acknowledged = true) }
        Unit
    }

    override suspend fun appendGpsPoints(shipmentId: String, deviceId: String, points: List<GpsPoint>) = call {
        if (custody[shipmentId]?.custodianId != currentUser) {
            refuse(RemoteRejection.NOT_ALLOWED, "Only the current custodian can report location.")
        }
        gpsPoints += points.filter { point -> gpsPoints.none { it.id == point.id } }
        Unit
    }

    override suspend fun getLatestGpsPoint(shipmentId: String) = call {
        gpsPoints.filter { it.shipmentId == shipmentId }.maxByOrNull { it.deviceTimestamp }
    }

    private companion object {
        const val CREATED_AT = 1_700_000_000_000L
    }
}
