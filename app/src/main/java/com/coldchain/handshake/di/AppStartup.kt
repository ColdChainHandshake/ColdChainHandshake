package com.coldchain.handshake.di

import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.repository.ChaosEngineService
import com.coldchain.handshake.repository.LocalSession
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's background jobs, started once per process: the check that this phone's records belong to the person
 * signing in, and the debug chaos lab's logger-disconnect scenario. `MainActivity` calls [start] on every creation,
 * rotation included; only the first call starts anything, so a second set never runs beside the first.
 */
@Singleton
class AppStartup @Inject constructor(
    private val session: LocalSession,
    private val chaos: ChaosEngineService,
    private val simulator: TemperatureSimulator,
    private val safetyEngine: SafetyEngine,
    @AppScope private val scope: CoroutineScope
) {
    /** What [start] launched; empty until then. */
    var jobs: List<Job> = emptyList()
        private set

    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(session.watch(scope), watchLoggerDisconnect())
    }

    private fun watchLoggerDisconnect(): Job = scope.launch {
        var wasDisconnectActive = false
        chaos.observeActiveScenarios().collect { scenarios ->
            val isDisconnectActive = scenarios.any { it.scenarioType == ChaosScenarioType.LOGGER_DISCONNECT && it.isActive }
            if (isDisconnectActive && !wasDisconnectActive) {
                val activeShipment = simulator.activeShipment.value
                safetyEngine.handleLoggerDisconnect(
                    shipmentId = activeShipment?.id ?: "SHIP-ACTIVE",
                    loggerId = activeShipment?.loggerId ?: "LOG-SIMULATED",
                    reason = "Chaos injected: Logger sensor disconnected"
                )
            }
            wasDisconnectActive = isDisconnectActive
        }
    }
}
