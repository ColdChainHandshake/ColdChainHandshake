package com.coldchain.handshake.repository

import com.coldchain.handshake.models.ChaosEvent
import com.coldchain.handshake.models.ChaosScenarioType
import kotlinx.coroutines.flow.Flow

/**
 * Shared service contract for injecting chaos scenarios into the test lab.
 */
interface ChaosEngineService {
    fun observeActiveScenarios(): Flow<List<ChaosEvent>>
    suspend fun injectScenario(scenarioType: ChaosScenarioType): Result<ChaosEvent>
    suspend fun resetScenario(scenarioType: ChaosScenarioType): Result<Unit>
    suspend fun resetAllScenarios(): Result<Unit>
}
