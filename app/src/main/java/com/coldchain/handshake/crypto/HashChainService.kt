package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.TemperatureEvent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Authoritative cryptographic SHA-256 hash-chain service.
 *
 * Implements tamper-evident temperature telemetry chaining where each event is cryptographically
 * bound to all preceding events via:
 *   currentHash = SHA-256(canonicalEventData + previousHash)
 */
object HashChainService {

    /**
     * Fixed genesis constant for the first block in the chain.
     */
    const val GENESIS_HASH = "GENESIS"

    /**
     * Computes the deterministic canonical string representation for a TemperatureEvent.
     *
     * Format: "$id|$shipmentId|$loggerId|$timestamp|${temperature formatted to 4 decimal places in Locale.US}"
     *
     * Note: PreviousHash, currentHash, and syncStatus are intentionally excluded because
     * previousHash is passed explicitly as the chain link, currentHash is the output of this hashing,
     * and syncStatus represents transport state rather than payload data.
     */
    fun canonicalEventData(event: TemperatureEvent): String {
        val formattedTemp = String.format(Locale.US, "%.4f", event.temperature)
        return "${event.id}|${event.shipmentId}|${event.loggerId}|${event.timestamp}|$formattedTemp"
    }

    /**
     * Computes the SHA-256 hex digest of (canonicalData + previousHash).
     */
    fun createHash(canonicalData: String, previousHash: String): String {
        val payload = canonicalData + previousHash
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(payload.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the SHA-256 hash for a TemperatureEvent given its previousHash.
     */
    fun createHash(event: TemperatureEvent, previousHash: String): String {
        return createHash(canonicalEventData(event), previousHash)
    }

    /**
     * Appends previousHash and computes currentHash for a TemperatureEvent, returning an updated copy.
     */
    fun appendHash(event: TemperatureEvent, previousHash: String): TemperatureEvent {
        val hash = createHash(event, previousHash)
        return event.copy(previousHash = previousHash, currentHash = hash)
    }

    /**
     * Constructs a valid SHA-256 chain from a raw list of events starting from genesis.
     */
    fun buildChain(rawEvents: List<TemperatureEvent>, genesisHash: String = GENESIS_HASH): List<TemperatureEvent> {
        val chain = mutableListOf<TemperatureEvent>()
        var prev = genesisHash
        for (event in rawEvents) {
            val hashed = appendHash(event, prev)
            chain.add(hashed)
            prev = hashed.currentHash
        }
        return chain
    }

    /**
     * Cryptographically verifies the integrity of an entire sequence of temperature events.
     *
     * Verification rules:
     * 1. Chain must not be empty (EMPTY_CHAIN).
     * 2. Events must be in chronological sequence (ORDER_VIOLATION).
     * 3. First event previousHash must equal GENESIS_HASH (INVALID_GENESIS).
     * 4. Each subsequent event.previousHash must match the preceding event.currentHash (PREVIOUS_HASH_MISMATCH).
     * 5. Recomputed SHA-256 hash must match event.currentHash (CURRENT_HASH_MISMATCH).
     *
     * @return [IntegrityResult] with detailed outcome, corrupted event ID, and failure reason.
     */
    fun verifyChain(events: List<TemperatureEvent>): IntegrityResult {
        if (events.isEmpty()) {
            return IntegrityResult(
                valid = false,
                reason = IntegrityFailureReason.EMPTY_CHAIN,
                message = "Hash chain is empty."
            )
        }

        // 1. Chronological order check
        for (i in 1 until events.size) {
            if (events[i].timestamp < events[i - 1].timestamp) {
                return IntegrityResult(
                    valid = false,
                    corruptedEventId = events[i].id,
                    reason = IntegrityFailureReason.ORDER_VIOLATION,
                    message = "Event ${events[i].id} timestamp (${events[i].timestamp}) violates chronological sequence after ${events[i - 1].id} (${events[i - 1].timestamp})."
                )
            }
        }

        // 2. Genesis hash check on the first event
        val firstEvent = events[0]
        if (firstEvent.previousHash != GENESIS_HASH) {
            return IntegrityResult(
                valid = false,
                corruptedEventId = firstEvent.id,
                reason = IntegrityFailureReason.INVALID_GENESIS,
                message = "First event ${firstEvent.id} has invalid genesis hash: expected '$GENESIS_HASH', found '${firstEvent.previousHash}'."
            )
        }

        // 3. Continuity and cryptographic digest verification
        for (i in events.indices) {
            val current = events[i]

            // Check previousHash link continuity
            if (i > 0) {
                val previous = events[i - 1]
                if (current.previousHash != previous.currentHash) {
                    return IntegrityResult(
                        valid = false,
                        corruptedEventId = current.id,
                        reason = IntegrityFailureReason.PREVIOUS_HASH_MISMATCH,
                        message = "Chain broken at event ${current.id}: previousHash does not match event ${previous.id} currentHash."
                    )
                }
            }

            // Check recomputed SHA-256 hash match
            val recomputedHash = createHash(current, current.previousHash)
            if (recomputedHash != current.currentHash) {
                return IntegrityResult(
                    valid = false,
                    corruptedEventId = current.id,
                    reason = IntegrityFailureReason.CURRENT_HASH_MISMATCH,
                    message = "Cryptographic integrity failure at event ${current.id}: recomputed hash ($recomputedHash) does not match stored currentHash (${current.currentHash})."
                )
            }
        }

        return IntegrityResult(
            valid = true,
            message = "SHA-256 hash chain verified successfully (${events.size} telemetry blocks verified)."
        )
    }

    /**
     * Identifies and returns the first corrupted TemperatureEvent in the chain, or null if valid.
     */
    fun findCorruptedEvent(events: List<TemperatureEvent>): TemperatureEvent? {
        val result = verifyChain(events)
        if (result.valid || result.corruptedEventId == null) {
            return null
        }
        return events.firstOrNull { it.id == result.corruptedEventId }
    }
}
