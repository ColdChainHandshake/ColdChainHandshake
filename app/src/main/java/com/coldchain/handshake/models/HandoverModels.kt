package com.coldchain.handshake.models

/**
 * Official handover inspection verdict.
 */
enum class HandoverVerdict {
    PASS,
    FAIL
}

/**
 * Official Handover custody verification record.
 */
data class Handover(
    val id: String,
    val shipmentId: String,
    val workerSigned: Boolean,
    val pharmacistSigned: Boolean,
    val integrityVerified: Boolean,
    val temperaturePassed: Boolean,
    val verdict: HandoverVerdict,
    val timestamp: Long
)
