package com.coldchain.handshake.simulator

import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.ChaosEngineService
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Deterministic Simulated Logger for ColdChainHandshake (Person 2).
 *
 * Requirements:
 * - Normal range: approx 4.0°C to 6.0°C (safe range 2.0°C to 8.0°C).
 * - Heat spike: approx 9.0°C to 11.0°C (>8°C excursion).
 * - Deterministic timestamp progression (+60s simulated per tick).
 * - All events routed strictly through [TelemetryRepository.saveTemperature].
 * - Observes [ChaosEngineService] for HEAT_SPIKE scenario activation.
 */
class TemperatureSimulator(
    private val telemetryRepository: TelemetryRepository,
    private val chaosEngineService: ChaosEngineService? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher)
    private var simulationJob: Job? = null
    private var chaosObserverJob: Job? = null

    // Deterministic temperature patterns
    private val normalReadings = doubleArrayOf(4.5, 4.8, 5.1, 5.4, 5.0, 4.7, 4.9, 5.2, 5.5, 4.6)
    private val heatSpikeReadings = doubleArrayOf(9.3, 9.8, 10.2, 10.7, 10.4, 9.9, 9.5, 10.1, 10.8, 11.0)

    private var stepCounter = 0

    private val _activeShipment = MutableStateFlow<Shipment?>(null)
    val activeShipment: StateFlow<Shipment?> = _activeShipment.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isHeatSpikeMode = MutableStateFlow(false)
    val isHeatSpikeMode: StateFlow<Boolean> = _isHeatSpikeMode.asStateFlow()

    private val _latestTemperature = MutableStateFlow<Double?>(null)
    val latestTemperature: StateFlow<Double?> = _latestTemperature.asStateFlow()

    private val _simulatedTimestamp = MutableStateFlow(System.currentTimeMillis())
    val simulatedTimestamp: StateFlow<Long> = _simulatedTimestamp.asStateFlow()

    init {
        // Automatically link with ChaosEngineService if present
        chaosEngineService?.let { service ->
            chaosObserverJob = scope.launch {
                service.observeActiveScenarios().collect { scenarios ->
                    val hasHeatSpike = scenarios.any { it.scenarioType == ChaosScenarioType.HEAT_SPIKE && it.isActive }
                    _isHeatSpikeMode.value = hasHeatSpike
                }
            }
        }
    }

    /**
     * Attaches the shipment to monitor and resets simulation step counter.
     */
    fun attachShipment(shipment: Shipment, startTimestamp: Long = System.currentTimeMillis()) {
        _activeShipment.value = shipment
        _simulatedTimestamp.value = startTimestamp
        stepCounter = 0
    }

    fun setHeatSpikeMode(enabled: Boolean) {
        _isHeatSpikeMode.value = enabled
        // If chaos engine service is attached, also trigger or reset scenario
        chaosEngineService?.let { service ->
            scope.launch {
                if (enabled) {
                    service.injectScenario(ChaosScenarioType.HEAT_SPIKE)
                } else {
                    service.resetScenario(ChaosScenarioType.HEAT_SPIKE)
                }
            }
        }
    }

    /**
     * Executes a single deterministic simulation step:
     * - Advances simulated timestamp by [simulatedStepDurationSeconds] (default 60s).
     * - Generates deterministic reading (normal 4–6°C or heat spike 9–11°C).
     * - Routes event strictly to [TelemetryRepository.saveTemperature].
     */
    suspend fun tickOnce(simulatedStepDurationSeconds: Long = 60L): TemperatureEvent? {
        val shipment = _activeShipment.value ?: return null

        val temp = if (_isHeatSpikeMode.value) {
            heatSpikeReadings[stepCounter % heatSpikeReadings.size]
        } else {
            normalReadings[stepCounter % normalReadings.size]
        }

        val nextTimestamp = _simulatedTimestamp.value + (simulatedStepDurationSeconds * 1000L)
        _simulatedTimestamp.value = nextTimestamp
        _latestTemperature.value = temp
        stepCounter++

        val event = TemperatureEvent(
            id = "TE-${UUID.randomUUID().toString().take(8).uppercase()}",
            shipmentId = shipment.id,
            loggerId = shipment.loggerId,
            timestamp = nextTimestamp,
            temperature = temp,
            previousHash = "",
            currentHash = "",
            syncStatus = SyncStatus.PENDING
        )

        telemetryRepository.saveTemperature(event)
        return event
    }

    /**
     * Starts continuous background simulation emitting a reading every [intervalMs] (default 1000ms).
     */
    fun startContinuousSimulation(
        intervalMs: Long = 1000L,
        simulatedStepSeconds: Long = 60L
    ) {
        if (_isRunning.value) return
        _isRunning.value = true

        simulationJob = scope.launch {
            while (isActive && _isRunning.value) {
                tickOnce(simulatedStepSeconds)
                delay(intervalMs)
            }
        }
    }

    fun stopContinuousSimulation() {
        _isRunning.value = false
        simulationJob?.cancel()
        simulationJob = null
    }

    fun reset() {
        stopContinuousSimulation()
        _activeShipment.value = null
        _latestTemperature.value = null
        _isHeatSpikeMode.value = false
        stepCounter = 0
    }
}
