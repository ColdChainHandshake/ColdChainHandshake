package com.coldchain.handshake.data.network

import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.repository.ChaosEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Decorator around [NetworkMonitor] that simulates network failure when
 * [ChaosScenarioType.NETWORK_FAILURE] is injected via [ChaosEngineService].
 *
 * When NETWORK_FAILURE is active:
 * - isOnline() returns false
 * - observeNetworkState() emits false
 * - Telemetry continues buffering in local Room as PENDING
 * - Cloud sync remains suspended
 *
 * When NETWORK_FAILURE is reset:
 * - isOnline() and observeNetworkState() delegate back to real network state
 * - If real network is online, pending sync resumes automatically!
 */
class ChaosAwareNetworkMonitor(
    private val delegate: NetworkMonitor,
    private val chaosEngineService: ChaosEngineService,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : NetworkMonitor {

    private val isChaosOffline = MutableStateFlow(false)

    init {
        scope.launch {
            chaosEngineService.observeActiveScenarios().collect { scenarios ->
                val hasNetworkFailure = scenarios.any {
                    it.scenarioType == ChaosScenarioType.NETWORK_FAILURE && it.isActive
                }
                isChaosOffline.value = hasNetworkFailure
            }
        }
    }

    override fun isOnline(): Boolean {
        if (isChaosOffline.value) return false
        return delegate.isOnline()
    }

    override fun observeNetworkState(): Flow<Boolean> {
        return combine(delegate.observeNetworkState(), isChaosOffline) { delegateOnline, chaosOffline ->
            if (chaosOffline) false else delegateOnline
        }.distinctUntilChanged()
    }
}
