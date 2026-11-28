package com.coldchain.handshake.simulator

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.ChaosEngineService
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.safety.QuarantineManager
import com.coldchain.handshake.safety.SafetyEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Deterministic Simulated Logger for ColdChainHandshake (Person 2).
 *
 * Requirements:
 * - Normal range: approx 4.0°C to 6.0°C (safe range 2.0°C to 8.0°C).
 * - Heat spike: approx 9.0°C to 11.0°C (>8°C excursion).
 * - With a [wallClock], readings are dated by it and a tick less than a step after the last reading
 *   adds nothing, so stopping leaves a real gap and no reading is dated in the future. Without one,
 *   each tick adds a fixed step, which keeps tests deterministic.
 * - All events routed strictly through [TelemetryRepository.saveTemperature].
 * - Telemetry hashed with SHA-256 BEFORE persistence.
 * - Observes [ChaosEngineService] for HEAT_SPIKE scenario activation.
 * - Integrates with [SafetyEngine] to trigger breach alerts & quarantine.
 */
class TemperatureSimulator(
    private val telemetryRepository: TelemetryRepository,
    private val chaosEngineService: ChaosEngineService? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val safetyEngine: SafetyEngine? = null,
    private val wallClock: (() -> Long)? = null,
    /** Debug local mode's virtual loggers; each local shipment's readings are also its logger's records. */
    private val localLoggers: LocalLoggers? = null
) {
    private val scope = CoroutineScope(dispatcher)
    private var simulationJob: Job? = null
    private var chaosObserverJob: Job? = null

    // Deterministic temperature patterns
    private val normalReadings = doubleArrayOf(4.5, 4.8, 5.1, 5.4, 5.0, 4.7, 4.9, 5.2, 5.5, 4.6)
    private val heatSpikeReadings = doubleArrayOf(9.3, 9.8, 10.2, 10.7, 10.4, 9.9, 9.5, 10.1, 10.8, 11.0)

    private var stepCounter = 0
    private val tickMutex = Mutex()

    private val _activeShipment = MutableStateFlow<Shipment?>(null)
    val activeShipment: StateFlow<Shipment?> = _activeShipment.asStateFlow()

    private val _isMonitorOnly = MutableStateFlow(false)
    val isMonitorOnly: StateFlow<Boolean> = _isMonitorOnly.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isHeatSpikeMode = MutableStateFlow(false)
    val isHeatSpikeMode: StateFlow<Boolean> = _isHeatSpikeMode.asStateFlow()

    private val _latestTemperature = MutableStateFlow<Double?>(null)
    val latestTemperature: StateFlow<Double?> = _latestTemperature.asStateFlow()

    private val _simulatedTimestamp = MutableStateFlow(System.currentTimeMillis())
    val simulatedTimestamp: StateFlow<Long> = _simulatedTimestamp.asStateFlow()

    private val _lastPersistenceError = MutableStateFlow<String?>(null)
    val lastPersistenceError: StateFlow<String?> = _lastPersistenceError.asStateFlow()

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
    /**
     * Attaches the shipment in read-only monitor mode (Phone B).
     * Stops continuous simulation and disables event generation so Phone B
     * acts purely as a monitor and never creates competing telemetry.
     */
    fun attachShipmentForMonitoring(shipment: Shipment) {
        stopContinuousSimulation()
        _isMonitorOnly.value = true
        _activeShipment.value = shipment
        _lastPersistenceError.value = null
    }

    private val _handedOver = MutableStateFlow<String?>(null)

    /** The shipment whose custody moved away while this phone recorded it, until a screen has said so. */
    val handedOver: StateFlow<String?> = _handedOver.asStateFlow()

    /**
     * A shipment that has been handed over has stopped moving, so nothing more is recorded for it.
     */
    fun onHandedOver(shipment: Shipment) {
        if (_activeShipment.value?.id == shipment.id) {
            attachShipmentForMonitoring(shipment)
            _handedOver.value = shipment.id
        }
    }

    /** A screen has told the person that custody moved. */
    fun handOverShown() {
        _handedOver.value = null
    }

    /**
     * Attaches the shipment to monitor and resets simulation step counter (Transporter mode).
     */
    fun attachShipment(shipment: Shipment, startTimestamp: Long = System.currentTimeMillis()) {
        _isMonitorOnly.value = false
        _activeShipment.value = shipment
        _simulatedTimestamp.value = startTimestamp
        stepCounter = 0
        _lastPersistenceError.value = null
    }

    fun setHeatSpikeMode(enabled: Boolean) {
        if (_isHeatSpikeMode.value == enabled) return
        _isHeatSpikeMode.value = enabled
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

    suspend fun setHeatSpikeModeSync(enabled: Boolean) {
        if (_isHeatSpikeMode.value == enabled) return
        _isHeatSpikeMode.value = enabled
        if (enabled) {
            chaosEngineService?.injectScenario(ChaosScenarioType.HEAT_SPIKE)
        } else {
            chaosEngineService?.resetScenario(ChaosScenarioType.HEAT_SPIKE)
        }
    }

    /**
     * Executes a single deterministic simulation step:
     * - Advances simulated timestamp by [simulatedStepDurationSeconds] (default 60s).
     * - Generates deterministic reading (normal 4–6°C or heat spike 9–11°C).
     * - Computes SHA-256 hash chain before persistence.
     * - Routes event strictly to [TelemetryRepository.saveTemperature].
     * - Triggers [SafetyEngine] to detect thermal breaches and apply quarantine.
     */
    /**
     * Runs [block] between two readings, never while one is being made. A reading is saved before its local logger
     * records it, so a check reading both outside this could see a reading the logger hasn't made yet.
     */
    suspend fun <T> betweenReadings(block: suspend () -> T): T = tickMutex.withLock { block() }

    suspend fun tickOnce(simulatedStepDurationSeconds: Long = 60L): TemperatureEvent? {
        if (_isMonitorOnly.value) return null
        val shipment = _activeShipment.value ?: return null

        return tickMutex.withLock {
            val existingEvents = telemetryRepository.getTemperatures(shipment.id).firstOrNull()
            val latestExisting = existingEvents?.maxByOrNull { it.seq }

            val previousHash = latestExisting?.currentHash
                ?: shipment.genesisHash
                ?: HashChainService.UNBOUND_GENESIS

            val baseTimestamp = if (latestExisting != null) {
                maxOf(_simulatedTimestamp.value, latestExisting.timestamp)
            } else {
                _simulatedTimestamp.value
            }
            val stepMs = simulatedStepDurationSeconds * 1000L
            val now = wallClock?.invoke()
            if (now != null && latestExisting != null && now - latestExisting.timestamp < stepMs) {
                return@withLock null
            }
            val nextTimestamp = now ?: (baseTimestamp + stepMs)

            val currentStep = if (latestExisting != null && stepCounter == 0) {
                existingEvents.size
            } else {
                stepCounter
            }

            val temp = if (_isHeatSpikeMode.value) {
                heatSpikeReadings[currentStep % heatSpikeReadings.size]
            } else {
                normalReadings[currentStep % normalReadings.size]
            }

            val rawEvent = TemperatureEvent(
                id = "TE-${UUID.randomUUID().toString().take(8).uppercase()}",
                shipmentId = shipment.id,
                loggerId = shipment.loggerId,
                timestamp = nextTimestamp,
                temperature = temp,
                previousHash = previousHash,
                currentHash = "",
                syncStatus = SyncStatus.PENDING,
                seq = (latestExisting?.seq ?: 0L) + 1L
            )

            val hashedEvent = HashChainService.appendHash(rawEvent, previousHash)
            val saveResult = telemetryRepository.saveTemperature(hashedEvent)

            if (saveResult.isSuccess) {
                // A shipment kept on this phone alone (debug local mode): its logger records the same measurement, one
                // record per reading, started with the first. A server shipment has none (LocalLoggers).
                if (shipment.genesisHash == null) {
                    val logger = if (latestExisting == null) localLoggers?.startFor(shipment.id, shipment.loggerId)
                    else localLoggers?.of(shipment.id)
                    logger?.record(hashedEvent.timestamp, hashedEvent.tempCentiC)
                }
                _simulatedTimestamp.value = nextTimestamp
                _latestTemperature.value = temp
                stepCounter = currentStep + 1
                _lastPersistenceError.value = null

                // Person 3 Safety Decision Layer Hook: Evaluate safety & apply quarantine/alerts if breached
                safetyEngine?.let { engine ->
                    try {
                        val events = telemetryRepository.getTemperatures(shipment.id).firstOrNull() ?: listOf(hashedEvent)
                        val decision = engine.processTelemetryAndEnforceSafety(shipment, events)
                        val quarantineSaved = decision.quarantineSaveResult
                        if (quarantineSaved?.isSuccess == true) {
                            _activeShipment.value = _activeShipment.value?.let {
                                QuarantineManager.applyQuarantine(it, decision.evaluation)
                            }
                        }
                        quarantineSaved?.exceptionOrNull()?.let { _lastPersistenceError.value = it.message }
                    } catch (_: Exception) {}
                }

                hashedEvent
            } else {
                val errorMsg = saveResult.exceptionOrNull()?.message ?: "Failed to persist temperature event"
                _lastPersistenceError.value = errorMsg
                null
            }
        }
    }

    /**
     * Starts continuous background simulation emitting a reading every [intervalMs] (default 1000ms).
     */
    fun startContinuousSimulation(
        intervalMs: Long = 1000L,
        simulatedStepSeconds: Long = 60L
    ) {
        if (_isMonitorOnly.value) return
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

    /**
     * Resets simulator and resets HEAT_SPIKE chaos scenario so it cannot immediately reactivate.
     */
    fun reset() {
        stopContinuousSimulation()
        _isMonitorOnly.value = false
        _activeShipment.value = null
        _latestTemperature.value = null
        _isHeatSpikeMode.value = false
        stepCounter = 0
        _lastPersistenceError.value = null
        chaosEngineService?.let { service ->
            scope.launch {
                service.resetScenario(ChaosScenarioType.HEAT_SPIKE)
            }
        }
    }

    /**
     * Synchronous suspend reset ensuring chaos scenario reset completes before continuing.
     */
    suspend fun resetSync() {
        stopContinuousSimulation()
        _isMonitorOnly.value = false
        _activeShipment.value = null
        _latestTemperature.value = null
        _isHeatSpikeMode.value = false
        stepCounter = 0
        _lastPersistenceError.value = null
        chaosEngineService?.resetScenario(ChaosScenarioType.HEAT_SPIKE)
    }
}
