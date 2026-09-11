package com.coldchain.handshake.handover

import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShipmentStatus

/**
 * Specific reasons why a custody handover evaluation may fail.
 */
enum class HandoverFailureReason {
    TEMPERATURE_FAILED,
    INTEGRITY_FAILED,
    WORKER_SIGNATURE_MISSING,
    PHARMACIST_SIGNATURE_MISSING
}

/**
 * Transparent domain evaluation result for consignment custody inspection.
 *
 * @param verdict Authoritative custody verdict: [HandoverVerdict.PASS] or [HandoverVerdict.FAIL].
 * @param targetStatus Resulting shipment lifecycle status: [ShipmentStatus.ACCEPTED] or [ShipmentStatus.QUARANTINED].
 * @param failureReasons Complete list of all failed conditions (empty if passed).
 */
data class HandoverEvaluation(
    val verdict: HandoverVerdict,
    val targetStatus: ShipmentStatus,
    val failureReasons: List<HandoverFailureReason>
)

/**
 * Pure, authoritative Handover Verdict Engine.
 *
 * Strictly consumes already-computed inputs:
 * - [temperaturePassed]: Result of temperature excursion evaluation (owned by Person 3).
 * - [integrityPassed]: Result of cryptographic SHA-256 hash-chain verification (owned by Person 4 Phase 1).
 * - [workerSigned]: Courier / logistics worker digital confirmation.
 * - [pharmacistSigned]: Receiving pharmacist digital confirmation.
 *
 * Rules:
 * - ALL FOUR conditions must be true to yield [HandoverVerdict.PASS] and [ShipmentStatus.ACCEPTED].
 * - ANY false condition yields [HandoverVerdict.FAIL] and [ShipmentStatus.QUARANTINED].
 * - Returns ALL applicable failure reasons without stopping at the first failure.
 */
object HandoverVerdictEngine {

    fun evaluate(
        temperaturePassed: Boolean,
        integrityPassed: Boolean,
        workerSigned: Boolean,
        pharmacistSigned: Boolean
    ): HandoverEvaluation {
        val failureReasons = mutableListOf<HandoverFailureReason>()

        if (!temperaturePassed) {
            failureReasons.add(HandoverFailureReason.TEMPERATURE_FAILED)
        }
        if (!integrityPassed) {
            failureReasons.add(HandoverFailureReason.INTEGRITY_FAILED)
        }
        if (!workerSigned) {
            failureReasons.add(HandoverFailureReason.WORKER_SIGNATURE_MISSING)
        }
        if (!pharmacistSigned) {
            failureReasons.add(HandoverFailureReason.PHARMACIST_SIGNATURE_MISSING)
        }

        val allPassed = failureReasons.isEmpty()

        return HandoverEvaluation(
            verdict = if (allPassed) HandoverVerdict.PASS else HandoverVerdict.FAIL,
            targetStatus = if (allPassed) ShipmentStatus.ACCEPTED else ShipmentStatus.QUARANTINED,
            failureReasons = failureReasons
        )
    }
}
