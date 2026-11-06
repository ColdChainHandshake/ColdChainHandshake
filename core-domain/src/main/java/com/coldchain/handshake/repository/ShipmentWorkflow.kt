package com.coldchain.handshake.repository

import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment

data class ShipmentDraft(
    val id: String,
    val productProfileId: String,
    val loggerId: String,
    val origin: String,
    val destination: String
)

/**
 * The steps of a shipment's life that only the server can decide. Each one asks the server first and
 * stores the answer, so this phone never shows a step the server didn't accept.
 *
 * Creating a shipment needs a connection, because the server fixes the time its chain is built from.
 */
class ShipmentWorkflow(
    private val remote: ColdChainRemote,
    private val shipments: ShipmentRepository,
    private val deviceId: () -> String
) {

    suspend fun create(draft: ShipmentDraft): Result<Shipment> {
        require(ProductProfiles.ALL.any { it.id == draft.productProfileId }) { "Unknown product." }

        remote.createShipment(
            draft.id, draft.productProfileId, draft.loggerId, draft.origin, draft.destination
        ).getOrElse { return Result.failure(it) }

        return open(draft.id)
    }

    /**
     * Separate from [create], so a mistyped email doesn't lose a shipment the server already holds.
     */
    suspend fun addPeople(shipmentId: String, courierEmail: String, receiverEmail: String): Result<Unit> =
        remote.addMember(shipmentId, courierEmail.trim(), MemberRole.COURIER)
            .mapCatching { remote.addMember(shipmentId, receiverEmail.trim(), MemberRole.RECEIVER).getOrThrow() }

    suspend fun dispatch(shipmentId: String): Result<Shipment> {
        val members = remote.getMembers(shipmentId).getOrElse { return Result.failure(it) }
        if (MemberRole.COURIER !in members.map { it.role } || MemberRole.RECEIVER !in members.map { it.role }) {
            return Result.failure(IllegalStateException("Add a courier and a receiver first."))
        }

        remote.dispatchShipment(shipmentId).getOrElse { return Result.failure(it) }
        return open(shipmentId)
    }

    suspend fun takeCustody(shipmentId: String): Result<Shipment> {
        remote.startCustody(shipmentId, deviceId()).getOrElse { return Result.failure(it) }
        return open(shipmentId)
    }

    suspend fun requestReplacement(shipmentId: String): Result<Shipment> {
        remote.requestReplacement(shipmentId).getOrElse { return Result.failure(it) }
        return open(shipmentId)
    }

    /**
     * Fetches a shipment by its ID, as a scanned code gives it. Nothing is ever built from the code.
     */
    suspend fun open(shipmentId: String): Result<Shipment> {
        val found = remote.getShipment(shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalArgumentException("No shipment with that code, or you aren't on it."))

        val shipment = Shipment(
            id = found.id,
            qrCode = found.id,
            loggerId = found.loggerId,
            origin = found.origin,
            destination = found.destination,
            workerId = found.senderId,
            status = found.status,
            productProfileId = found.productProfileId,
            journeyStartedAt = found.journeyStartedAt,
            genesisHash = found.genesisHash
        )
        return shipments.adoptRemoteShipment(shipment).map { shipment }
    }
}
