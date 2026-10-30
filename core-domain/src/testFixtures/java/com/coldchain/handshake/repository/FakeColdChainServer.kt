package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.TemperatureEvent

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
    val confirmations = mutableListOf<HandoverConfirmation>()
    val gpsPoints = mutableListOf<GpsPoint>()
    val signatures = mutableListOf<String>()
    val batches = mutableMapOf<String, MutableList<UploadedBatch>>()
    val devices = mutableMapOf<String, String>()
    var appendCalls = 0
    var readCalls = 0

    /** When set, every call fails this way, as a dropped connection would. */
    var offlineError: Throwable? = null

    /** When set, the next append is stored but its reply is lost, as a dropped connection would. */
    var loseNextAppendReply = false

    /** When set, fetching readings fails this way while other calls still work. */
    var failReads: Throwable? = null

    fun seedShipment(id: String, custodian: String = currentUser, logger: String = "LOG-1"): String {
        val shipment = RemoteShipment(
            id, "sender", "profile", logger, "A", "B", ShipmentStatus.IN_TRANSIT, CREATED_AT, CREATED_AT
        )
        val genesis = shipment.genesisHash
        shipments[id] = shipment
        heads[id] = ServerChainHead(genesis, 0)
        readings[id] = mutableListOf()
        custody[id] = CustodyState(1, custodian)
        return genesis
    }

    private inline fun <T> call(block: () -> T): Result<T> {
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
        destination: String
    ) = call {
        val created = CreatedShipment(seedShipment(id, currentUser, loggerId), CREATED_AT, "sender")
        custody.remove(id)
        created
    }

    override suspend fun addMember(shipmentId: String, email: String, role: MemberRole) = call { }

    override suspend fun dispatchShipment(shipmentId: String) = call { }

    override suspend fun startCustody(shipmentId: String, deviceId: String) = call {
        custody[shipmentId] = CustodyState(1, currentUser)
    }

    override suspend fun requestReplacement(shipmentId: String) = call { }

    override suspend fun getShipment(shipmentId: String) = call { shipments[shipmentId] }

    override suspend fun getCustody(shipmentId: String) = call { custody[shipmentId] }

    override suspend fun getChainHead(shipmentId: String) = call { heads[shipmentId] }

    override suspend fun getReadingsAfter(shipmentId: String, afterSeq: Long) = call {
        failReads?.let { throw it }
        readCalls++
        readings[shipmentId].orEmpty().filter { it.seq > afterSeq }
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

            var running = head
            for (reading in readings) {
                if (reading.seq != running.count + 1) refuse(RemoteRejection.SEQUENCE, "Sequence gap.")
                if (reading.previousHash != running.headHash) refuse(RemoteRejection.HASH, "Wrong link.")
                if (HashChainService.createHash(reading, reading.previousHash) != reading.currentHash) {
                    refuse(RemoteRejection.HASH, "Hash mismatch.")
                }
                running = ServerChainHead(reading.currentHash, reading.seq)
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

    override suspend fun confirmHandover(confirmation: HandoverConfirmation) = call {
        val head = heads[confirmation.shipmentId] ?: refuse(RemoteRejection.NOT_ALLOWED, "Unknown shipment.")
        if (confirmation.headHash != head.headHash) refuse(RemoteRejection.STALE_HEAD, "The history has moved on.")
        if (confirmations.any { it.shipmentId == confirmation.shipmentId && it.headHash == confirmation.headHash && it.deviceId == confirmation.deviceId }) {
            refuse(RemoteRejection.ALREADY_CONFIRMED, "Already confirmed.")
        }
        confirmations += confirmation
        val status = if (confirmation.verdict != null && confirmation.verdict != HandoverVerdict.PASS) {
            ShipmentStatus.QUARANTINED
        } else {
            ShipmentStatus.IN_TRANSIT
        }
        HandoverOutcome(HandoverState.WAITING, status)
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
