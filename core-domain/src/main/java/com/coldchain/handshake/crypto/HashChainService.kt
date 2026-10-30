package com.coldchain.handshake.crypto

import com.coldchain.handshake.models.TemperatureEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The fields a shipment's chain is committed to. The server computes the genesis hash from these when
 * the shipment is created, and they can't change afterwards.
 */
data class ShipmentManifest(
    val shipmentId: String,
    val senderId: String,
    val productProfileId: String,
    val loggerId: String,
    val createdAtMs: Long
)

/**
 * The chain, version 2. Bytes are defined in docs/protocol/CANONICAL_ENCODING.md and shared with the
 * SQL and the logger firmware through docs/protocol/chain_vectors.json.
 *
 * On its own the chain shows that a history hangs together, not that it is complete or true: anyone
 * holding the data can recompute every hash. Tail deletion and rewriting are caught by comparing
 * against the head and count the server holds, in [ShipmentIntegrityEvaluator].
 */
object HashChainService {

    const val CHAIN_VERSION = 2

    /**
     * Stands in for a genesis hash on a shipment with no server record. A chain built on it can only
     * be checked for internal consistency.
     */
    const val UNBOUND_GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun genesisHash(manifest: ShipmentManifest): String = sha256 {
        version()
        text(manifest.shipmentId)
        text(manifest.senderId)
        text(manifest.productProfileId)
        text(manifest.loggerId)
        int64(manifest.createdAtMs)
    }

    fun readingHash(
        shipmentId: String,
        loggerId: String,
        seq: Long,
        loggerTs: Long,
        tempCentiC: Int,
        previousHash: String
    ): String = sha256 {
        version()
        text(shipmentId)
        text(loggerId)
        int64(seq)
        int64(loggerTs)
        int32(tempCentiC)
        hash(previousHash)
    }

    fun createHash(event: TemperatureEvent, previousHash: String): String = readingHash(
        shipmentId = event.shipmentId,
        loggerId = event.loggerId,
        seq = event.seq,
        loggerTs = event.timestamp,
        tempCentiC = event.tempCentiC,
        previousHash = previousHash
    )

    /**
     * Appends previousHash and computes currentHash for a TemperatureEvent, returning an updated copy.
     */
    fun appendHash(event: TemperatureEvent, previousHash: String): TemperatureEvent {
        val hash = createHash(event, previousHash)
        return event.copy(previousHash = previousHash, currentHash = hash)
    }

    /**
     * Constructs a chain from raw events, numbering them from 1 in the order given.
     */
    fun buildChain(rawEvents: List<TemperatureEvent>, genesisHash: String = UNBOUND_GENESIS): List<TemperatureEvent> {
        val chain = mutableListOf<TemperatureEvent>()
        var prev = genesisHash
        rawEvents.forEachIndexed { index, event ->
            val hashed = appendHash(event.copy(seq = index + 1L), prev)
            chain.add(hashed)
            prev = hashed.currentHash
        }
        return chain
    }

    /**
     * Verifies that a sequence of temperature events is one unbroken chain from [genesisHash].
     *
     * Verification rules:
     * 1. Chain must not be empty (EMPTY_CHAIN).
     * 2. Events must be in chronological sequence (ORDER_VIOLATION).
     * 3. First event previousHash must equal [genesisHash] (INVALID_GENESIS).
     * 4. Sequence numbers must run from 1 with no gaps (SEQUENCE_GAP).
     * 5. Each subsequent event.previousHash must match the preceding event.currentHash (PREVIOUS_HASH_MISMATCH).
     * 6. Recomputed hash must match event.currentHash (CURRENT_HASH_MISMATCH).
     *
     * @return [IntegrityResult] with detailed outcome, corrupted event ID, and failure reason.
     */
    fun verifyChain(events: List<TemperatureEvent>, genesisHash: String = UNBOUND_GENESIS): IntegrityResult {
        if (events.isEmpty()) {
            return IntegrityResult(
                valid = false,
                reason = IntegrityFailureReason.EMPTY_CHAIN,
                message = "No readings to verify."
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
        if (firstEvent.previousHash != genesisHash) {
            return IntegrityResult(
                valid = false,
                corruptedEventId = firstEvent.id,
                reason = IntegrityFailureReason.INVALID_GENESIS,
                message = "First event ${firstEvent.id} does not start from this shipment's genesis."
            )
        }

        // 3. Continuity and digest verification
        for (i in events.indices) {
            val current = events[i]

            if (current.seq != i + 1L) {
                return IntegrityResult(
                    valid = false,
                    corruptedEventId = current.id,
                    reason = IntegrityFailureReason.SEQUENCE_GAP,
                    message = "Expected reading ${i + 1} but found ${current.seq}."
                )
            }

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

            // Check recomputed hash match
            val recomputedHash = createHash(current, current.previousHash)
            if (recomputedHash != current.currentHash) {
                return IntegrityResult(
                    valid = false,
                    corruptedEventId = current.id,
                    reason = IntegrityFailureReason.CURRENT_HASH_MISMATCH,
                    message = "Reading ${current.id} does not match its hash."
                )
            }
        }

        return IntegrityResult(valid = true, message = "${events.size} readings form one unbroken chain.")
    }

    /**
     * Identifies and returns the first corrupted TemperatureEvent in the chain, or null if valid.
     */
    fun findCorruptedEvent(events: List<TemperatureEvent>, genesisHash: String = UNBOUND_GENESIS): TemperatureEvent? {
        val result = verifyChain(events, genesisHash)
        if (result.valid || result.corruptedEventId == null) {
            return null
        }
        return events.firstOrNull { it.id == result.corruptedEventId }
    }

    private fun sha256(write: CanonicalWriter.() -> Unit): String {
        val bytes = CanonicalWriter().apply(write).toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private class CanonicalWriter {
        private val out = ByteArrayOutputStream()

        fun version() = out.write(CHAIN_VERSION)

        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            int32(bytes.size)
            out.write(bytes)
        }

        fun int32(value: Int) = out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())

        fun int64(value: Long) = out.write(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())

        fun hash(hex: String) {
            require(hex.length == HASH_HEX_LENGTH) { "A hash is $HASH_HEX_LENGTH hex characters." }
            out.write(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private const val HASH_HEX_LENGTH = 64
}
