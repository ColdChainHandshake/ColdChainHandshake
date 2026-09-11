package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.ShipmentRepository

/**
 * Handles shipment quarantine evaluation and status transitions using existing [ShipmentStatus] values.
 */
object QuarantineManager {

    /**
     * Determines whether a shipment should be quarantined based on thermal breach evaluation.
     */
    fun shouldQuarantine(evaluation: BreachEvaluation): Boolean {
        return evaluation.hasBreach
    }

    /**
     * Applies quarantine status transition if a breach is detected.
     *
     * Rules:
     * - If [BreachEvaluation.hasBreach] is true and shipment is not already [ShipmentStatus.QUARANTINED]
     *   or [ShipmentStatus.REPLACEMENT_REQUESTED], updates status to [ShipmentStatus.QUARANTINED].
     * - Preserves all other [Shipment] properties (id, qrCode, loggerId, origin, destination, workerId).
     * - If no breach is present, leaves the current status intact.
     */
    fun applyQuarantine(shipment: Shipment, evaluation: BreachEvaluation): Shipment {
        if (!evaluation.hasBreach) {
            return shipment
        }

        return when (shipment.status) {
            ShipmentStatus.QUARANTINED,
            ShipmentStatus.REPLACEMENT_REQUESTED -> shipment
            else -> shipment.copy(status = ShipmentStatus.QUARANTINED)
        }
    }

    /**
     * Evaluates quarantine and persists status update via [ShipmentRepository] if status changed.
     * Preserves all other [Shipment] properties and avoids redundant writes if status is unchanged.
     */
    suspend fun quarantineAndPersist(
        shipment: Shipment,
        evaluation: BreachEvaluation,
        repository: ShipmentRepository
    ): Result<Shipment> {
        val updated = applyQuarantine(shipment, evaluation)
        return if (updated.status != shipment.status) {
            repository.saveShipment(updated).map { updated }
        } else {
            Result.success(updated)
        }
    }
}
