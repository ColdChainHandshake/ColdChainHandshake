package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.TemperatureEvent

/**
 * State representing the verification status of a shipment's telemetry chain.
 *
 * Strictly distinguishes between INCOMPLETE / STILL-SYNCING HISTORY and ACTUAL HASH CORRUPTION.
 */
enum class IntegrityVerificationState {
    /**
     * History is empty, still actively synchronizing, or represents a partial suffix missing genesis.
     * Verification is pending; an incomplete chain is NEVER treated as tampered.
     */
    PENDING_SYNC,

    /**
     * Full chain rooted from Genesis is present and cryptographically valid.
     */
    VERIFIED,

    /**
     * Complete chain is present and cryptographically verified, but contains actual corruption
     * (e.g. CURRENT_HASH_MISMATCH, PREVIOUS_HASH_MISMATCH, ORDER_VIOLATION).
     */
    TAMPER_DETECTED
}

data class IntegrityEvaluation(
    val state: IntegrityVerificationState,
    val integrityResult: IntegrityResult?,
    val title: String,
    val message: String,
    val isEligibleForHandover: Boolean
)

object ShipmentIntegrityEvaluator {

    /**
     * Evaluates the cryptographic integrity of [events] with awareness of history synchronization state.
     *
     * @param events Chronological temperature events currently present for the shipment.
     * @param isSyncComplete True only if history hydration from cloud has finished and device holds complete chain.
     */
    fun evaluate(
        events: List<TemperatureEvent>,
        isSyncComplete: Boolean = true
    ): IntegrityEvaluation {
        // 1. If history is empty, verification is pending arrival of records
        if (events.isEmpty()) {
            return IntegrityEvaluation(
                state = IntegrityVerificationState.PENDING_SYNC,
                integrityResult = null,
                title = "INTEGRITY VERIFICATION PENDING",
                message = "Waiting for telemetry history before integrity verification…",
                isEligibleForHandover = false
            )
        }

        // 2. If the first event does not have GENESIS as previousHash, this is a partial suffix!
        // Example: Phone B has E3 -> E4, but E1 -> E2 have not arrived yet.
        // An incomplete chain must NEVER be reported as TAMPER DETECTED.
        val firstEvent = events[0]
        if (firstEvent.previousHash != HashChainService.GENESIS_HASH) {
            return IntegrityEvaluation(
                state = IntegrityVerificationState.PENDING_SYNC,
                integrityResult = null,
                title = "INTEGRITY VERIFICATION PENDING",
                message = "Receiving complete shipment history from cloud (genesis block pending)…",
                isEligibleForHandover = false
            )
        }

        // 3. If history is still actively synchronizing from cloud, mark as PENDING_SYNC
        if (!isSyncComplete) {
            return IntegrityEvaluation(
                state = IntegrityVerificationState.PENDING_SYNC,
                integrityResult = null,
                title = "HISTORY SYNCING",
                message = "Waiting for complete telemetry history before integrity verification…",
                isEligibleForHandover = false
            )
        }

        // 4. Complete history rooted at Genesis is present: execute authoritative cryptographic verification
        val result = HashChainService.verifyChain(events)
        return if (result.valid) {
            IntegrityEvaluation(
                state = IntegrityVerificationState.VERIFIED,
                integrityResult = result,
                title = "✓ HASH CHAIN VERIFIED",
                message = "${events.size} immutable SHA-256 telemetry blocks verified",
                isEligibleForHandover = true
            )
        } else {
            IntegrityEvaluation(
                state = IntegrityVerificationState.TAMPER_DETECTED,
                integrityResult = result,
                title = "✗ TAMPER DETECTED",
                message = "${result.reason?.name ?: "CORRUPTION"}: ${result.corruptedEventId ?: result.message}",
                isEligibleForHandover = false
            )
        }
    }
}
