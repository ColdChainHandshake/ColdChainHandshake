package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.alerts.AlertChecks
import com.coldchain.handshake.di.DeviceId
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.GpsPoint
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.ui.navigation.ActiveShipmentRestorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * @property shipment The shipment on screen: the one this phone records or watches.
 * @property signedInAs The signed-in person's user ID; null in debug local mode.
 * @property watching This phone follows a shipment someone else carries, instead of recording it.
 * @property isCustodian The server says the signed-in person holds custody.
 * @property notice Why the last action didn't work.
 * @property handedOverShipmentId Set when custody moved away from this phone while it was recording.
 * @property remoteGps The custodian's last reported location, while watching.
 * @property gpsUploadStatus Whether this phone's last location reached the server.
 */
data class TransitUiState(
    val shipment: Shipment? = null,
    val signedInAs: String? = null,
    val watching: Boolean = false,
    val isCustodian: Boolean = false,
    val notice: String? = null,
    val handedOverShipmentId: String? = null,
    val remoteGps: GpsLocationData? = null,
    val gpsUploadStatus: String? = null
)

/**
 * The Transit screen: take custody and record, or open a shipment by its code and watch it. Only the server says
 * who holds custody. Recording, and sending what is recorded, run in the background through [CourierRecorder] and
 * the recording service, so they go on with the screen off. What this screen polls (custody while recording, the
 * courier's position and readings while watching) runs only inside [runWhileVisible], which the screen runs while
 * it is shown.
 *
 * The shipment on screen and the mode are kept in [SavedStateHandle]. After the process was killed the shipment comes
 * back attached to the simulator, as the custodian's or a watcher's as the server says, and recording resumes if it
 * was this shipment that was recording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransitViewModel @Inject constructor(
    private val account: AccountRepository,
    private val shipments: ShipmentRepository,
    private val workflow: ShipmentWorkflow,
    private val simulator: TemperatureSimulator,
    private val history: ShipmentHistorySyncCoordinator,
    private val remote: ColdChainRemote,
    private val telemetry: TelemetryRepository,
    private val recorder: CourierRecorder,
    @DeviceId private val deviceId: String,
    private val alertChecks: AlertChecks,
    private val restorer: ActiveShipmentRestorer,
    private val saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(
        TransitUiState(
            shipment = simulator.activeShipment.value,
            signedInAs = (account.state.value as? AccountState.Ready)?.userId,
            watching = saved[KEY_WATCHING] ?: (simulator.activeShipment.value?.workerId == MONITOR_WORKER_ID)
        )
    )
    val state: StateFlow<TransitUiState> = _state.asStateFlow()

    /** The shipment's readings as this phone stores them. */
    val readings: StateFlow<List<TemperatureEvent>> = simulator.activeShipment
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else telemetry.getTemperatures(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), emptyList())

    val latestTemperature: StateFlow<Double?> = simulator.latestTemperature
    val isRecording: StateFlow<Boolean> = simulator.isRunning
    val isHeatSpikeMode: StateFlow<Boolean> = simulator.isHeatSpikeMode
    val simulatedTimestamp: StateFlow<Long> = simulator.simulatedTimestamp
    val lastPersistenceError: StateFlow<String?> = simulator.lastPersistenceError

    private val me: String? get() = (account.state.value as? AccountState.Ready)?.userId
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        val savedId = saved.get<String>(KEY_SHIPMENT_ID)
        if (simulator.activeShipment.value == null && savedId != null) {
            viewModelScope.launch { restorer.restore(savedId, watching = saved.get<Boolean>(KEY_WATCHING) == true) }
        }
        viewModelScope.launch {
            // This ViewModel outlives a tab switch, so another shipment starts without the last one's location and
            // custody. The handed-over notice stays: it is set just as the shipment is let go.
            simulator.activeShipment.collect { shipment ->
                _state.update {
                    if (shipment?.id == it.shipment?.id) it.copy(shipment = shipment)
                    else it.copy(shipment = shipment, isCustodian = false, remoteGps = null, gpsUploadStatus = null)
                }
                saved[KEY_SHIPMENT_ID] = shipment?.id
            }
        }
        viewModelScope.launch {
            account.state.collect { s -> _state.update { it.copy(signedInAs = (s as? AccountState.Ready)?.userId) } }
        }
        // Who holds custody, read once for each shipment and person.
        viewModelScope.launch {
            combine(simulator.activeShipment, account.state) { shipment, _ -> shipment to me }
                .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second == b.second }
                .collect { (shipment, user) -> if (shipment != null && user != null) readCustody(shipment, user) }
        }
    }

    /** Runs the screen's polling until the caller is cancelled. */
    suspend fun runWhileVisible(): Unit = coroutineScope {
        launch {
            _state.map { Triple(it.shipment?.id, it.shipment?.genesisHash != null, it.watching) }
                .distinctUntilChanged()
                .collect { (id, onServer, watching) ->
                    if (id == null || !onServer) return@collect
                    if (watching) watch(id) else watchForHandover(id)
                }
        }
    }

    fun setWatching(watching: Boolean) {
        _state.update { it.copy(watching = watching) }
        saved[KEY_WATCHING] = watching
    }

    /** A courier takes custody on their own phone, and the server records it. Recording starts here. */
    fun takeCustody(): Job = viewModelScope.launch {
        val waiting = _state.value.shipment ?: return@launch
        workflow.takeCustody(waiting.id)
            .onSuccess { taken ->
                _state.update { it.copy(notice = null, isCustodian = true) }
                setWatching(false)
                history.markReady(taken.id)
                recorder.start(taken)
                alertChecks.checkNow()
            }
            .onFailure { e -> _state.update { it.copy(notice = e.message) } }
    }

    /** A code only names a shipment this phone holds or the server lets this person open; nothing is built from it. */
    fun onCodeScanned(code: String): Job = viewModelScope.launch {
        var resolved = shipments.getShipment(code).firstOrNull()
        if (resolved == null && me != null) {
            resolved = workflow.open(code).onFailure { e -> _state.update { it.copy(notice = e.message) } }.getOrNull()
        }
        if (resolved == null) return@launch
        _state.update { it.copy(notice = null) }
        simulator.attachShipmentForMonitoring(resolved)
        setWatching(true)
        if (resolved.genesisHash != null) history.hydrateHistory(resolved.id)
    }

    /** Only for a shipment the server holds, which then decides whether this person may report it. */
    fun uploadLocation(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long): Job = viewModelScope.launch {
        val shipment = _state.value.shipment ?: return@launch
        if (me == null || shipment.genesisHash == null) return@launch
        val point = GpsPoint(UUID.randomUUID().toString(), shipment.id, latitude, longitude, accuracy, timestamp)
        val status = remote.appendGpsPoints(shipment.id, deviceId, listOf(point)).fold(
            { "Synced ${timeFormat.format(Date(timestamp))}" },
            { "Not sent: ${it.message ?: "network issue"}" }
        )
        _state.update { it.copy(gpsUploadStatus = status) }
    }

    fun acknowledgeHandover() = _state.update { it.copy(handedOverShipmentId = null) }

    /** Debug tools: the simulator's own controls. */
    fun pauseOrResumeRecording() {
        if (simulator.isRunning.value) recorder.pause()
        else _state.value.shipment?.let { recorder.start(it) }
    }

    fun recordOneReading(): Job = viewModelScope.launch { simulator.tickOnce(simulatedStepDurationSeconds = 60L) }

    fun toggleHeatSpike() = simulator.setHeatSpikeMode(!simulator.isHeatSpikeMode.value)

    private suspend fun readCustody(shipment: Shipment, user: String) {
        if (shipment.genesisHash == null) return
        val custody = remote.getCustody(shipment.id).getOrNull() ?: return
        val mine = custody.custodianId == user
        _state.update { it.copy(isCustodian = mine) }
        if (!mine) setWatching(true)
    }

    /** While recording: custody moving to someone else ends recording on this phone. */
    private suspend fun watchForHandover(shipmentId: String) {
        while (true) {
            delay(CUSTODY_POLL_MS)
            val custody = remote.getCustody(shipmentId).getOrNull()
            val user = me
            if (custody != null && user != null && custody.custodianId != user) {
                simulator.reset()
                _state.update { it.copy(handedOverShipmentId = shipmentId, isCustodian = false) }
                return
            }
        }
    }

    /** While watching: the custodian's location, the readings, the shipment, and whether custody came here. */
    private suspend fun watch(shipmentId: String) {
        while (true) {
            remote.getLatestGpsPoint(shipmentId).getOrNull()?.let { point ->
                _state.update {
                    it.copy(remoteGps = GpsLocationData(point.latitude, point.longitude, point.accuracyMeters, point.deviceTimestamp))
                }
            }
            history.hydrateHistory(shipmentId)
            workflow.open(shipmentId)
            val custody = remote.getCustody(shipmentId).getOrNull()
            val user = me
            if (custody != null && user != null && custody.custodianId == user) {
                _state.update { it.copy(isCustodian = true) }
                setWatching(false)
            }
            delay(WATCH_POLL_MS)
        }
    }

    companion object {
        const val CUSTODY_POLL_MS = 3_500L
        const val WATCH_POLL_MS = 3_000L
        private const val STOP_SHARING_MS = 5_000L
        private const val KEY_SHIPMENT_ID = "shipmentId"
        private const val KEY_WATCHING = "watching"
        /** The worker ID the old two-phone demo gave a phone that only watches. */
        private const val MONITOR_WORKER_ID = "W-MONITOR"
    }
}
