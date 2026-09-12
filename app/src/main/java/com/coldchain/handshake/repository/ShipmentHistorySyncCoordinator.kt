package com.coldchain.handshake.repository

import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Coordinator for shipment history hydration and synchronization.
 *
 * Ensures that remote telemetry from Supabase is hydrated into Room atomically,
 * prevents concurrent hydration races per shipment, and exposes authoritative
 * synchronization state so the UI never displays false tamper while history is syncing.
 */
class ShipmentHistorySyncCoordinator(
    private val telemetryRepository: TelemetryRepository,
    private val remoteDataSource: SupabaseRemoteDataSource
) {
    private val syncStateMap = ConcurrentHashMap<String, MutableStateFlow<HistorySyncState>>()
    private val syncMutexMap = ConcurrentHashMap<String, Mutex>()

    private fun getOrCreateStateFlow(shipmentId: String): MutableStateFlow<HistorySyncState> {
        return syncStateMap.computeIfAbsent(shipmentId) {
            MutableStateFlow(HistorySyncState.IDLE)
        }
    }

    private fun getOrCreateMutex(shipmentId: String): Mutex {
        return syncMutexMap.computeIfAbsent(shipmentId) {
            Mutex()
        }
    }

    fun getSyncState(shipmentId: String): Flow<HistorySyncState> {
        return getOrCreateStateFlow(shipmentId).asStateFlow()
    }

    fun setSyncState(shipmentId: String, state: HistorySyncState) {
        getOrCreateStateFlow(shipmentId).value = state
    }

    fun markReady(shipmentId: String) {
        getOrCreateStateFlow(shipmentId).value = HistorySyncState.HISTORY_READY
    }

    /**
     * Hydrates complete remote history for [shipmentId] from Supabase into local Room.
     *
     * Invariants:
     * - Protected by per-shipment mutex to avoid overlapping hydration calls.
     * - Fetches events ordered chronologically (timestamp ASC, id ASC).
     * - Preserves exact original event IDs, timestamps, previousHash, and currentHash.
     * - Overrides syncStatus to SYNCED so imported events are not re-uploaded by Phone B.
     * - Executes single atomic batch insert into Room.
     * - Sets state to HISTORY_READY on completion.
     */
    suspend fun hydrateHistory(shipmentId: String): Result<Int> {
        if (shipmentId.isBlank()) return Result.success(0)

        val mutex = getOrCreateMutex(shipmentId)
        val stateFlow = getOrCreateStateFlow(shipmentId)

        return mutex.withLock {
            stateFlow.value = HistorySyncState.HISTORY_SYNCING

            val remoteResult = remoteDataSource.getTemperatureEvents(shipmentId)
            if (remoteResult.isFailure) {
                val error = remoteResult.exceptionOrNull() ?: Exception("Remote fetch failed")
                stateFlow.value = HistorySyncState.SYNC_ERROR
                return@withLock Result.failure(error)
            }

            val remoteDtos = remoteResult.getOrNull() ?: emptyList()
            if (remoteDtos.isEmpty()) {
                // If cloud has 0 events, consider ready (e.g. freshly created shipment before first reading)
                stateFlow.value = HistorySyncState.HISTORY_READY
                return@withLock Result.success(0)
            }

            // Map DTOs preserving exact fields, marking as SYNCED
            val domainEvents = remoteDtos.map { dto ->
                TemperatureEvent(
                    id = dto.id,
                    shipmentId = dto.shipmentId,
                    loggerId = dto.loggerId,
                    timestamp = dto.timestamp,
                    temperature = dto.temperature,
                    previousHash = dto.previousHash,
                    currentHash = dto.currentHash,
                    syncStatus = SyncStatus.SYNCED
                )
            }.sortedWith(compareBy({ it.timestamp }, { it.id }))

            // Batch insert atomically into Room
            val saveResult = telemetryRepository.saveTemperatures(domainEvents)
            if (saveResult.isSuccess) {
                stateFlow.value = HistorySyncState.HISTORY_READY
                Result.success(domainEvents.size)
            } else {
                stateFlow.value = HistorySyncState.SYNC_ERROR
                Result.failure(saveResult.exceptionOrNull() ?: Exception("Failed to persist telemetry history in Room"))
            }
        }
    }
}
