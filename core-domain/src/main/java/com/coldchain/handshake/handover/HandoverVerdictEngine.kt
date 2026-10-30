package com.coldchain.handshake.handover

import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.safety.TemperatureVerdict

/**
 * Specific reasons why a custody handover evaluation may not pass.
 */
enum class HandoverFailureReason(val description: String) {
    TEMPERATURE_FAILED("Temperature went out of range"),
    TEMPERATURE_UNKNOWN("The readings have a gap, so the temperature during that time is unknown"),
    INTEGRITY_FAILED("The reading history failed verification"),
    WORKER_SIGNATURE_MISSING("The courier has not confirmed"),
    PHARMACIST_SIGNATURE_MISSING("The receiver has not confirmed"),
    VVM_PAST_DISCARD_POINT("The vial monitor has reached its discard point"),
    SHAKE_TEST_FAILED("The shake test shows freeze damage")
}

/**
 * Transparent domain evaluation result for consignment custody inspection.
 *
 * @param verdict Authoritative custody verdict.
 * @param targetStatus Resulting shipment lifecycle status: [ShipmentStatus.ACCEPTED] only on a pass.
 *   Anything else is held in [ShipmentStatus.QUARANTINED], including an unknown result.
 * @param failureReasons Complete list of all failed conditions (empty if passed).
 */
data class HandoverEvaluation(
    val verdict: HandoverVerdict,
    val targetStatus: ShipmentStatus,
    val failureReasons: List<HandoverFailureReason>
)

/**
 * The verdict and reason that go on the record once the receiver's own finding is applied.
 */
data class HandoverDecision(
    val verdict: HandoverVerdict,
    val targetStatus: ShipmentStatus,
    val reason: String?
)

/**
 * Pure, authoritative Handover Verdict Engine.
 *
 * Rules:
 * - Any failed check yields [HandoverVerdict.FAIL], and a temperature gap with nothing else wrong
 *   yields [HandoverVerdict.UNKNOWN]. Only a clean result yields [HandoverVerdict.PASS].
 * - The vial monitor and the shake test are independent of the logger, and either can fail a
 *   handover on its own. Leaving them unrecorded does not.
 * - Returns ALL applicable failure reasons without stopping at the first failure.
 */
object HandoverVerdictEngine {

    fun evaluate(
        temperature: TemperatureVerdict,
        integrityPassed: Boolean,
        workerSigned: Boolean,
        pharmacistSigned: Boolean,
        vvmStage: VvmStage = VvmStage.NOT_RECORDED,
        shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE
    ): HandoverEvaluation {
        val failureReasons = mutableListOf<HandoverFailureReason>()

        if (temperature == TemperatureVerdict.FAIL) {
            failureReasons.add(HandoverFailureReason.TEMPERATURE_FAILED)
        }
        if (temperature == TemperatureVerdict.UNKNOWN) {
            failureReasons.add(HandoverFailureReason.TEMPERATURE_UNKNOWN)
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
        if (vvmStage == VvmStage.STAGE_3 || vvmStage == VvmStage.STAGE_4) {
            failureReasons.add(HandoverFailureReason.VVM_PAST_DISCARD_POINT)
        }
        if (shakeTest == ShakeTestResult.FAILED) {
            failureReasons.add(HandoverFailureReason.SHAKE_TEST_FAILED)
        }

        val verdict = when {
            failureReasons.isEmpty() -> HandoverVerdict.PASS
            failureReasons.all { it == HandoverFailureReason.TEMPERATURE_UNKNOWN } -> HandoverVerdict.UNKNOWN
            else -> HandoverVerdict.FAIL
        }

        return HandoverEvaluation(
            verdict = verdict,
            targetStatus = targetStatusFor(verdict),
            failureReasons = failureReasons
        )
    }

    /**
     * Applies what the receiver declared on top of the computed result. The receiver can make a
     * verdict stricter, because they can see things the logger can't, but never more lenient.
     * Anything other than a pass must carry a reason, which the computed failures supply unless the
     * receiver gives their own.
     *
     * @param declared The receiver's verdict, or null to accept the computed one.
     * @param reason The receiver's explanation, if any.
     */
    fun decide(
        evaluation: HandoverEvaluation,
        declared: HandoverVerdict?,
        reason: String?
    ): Result<HandoverDecision> {
        val verdict = declared ?: evaluation.verdict
        if (verdict < evaluation.verdict) {
            return Result.failure(
                IllegalArgumentException("Cannot record $verdict: the checks found ${evaluation.verdict}.")
            )
        }

        val givenReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        val computedReason = evaluation.failureReasons.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { it.description }
        val recordedReason = givenReason ?: computedReason

        if (verdict != HandoverVerdict.PASS && recordedReason == null) {
            return Result.failure(IllegalArgumentException("A $verdict handover needs a reason."))
        }

        return Result.success(
            HandoverDecision(
                verdict = verdict,
                targetStatus = targetStatusFor(verdict),
                reason = recordedReason
            )
        )
    }

    private fun targetStatusFor(verdict: HandoverVerdict): ShipmentStatus =
        if (verdict == HandoverVerdict.PASS) ShipmentStatus.ACCEPTED else ShipmentStatus.QUARANTINED
}
