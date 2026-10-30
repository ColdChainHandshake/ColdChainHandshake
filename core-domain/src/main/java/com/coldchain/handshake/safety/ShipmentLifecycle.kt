package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus

class IllegalTransitionException(from: ShipmentStatus, to: ShipmentStatus) :
    IllegalStateException("A shipment cannot move from $from to $to.")

/**
 * The legal shipment lifecycle:
 *
 * CREATED -> DISPATCHED -> IN_TRANSIT -> ARRIVED -> INSPECTED -> ACCEPTED
 *
 * Quarantine can be entered from any stage after dispatch and leads only to a replacement request,
 * because a breach can happen at any point on the road. ACCEPTED and REPLACEMENT_REQUESTED are final.
 */
object ShipmentLifecycle {

    private val transitions: Map<ShipmentStatus, Set<ShipmentStatus>> = mapOf(
        ShipmentStatus.CREATED to setOf(ShipmentStatus.DISPATCHED),
        ShipmentStatus.DISPATCHED to setOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.QUARANTINED),
        ShipmentStatus.IN_TRANSIT to setOf(ShipmentStatus.ARRIVED, ShipmentStatus.QUARANTINED),
        ShipmentStatus.ARRIVED to setOf(ShipmentStatus.INSPECTED, ShipmentStatus.QUARANTINED),
        ShipmentStatus.INSPECTED to setOf(ShipmentStatus.ACCEPTED, ShipmentStatus.QUARANTINED),
        ShipmentStatus.QUARANTINED to setOf(ShipmentStatus.REPLACEMENT_REQUESTED)
    )

    fun allowedNext(from: ShipmentStatus): Set<ShipmentStatus> = transitions[from].orEmpty()

    fun canTransition(from: ShipmentStatus, to: ShipmentStatus): Boolean = to in allowedNext(from)

    /**
     * True if [to] is [from] or lies further along the lifecycle. Another device may be several
     * steps ahead of this one, so a status that arrives from elsewhere can skip steps, but it can
     * never move a shipment backwards.
     */
    fun canReach(from: ShipmentStatus, to: ShipmentStatus): Boolean =
        from == to || allowedNext(from).any { canReach(it, to) }

    fun transition(shipment: Shipment, to: ShipmentStatus): Result<Shipment> =
        if (canTransition(shipment.status, to)) {
            Result.success(shipment.copy(status = to))
        } else {
            Result.failure(IllegalTransitionException(shipment.status, to))
        }

    /**
     * The statuses a shipment still has to pass through before its inspection can be recorded.
     * A shipment quarantined on the road needs none: it must still be possible to record what the
     * receiver found.
     */
    fun pathToInspection(from: ShipmentStatus): Result<List<ShipmentStatus>> = when (from) {
        ShipmentStatus.IN_TRANSIT -> Result.success(listOf(ShipmentStatus.ARRIVED, ShipmentStatus.INSPECTED))
        ShipmentStatus.ARRIVED -> Result.success(listOf(ShipmentStatus.INSPECTED))
        ShipmentStatus.INSPECTED,
        ShipmentStatus.QUARANTINED -> Result.success(emptyList())
        else -> Result.failure(IllegalTransitionException(from, ShipmentStatus.INSPECTED))
    }
}
