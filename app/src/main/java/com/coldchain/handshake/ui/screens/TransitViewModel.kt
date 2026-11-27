package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.alerts.AlertChecks
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.recording.RecordedLocation
import com.coldchain.handshake.recording.RecordedLocations
import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.PollBackoff
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @property shipment The shipment on screen: the one this phone records or watches.
 * @property signedInAs The signed-in person's user ID; null in debug local mode.
 * @property watching This phone follows a shipment someone else carries, instead of recording it.
 * @property isCustodian The server says the signed-in person holds custody.
 * @property notice Why the last action didn't work.
 * @property handedOverShipmentId Set when custody moved away from this phone while it was recording.
 * @property remoteGps The custodian's last reported location, while watching.
 * @property myLocation The newest location this phone recorded for the shipment, and whether the server has it.
 * @property locating The recording service records location right now (allowed is not enough: Android may refuse it).
 * @property serverCheckedAt While watching, when this phone last fetched the shipment's readings from the server.
 * @property serverUnreachable While watching, the last fetch failed: what is shown may be out of date.
 * @property tripEnded While watching, the trip is over: a receiver holds the shipment, or it is past the road.
 */
data class TransitUiState(
    val shipment: Shipment? = null,
    val signedInAs: String? = null,
    val watching: Boolean = false,
    val isCustodian: Boolean = false,
    val notice: String? = null,
    val handedOverShipmentId: String? = null,
    val remoteGps: GpsLocationData? = null,
    val myLocation: RecordedLocation? = null,
    val locating: Boolean = false,
    val serverCheckedAt: Long? = null,
    val serverUnreachable: Boolean = false,
    val tripEnded: Boolean = false
)

/**
 * The Transit screen: take custody and record, or open a shipment by its code and watch it. Only the server says
 * who holds custody. Recording, and sending what is recorded, run in the background through [CourierRecorder] and
 * the recording service, so they go on with the screen off; the service also says when custody moved away. What this
 * screen fetches while watching (the readings, custody, the shipment and the courier's position) runs only inside
 * [runWhileVisible], which the screen runs while it is shown, and backs off while nothing changes.
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
    private val recordedLocations: RecordedLocations,
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
    val lastPersistenceError: StateFlow<String?> = simulator.lastPersistenceError

    private val me: String? get() = (account.state.value as? AccountState.Ready)?.userId

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
                    else it.copy(
                        shipment = shipment, isCustodian = false, remoteGps = null, myLocation = null,
                        serverCheckedAt = null, serverUnreachable = false, tripEnded = false
                    )
                }
                saved[KEY_SHIPMENT_ID] = shipment?.id
            }
        }
        // The newest location the recording service queued here for the shipment on screen.
        viewModelScope.launch {
            simulator.activeShipment.map { it?.id }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else recordedLocations.latest(id) }
                .collect { location -> _state.update { it.copy(myLocation = location) } }
        }
        viewModelScope.launch {
            recordedLocations.active.collect { on -> _state.update { it.copy(locating = on) } }
        }
        viewModelScope.launch {
            account.state.collect { s -> _state.update { it.copy(signedInAs = (s as? AccountState.Ready)?.userId) } }
        }
        // While recording, custody moving to someone else ends recording on this phone: the recording service reads
        // custody and says so, and this screen tells the person, even if it was away when it happened.
        viewModelScope.launch {
            simulator.handedOver.filterNotNull().collect { id ->
                simulator.reset()
                simulator.handOverShown()
                _state.update { it.copy(handedOverShipmentId = id, isCustodian = false) }
            }
        }
        // Who holds custody, read once for each shipment and person.
        viewModelScope.launch {
            combine(simulator.activeShipment, account.state) { shipment, _ -> shipment to me }
                .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second == b.second }
                .collect { (shipment, user) -> if (shipment != null && user != null) readCustody(shipment, user) }
        }
    }

    /**
     * Runs the screen's polling until the caller is cancelled: only while watching. While this phone records, the
     * recording service already reads custody and says when it moved ([TemperatureSimulator.handedOver]).
     */
    suspend fun runWhileVisible(): Unit = coroutineScope {
        launch {
            _state.map { Triple(it.shipment?.id, it.shipment?.genesisHash != null, it.watching) }
                .distinctUntilChanged()
                .collectLatest { (id, onServer, watching) ->
                    if (id != null && onServer && watching) watch(id)
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

    /**
     * A code only names a shipment this phone holds or the server lets this person open; nothing is built from it. One
     * this phone holds is opened as the server has it now, so a courier who handed it on sees where it ended up, not the
     * status it had when they let it go; with no answer, as this phone has it.
     */
    fun onCodeScanned(code: String): Job = viewModelScope.launch {
        val held = shipments.getShipment(code).firstOrNull()
        var resolved = held
        if (me != null && (held == null || held.genesisHash != null)) {
            val opened = workflow.open(code)
            resolved = opened.getOrNull() ?: held
            if (resolved == null) opened.onFailure { e -> _state.update { it.copy(notice = e.message) } }
        }
        if (resolved == null) return@launch
        _state.update { it.copy(notice = null) }
        simulator.attachShipmentForMonitoring(resolved)
        setWatching(true)
        if (resolved.genesisHash != null) history.hydrateHistory(resolved.id)
    }

    /** Location allowed since recording started: the recording already running adds it, without starting again. */
    fun keepRecordingInBackground() = recorder.refreshBackground()

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

    /**
     * While watching: the readings and custody each round, and the shipment and the custodian's location whenever either
     * moved (the courier's phone sends its location with its readings). Quick while things change, and less often each
     * round nothing does, up to every 30 s (finding 11; [PollBackoff]).
     */
    private suspend fun watch(shipmentId: String) {
        val backoff = PollBackoff()
        var lastCustody: CustodyState? = null
        var firstRound = true
        while (true) {
            val fetched = history.hydrateHistory(shipmentId)
            val checkedAt = System.currentTimeMillis()
            _state.update {
                if (fetched.isSuccess) it.copy(serverCheckedAt = checkedAt, serverUnreachable = false)
                else it.copy(serverUnreachable = true)
            }
            val newReadings = fetched.getOrNull() ?: 0
            val custody = remote.getCustody(shipmentId).getOrNull()
            val custodyMoved = custody != null && custody != lastCustody
            if (custody != null) lastCustody = custody
            val changed = firstRound || newReadings > 0 || custodyMoved
            if (changed) {
                remote.getLatestGpsPoint(shipmentId).getOrNull()?.let { point ->
                    _state.update {
                        it.copy(remoteGps = GpsLocationData(point.latitude, point.longitude, point.accuracyMeters, point.deviceTimestamp))
                    }
                }
                val status = workflow.open(shipmentId).getOrNull()?.status
                // Once the trip is over the silence since isn't the courier's (Gate 5 review S4). Members are read only
                // when custody moved: the receiver holding it, or a status past the road, ends the trip.
                val custodianRole = if (custody == null || !(custodyMoved || firstRound)) null
                else remote.getMembers(shipmentId).getOrNull()?.firstOrNull { it.userId == custody.custodianId }?.role
                val ended = status in TRIP_OVER || custodianRole == MemberRole.RECEIVER
                if (custodianRole != null || status in TRIP_OVER) _state.update { it.copy(tripEnded = ended) }
            }
            val user = me
            if (custody != null && user != null && custody.custodianId == user && takeOverRecording(shipmentId)) {
                _state.update { it.copy(isCustodian = true) }
                setWatching(false)
            }
            firstRound = false
            delay(backoff.after(changed))
        }
    }

    /**
     * Custody has come to this person. A courier (5.3, a later leg) records from now on, on a history complete up to
     * the server's head, so their phone carries the same chain on; a receiver has nothing to record. False if the
     * members or the history couldn't be fetched, so the next round tries again.
     */
    private suspend fun takeOverRecording(shipmentId: String): Boolean {
        // Their role on this shipment decides, not their profile's: a receiver's FAIL leaves the shipment QUARANTINED,
        // which counts as on the move (Gate 5 review S6).
        val user = me ?: return true
        val members = remote.getMembers(shipmentId).getOrElse { return false }
        if (members.firstOrNull { it.userId == user }?.role != MemberRole.COURIER) return true
        history.hydrateHistory(shipmentId).getOrElse { return false }
        val shipment = shipments.getShipment(shipmentId).firstOrNull() ?: return false
        if (shipment.status in CourierRecorder.ON_THE_MOVE && !simulator.isRunning.value) recorder.start(shipment)
        return true
    }

    companion object {
        private const val STOP_SHARING_MS = 5_000L
        /** Statuses past the road: nothing more is recorded once a shipment is in one. */
        private val TRIP_OVER = setOf(
            ShipmentStatus.ARRIVED, ShipmentStatus.INSPECTED, ShipmentStatus.ACCEPTED, ShipmentStatus.REPLACEMENT_REQUESTED
        )
        private const val KEY_SHIPMENT_ID = "shipmentId"
        private const val KEY_WATCHING = "watching"
        /** The worker ID the old two-phone demo gave a phone that only watches. */
        private const val MONITOR_WORKER_ID = "W-MONITOR"
    }
}
