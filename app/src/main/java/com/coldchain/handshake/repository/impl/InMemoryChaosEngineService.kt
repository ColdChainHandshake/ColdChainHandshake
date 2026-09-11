package com.coldchain.handshake.repository.impl

import com.coldchain.handshake.models.ChaosEvent
import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.repository.ChaosEngineService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Thread-safe in-memory implementation of [ChaosEngineService].
 * Allows injecting and observing fault scenarios like HEAT_SPIKE.
 */
class InMemoryChaosEngineService : ChaosEngineService {

    private val activeScenariosFlow = MutableStateFlow<Map<ChaosScenarioType, ChaosEvent>>(emptyMap())

    override fun observeActiveScenarios(): Flow<List<ChaosEvent>> {
        return activeScenariosFlow.map { it.values.toList() }
    }

    override suspend fun injectScenario(scenarioType: ChaosScenarioType): Result<ChaosEvent> {
        return try {
            val description = when (scenarioType) {
                ChaosScenarioType.HEAT_SPIKE -> "Heat Spike excursion active (9°C–11°C)"
                ChaosScenarioType.LOGGER_DISCONNECT -> "Logger disconnected from telemetry stream"
                ChaosScenarioType.NETWORK_FAILURE -> "Network failure active; local buffering engaged"
                ChaosScenarioType.CORRUPT_EVENT -> "Corrupted event payload injected into hash verification"
            }
            val event = ChaosEvent(
                id = UUID.randomUUID().toString(),
                scenarioType = scenarioType,
                isActive = true,
                triggeredAtTimestamp = System.currentTimeMillis(),
                description = description
            )
            activeScenariosFlow.value = activeScenariosFlow.value + (scenarioType to event)
            Result.success(event)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetScenario(scenarioType: ChaosScenarioType): Result<Unit> {
        return try {
            activeScenariosFlow.value = activeScenariosFlow.value - scenarioType
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetAllScenarios(): Result<Unit> {
        return try {
            activeScenariosFlow.value = emptyMap()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
