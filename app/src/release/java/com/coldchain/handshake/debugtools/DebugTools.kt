package com.coldchain.handshake.debugtools

import androidx.compose.runtime.Composable
import com.coldchain.handshake.data.network.NetworkMonitor
import com.coldchain.handshake.models.ChaosEvent
import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.repository.ChaosEngineService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Release counterpart of the debug-only tools: nothing can be injected or tampered with.
 */
object DebugTools {

    const val ENABLED = false

    fun createChaosService(): ChaosEngineService = NoChaosService

    fun wrapNetworkMonitor(monitor: NetworkMonitor, chaosService: ChaosEngineService): NetworkMonitor = monitor

    @Composable
    fun ChaosLab() = Unit
}

private object NoChaosService : ChaosEngineService {

    override fun observeActiveScenarios(): Flow<List<ChaosEvent>> = flowOf(emptyList())

    override suspend fun injectScenario(scenarioType: ChaosScenarioType): Result<ChaosEvent> =
        Result.failure(UnsupportedOperationException("Fault injection is not available in release builds"))

    override suspend fun resetScenario(scenarioType: ChaosScenarioType): Result<Unit> = Result.success(Unit)

    override suspend fun resetAllScenarios(): Result<Unit> = Result.success(Unit)
}
