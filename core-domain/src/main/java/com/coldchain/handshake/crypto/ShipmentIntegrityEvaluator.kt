package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.TemperatureEvent

/**
 * State representing the verification status of a shipment's telemetry chain.
 *
 * Strictly distinguishes between INCOMPLETE / STILL-SYNCING HISTORY and ACTUAL HASH CORRUPTION.
 */
enum class IntegrityVerificationState {
    /**
     * History is empty or still actively synchronizing, so verification is pending.
     */
    PENDING_SYNC,

    /**
     * Full chain rooted from the shipment's genesis is present and valid.
     */
    VERIFIED,

    /**
     * History has finished arriving and is broken, shorter than the server's, or different from it.
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

/**
 * What the server holds for a shipment's chain.
 */
data class ServerChainHead(
    val headHash: String,
    val count: Long
)

object ShipmentIntegrityEvaluator {

    /**
     * Evaluates a shipment's readings with awareness of whether they have finished arriving.
     *
     * A short history means different things at different times: while a download is running it is
     * just unfinished, and once the download has finished it means readings are missing.
     *
     * @param events Chronological temperature events currently present for the shipment.
     * @param isSyncComplete True only if history hydration from cloud has finished and device holds complete history.
     * @param genesisHash Where this shipment's chain must start.
     * @param serverHead What the server holds. This is what catches a removed tail or a rewritten
     *   history, because such a chain is consistent with itself.
     */
    fun evaluate(
        events: List<TemperatureEvent>,
        isSyncComplete: Boolean = true,
        genesisHash: String = HashChainService.UNBOUND_GENESIS,
        serverHead: ServerChainHead? = null
    ): IntegrityEvaluation {
        if (!isSyncComplete) {
            return pending(
                title = "HISTORY SYNCING",
                message = "Waiting for complete telemetry history before integrity verification…"
            )
        }

        if (events.isEmpty()) {
            if (serverHead != null && serverHead.count > 0) {
                return tampered(null, "The server holds ${serverHead.count} readings and this device has none.")
            }
            return pending(
                title = "INTEGRITY VERIFICATION PENDING",
                message = "Waiting for telemetry history before integrity verification…"
            )
        }

        val result = HashChainService.verifyChain(events, genesisHash)
        if (!result.valid) {
            return tampered(result, "${result.reason?.name ?: "CORRUPTION"}: ${result.corruptedEventId ?: result.message}")
        }

        if (serverHead != null) {
            if (events.size < serverHead.count) {
                return tampered(
                    result,
                    "This device has ${events.size} readings but the server holds ${serverHead.count}."
                )
            }
            val matches = if (serverHead.count == 0L) {
                serverHead.headHash == genesisHash
            } else {
                events[(serverHead.count - 1).toInt()].currentHash == serverHead.headHash
            }
            if (!matches) {
                return tampered(result, "These readings differ from the ones the server holds.")
            }
        }

        return IntegrityEvaluation(
            state = IntegrityVerificationState.VERIFIED,
            integrityResult = result,
            title = "✓ READINGS VERIFIED",
            message = if (serverHead == null) {
                "${events.size} readings form one unbroken chain. Not yet compared with the server."
            } else {
                "${events.size} readings form one unbroken chain and match the server."
            },
            isEligibleForHandover = true
        )
    }

    private fun pending(title: String, message: String) = IntegrityEvaluation(
        state = IntegrityVerificationState.PENDING_SYNC,
        integrityResult = null,
        title = title,
        message = message,
        isEligibleForHandover = false
    )

    private fun tampered(result: IntegrityResult?, message: String) = IntegrityEvaluation(
        state = IntegrityVerificationState.TAMPER_DETECTED,
        integrityResult = result,
        title = "✗ TAMPER DETECTED",
        message = message,
        isEligibleForHandover = false
    )
}
