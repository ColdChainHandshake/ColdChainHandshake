package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.crypto.UploadAudit
import com.coldchain.handshake.crypto.UploadIssue
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.HandoverOrchestratorResult
import com.coldchain.handshake.handover.HandoverResult
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.CustodyState
import com.coldchain.handshake.repository.GpsPoint
import com.coldchain.handshake.repository.HandoverOutcome
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.HistorySyncState
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentMember
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @property me The signed-in person; null when signed out or in debug local mode.
 * @property custody Who holds the shipment, as the server says.
 * @property members The shipment's people on the server.
 * @property uploadIssues Problems the server's batches show against this phone's readings; null before they're read.
 * @property lastLocation The custodian's last reported location.
 * @property result A handover recorded on this phone alone (debug local mode).
 * @property error Why the last action failed.
 * @property notice What the server said happened.
 * @property processing An action is running.
 */
data class HandoverUiState(
    val shipment: Shipment? = null,
    val me: AccountState.Ready? = null,
    val custody: CustodyState? = null,
    val members: List<ShipmentMember> = emptyList(),
    val uploadIssues: List<UploadIssue>? = null,
    val lastLocation: GpsPoint? = null,
    val result: HandoverOrchestratorResult? = null,
    val error: String? = null,
    val notice: String? = null,
    val processing: Boolean = false,
    val vvmStage: VvmStage = VvmStage.NOT_RECORDED,
    val shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE
) {
    /** A shipment on the server is only ever confirmed there, by someone signed in. */
    val onServer: Boolean get() = me != null && shipment?.genesisHash != null
}

/**
 * The Handover screen: the receiver's inspection and verdict, the custodian naming who takes over, and what the
 * server says. What it reads from the server it reads only inside [runWhileVisible], as the screen's own effects
 * did. The verdict itself is always worked out again where it is recorded (`ServerHandover`, `HandoverOrchestrator`),
 * never taken from here. The inspection answers are kept in [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HandoverViewModel @Inject constructor(
    private val account: AccountRepository,
    private val simulator: TemperatureSimulator,
    private val history: ShipmentHistorySyncCoordinator,
    private val telemetry: TelemetryRepository,
    private val alerts: AlertRepository,
    private val handovers: HandoverRepository,
    private val remote: ColdChainRemote,
    private val serverHandover: ServerHandover,
    private val orchestrator: HandoverOrchestrator,
    private val saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(
        HandoverUiState(
            shipment = simulator.activeShipment.value,
            me = account.state.value as? AccountState.Ready,
            vvmStage = saved[KEY_VVM] ?: VvmStage.NOT_RECORDED,
            shakeTest = saved[KEY_SHAKE] ?: ShakeTestResult.NOT_DONE
        )
    )
    val state: StateFlow<HandoverUiState> = _state.asStateFlow()

    private val shipmentId: Flow<String?> = simulator.activeShipment.map { it?.id }.distinctUntilChanged()

    private fun <T> perShipment(none: T, source: (String) -> Flow<T>): Flow<T> =
        shipmentId.flatMapLatest { id -> if (id == null) flowOf(none) else source(id) }

    val syncState: StateFlow<HistorySyncState> = perShipment(HistorySyncState.IDLE, history::getSyncState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistorySyncState.IDLE)

    /** What the server holds, which is what catches a removed tail or a rewritten history. */
    val serverHead: StateFlow<ServerChainHead?> = perShipment(null, history::getServerHead)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val readings: StateFlow<List<TemperatureEvent>> = perShipment(emptyList(), telemetry::getTemperatures)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), emptyList())

    val tripAlerts: StateFlow<List<Alert>> = perShipment(emptyList(), alerts::getAllAlerts)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), emptyList())

    /** A handover already recorded for this shipment, which the screen must show rather than replace. */
    val storedHandover: StateFlow<Handover?> = perShipment(null, handovers::getHandover)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), null)

    /** With no signal the server can't say who holds custody; the phone running the logger stands in. */
    val monitorOnly: StateFlow<Boolean> = simulator.isMonitorOnly

    private val serverRefresh = MutableStateFlow(0)

    init {
        viewModelScope.launch { simulator.activeShipment.collect { s -> _state.update { it.copy(shipment = s) } } }
        viewModelScope.launch {
            account.state.collect { s -> _state.update { it.copy(me = s as? AccountState.Ready) } }
        }
    }

    /** Fetches what the screen shows from the server, until the caller is cancelled. */
    suspend fun runWhileVisible(): Unit = coroutineScope {
        // The history, once for each shipment. One kept on this phone alone has nothing on the server to fetch.
        launch {
            simulator.activeShipment.map { s -> s?.let { it.id to (it.genesisHash != null) } }
                .distinctUntilChanged()
                .collect { opened ->
                    val (id, onServer) = opened ?: return@collect
                    if (onServer) history.hydrateHistory(id) else history.markReady(id)
                }
        }
        // Who holds it and who is on it, again whenever the readings or a confirmation change.
        launch {
            val readingsHere = perShipment(emptyList(), telemetry::getTemperatures)
            combine(_state.map { it.shipment?.id to it.onServer }.distinctUntilChanged(), readingsHere, serverRefresh) { key, events, _ ->
                key to events
            }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second.size == b.second.size }
                .collect { (key, events) -> if (key.second) loadServerView(key.first!!, events) }
        }
        launch {
            _state.map { it.shipment?.id to it.onServer }.distinctUntilChanged().collect { (id, onServer) ->
                if (id != null && onServer) {
                    val point = remote.getLatestGpsPoint(id).getOrNull()
                    _state.update { it.copy(lastLocation = point) }
                }
            }
        }
    }

    fun setVvmStage(stage: VvmStage) {
        saved[KEY_VVM] = stage
        _state.update { it.copy(vvmStage = stage) }
    }

    fun setShakeTest(result: ShakeTestResult) {
        saved[KEY_SHAKE] = result
        _state.update { it.copy(shakeTest = result) }
    }

    /** The custodian names who takes over. The handover is held against the readings so far, so the logger stops first. */
    fun release(toUserId: String): Job = action(clearNotice = false) { shipment ->
        simulator.stopContinuousSimulation()
        serverHandover.release(shipment.id, toUserId)
            .onSuccess { outcome -> _state.update { it.copy(notice = describeOutcome(outcome)) } }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
        serverRefresh.update { it + 1 }
    }

    fun refresh(): Job = viewModelScope.launch {
        val shipment = _state.value.shipment ?: return@launch
        serverHandover.refresh(shipment.id).onFailure { e -> _state.update { it.copy(error = e.message) } }
        serverRefresh.update { it + 1 }
    }

    fun retryHistory(): Job = viewModelScope.launch {
        _state.value.shipment?.let { history.hydrateHistory(it.id) }
    }

    /**
     * Records this person's verdict. On the server it is a confirmation, checked there; on this phone alone the
     * orchestrator works the verdict out again from the stored readings.
     */
    fun record(verdict: HandoverVerdict, reason: String?): Job = action(clearNotice = true) { shipment ->
        val current = _state.value
        if (current.onServer) {
            serverHandover.receive(shipment.id, verdict, reason, current.vvmStage, current.shakeTest)
                .onSuccess { result ->
                    val notice = when (result) {
                        is HandoverResult.Confirmed -> describeOutcome(result.outcome)
                        is HandoverResult.QueuedOffline ->
                            "No connection. Your ${result.handover.verdict.name} is saved on this " +
                                "phone and will be sent. Custody hasn't moved."
                    }
                    _state.update { it.copy(notice = notice) }
                    serverRefresh.update { it + 1 }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        } else {
            orchestrator.processHandover(
                shipmentId = shipment.id,
                workerSigned = true,
                pharmacistSigned = true,
                declaredVerdict = verdict,
                reason = reason,
                vvmStage = current.vvmStage,
                shakeTest = current.shakeTest,
                historySynced = syncState.value == HistorySyncState.HISTORY_READY
            )
                .onSuccess { res ->
                    _state.update { it.copy(result = res) }
                    simulator.onHandedOver(res.updatedShipment)
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Handover processing failed") } }
        }
    }

    fun requestReplacement(): Job = action(clearNotice = false) { shipment ->
        val recorded = _state.value.result ?: return@action
        orchestrator.requestReplacement(shipment.id)
            .onSuccess { updated -> _state.update { it.copy(result = recorded.copy(updatedShipment = updated)) } }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Could not request a replacement") } }
    }

    private fun action(clearNotice: Boolean, block: suspend (Shipment) -> Unit): Job = viewModelScope.launch {
        val shipment = _state.value.shipment ?: return@launch
        _state.update { it.copy(processing = true, error = null, notice = if (clearNotice) null else it.notice) }
        try {
            block(shipment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            _state.update { it.copy(processing = false) }
        }
    }

    private suspend fun loadServerView(shipmentId: String, events: List<TemperatureEvent>) {
        val custody = remote.getCustody(shipmentId).getOrNull()
        val members = remote.getMembers(shipmentId).getOrDefault(emptyList())
        val issues = remote.getBatches(shipmentId).getOrNull()?.let { UploadAudit.check(shipmentId, it, events) }
        _state.update { it.copy(custody = custody, members = members, uploadIssues = issues) }
    }

    companion object {
        private const val STOP_SHARING_MS = 5_000L
        private const val KEY_VVM = "vvmStage"
        private const val KEY_SHAKE = "shakeTest"
    }
}

internal fun describeOutcome(outcome: HandoverOutcome): String = when (outcome.state) {
    HandoverState.TRANSFERRED -> "Custody has moved. The shipment is now ${outcome.status.name}."
    HandoverState.WAITING -> "Confirmed. Waiting for the other person to confirm on their own phone."
}
