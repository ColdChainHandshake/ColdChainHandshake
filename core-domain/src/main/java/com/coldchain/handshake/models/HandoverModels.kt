package com.coldchain.handshake.models

/**
 * Official handover inspection verdict, from least to most severe.
 */
enum class HandoverVerdict {
    PASS,
    UNKNOWN,
    FAIL
}

/**
 * Stage of the vaccine vial monitor, the heat-sensitive label on a vial. Stages 1 and 2 are usable.
 * Stage 3 is the discard point and stage 4 is past it. It shows heat exposure only, not freezing.
 */
enum class VvmStage {
    NOT_RECORDED,
    STAGE_1,
    STAGE_2,
    STAGE_3,
    STAGE_4
}

/**
 * Result of the shake test, which shows whether a vaccine has been damaged by freezing.
 */
enum class ShakeTestResult {
    NOT_DONE,
    PASSED,
    FAILED
}

/**
 * Official Handover custody verification record.
 *
 * @property reason Why the verdict is not PASS. Always present on a FAIL or UNKNOWN.
 */
data class Handover(
    val id: String,
    val shipmentId: String,
    val workerSigned: Boolean,
    val pharmacistSigned: Boolean,
    val integrityVerified: Boolean,
    val temperaturePassed: Boolean,
    val verdict: HandoverVerdict,
    val timestamp: Long,
    val reason: String? = null,
    val vvmStage: VvmStage = VvmStage.NOT_RECORDED,
    val shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE
)
