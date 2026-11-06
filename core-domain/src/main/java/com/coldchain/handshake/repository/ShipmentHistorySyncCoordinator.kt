package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.ServerChainHead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class HistorySyncState {
    IDLE,
    HISTORY_LOADING,
    HISTORY_SYNCING,
    HISTORY_READY,
    SYNC_ERROR
}

/**
 * Brings a shipment's readings down from the server, one fetch per shipment at a time, and remembers
 * what the server said its head was. That's what lets the UI tell a history still downloading from one
 * that's been cut short.
 */
class ShipmentHistorySyncCoordinator(
    private val telemetryRepository: TelemetryRepository,
    private val remote: ColdChainRemote
) {
    private val syncStates = ConcurrentHashMap<String, MutableStateFlow<HistorySyncState>>()
    private val serverHeads = ConcurrentHashMap<String, MutableStateFlow<ServerChainHead?>>()
    private val syncMutexes = ConcurrentHashMap<String, Mutex>()

    private fun stateFor(shipmentId: String) =
        syncStates.computeIfAbsent(shipmentId) { MutableStateFlow(HistorySyncState.IDLE) }

    private fun headFor(shipmentId: String) =
        serverHeads.computeIfAbsent(shipmentId) { MutableStateFlow(null) }

    fun getSyncState(shipmentId: String): Flow<HistorySyncState> = stateFor(shipmentId).asStateFlow()

    fun getServerHead(shipmentId: String): StateFlow<ServerChainHead?> = headFor(shipmentId).asStateFlow()

    fun setSyncState(shipmentId: String, state: HistorySyncState) {
        stateFor(shipmentId).value = state
    }

    fun markReady(shipmentId: String) {
        stateFor(shipmentId).value = HistorySyncState.HISTORY_READY
    }

    /**
     * Fetches whatever this device doesn't hold. A reading it holds already is never replaced: if that
     * copy differs from the server's, comparing with the server's head has to show it.
     */
    suspend fun hydrateHistory(shipmentId: String): Result<Int> {
        if (shipmentId.isBlank()) return Result.success(0)

        val state = stateFor(shipmentId)

        return syncMutexes.computeIfAbsent(shipmentId) { Mutex() }.withLock {
            state.value = HistorySyncState.HISTORY_SYNCING

            val head = remote.getChainHead(shipmentId).getOrElse {
                state.value = HistorySyncState.SYNC_ERROR
                return@withLock Result.failure(it)
            }

            val held = telemetryRepository.getTemperatures(shipmentId).firstOrNull().orEmpty()
                .map { it.seq }.toSet()
            val firstMissing = generateSequence(1L) { it + 1 }.first { it !in held }

            val missing = remote.getReadingsAfter(shipmentId, firstMissing - 1)
                .getOrElse {
                    state.value = HistorySyncState.SYNC_ERROR
                    return@withLock Result.failure(it)
                }
                .filter { it.seq !in held }

            telemetryRepository.saveTemperatures(missing).getOrElse {
                state.value = HistorySyncState.SYNC_ERROR
                return@withLock Result.failure(it)
            }

            headFor(shipmentId).value = head
            state.value = HistorySyncState.HISTORY_READY
            Result.success(missing.size)
        }
    }
}
