package com.coldchain.handshake.simulator

import com.coldchain.handshake.logger.VirtualLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug local mode's loggers (4.1, MASTER 5.4 item 2): one virtual logger for each shipment kept on this phone alone,
 * recording what the simulator measures, so on one phone playing every part the Handover screen reads a logger and
 * checks the readings against it, as a receiver would read a real one.
 *
 * A virtual logger's key lives in this app, so it proves nothing about temperature (THREAT_MODEL); it exists so the
 * check runs end to end. It is never enrolled on a server: a server shipment's readings come from the courier's phone,
 * where the receiver can't reach it (DECISIONS, 28 Nov). Kept in memory: after a restart a local shipment has no
 * logger here, and its handover says the logger wasn't checked.
 *
 * The key the check trusts is the one taken when the logger started, kept apart from the logger, as a server shipment
 * keeps the key enrolled at creation: never one the logger supplies at handover.
 */
@Singleton
class LocalLoggers @Inject constructor() {

    private val loggers = ConcurrentHashMap<String, VirtualLogger>()
    private val enrolled = ConcurrentHashMap<String, ByteArray>()

    /** The shipment's logger, started now if it has none: called with its first reading. */
    fun startFor(shipmentId: String, loggerId: String): VirtualLogger =
        loggers.computeIfAbsent(shipmentId) { id ->
            VirtualLogger(loggerId).also { enrolled[id] = it.publicKeyUncompressed.copyOf() }
        }

    fun of(shipmentId: String): VirtualLogger? = loggers[shipmentId]

    /** The key on the local shipment's record, taken when its logger started. */
    fun enrolledKey(shipmentId: String): ByteArray? = enrolled[shipmentId]?.copyOf()
}
