package com.coldchain.handshake.crypto

/**
 * Reasons why a hash chain integrity verification might fail.
 */
enum class IntegrityFailureReason {
    EMPTY_CHAIN,
    ORDER_VIOLATION,
    INVALID_GENESIS,
    PREVIOUS_HASH_MISMATCH,
    CURRENT_HASH_MISMATCH
}

/**
 * Detailed cryptographic integrity verification result.
 *
 * @param valid Whether the entire chain passed verification.
 * @param corruptedEventId The identifier of the first event that failed verification, if any.
 * @param reason The specific reason for failure, or null if valid.
 * @param message Human-readable diagnostic description of the verification outcome.
 */
data class IntegrityResult(
    val valid: Boolean,
    val corruptedEventId: String? = null,
    val reason: IntegrityFailureReason? = null,
    val message: String = ""
)
