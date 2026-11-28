package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.crypto.UploadAudit
import com.coldchain.handshake.crypto.UploadIssue
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.HandoverOrchestratorResult
import com.coldchain.handshake.logger.LoggerCheckResult
import com.coldchain.handshake.simulator.LocalLoggerCheck
import com.coldchain.handshake.simulator.LocalLoggers
import com.coldchain.handshake.handover.HandoverResult
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.recording.CourierRecorder
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
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.PollBackoff
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentMember
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The readings the Handover screen shows and the server head it checks them against. */
data class ShownHistory(
    val readings: List<TemperatureEvent> = emptyList(),
    val serverHead: ServerChainHead? = null
)

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
 * @property confirmedHead The head this person's confirmation is waiting on, while the other person hasn't confirmed.
 * @property loggerCheck What reading the shipment's logger showed (`ServerHandover.loggerCheck`), the same outcome the
 *   receiver's confirmation records; null before it is read.
 * @property checkedAt When this phone last fetched the shipment's history from the server, changed or not: the screen
 *   judges the record as of then, so silence since the newest reading counts even when nothing new arrives.
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
    val shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE,
    val confirmedHead: String? = null,
    val checkedAt: Long? = null,
    val loggerCheck: LoggerCheckResult? = null
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
    private val recorder: CourierRecorder,
    private val localLoggers: LocalLoggers,
    private val saved: SavedStateHandle
) : ViewModel() {

    /** Debug local mode: the shipment's virtual logger on this phone (here the phone that recorded also receives). */
    private val localCheck = LocalLoggerCheck(simulator, localLoggers, telemetry)

    private suspend fun localLoggerCheck(shipment: Shipment): LoggerCheckResult = localCheck.check(shipment)

    private val _state = MutableStateFlow(
        freshFor(simulator.activeShipment.value).copy(me = account.state.value as? AccountState.Ready)
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

    /**
     * The readings the screen shows and the server head it checks them against, as a pair that belongs together.
     * The head is set the moment new readings are saved, and the stored readings follow a moment later; compared in
     * between, they look like a device missing the server's newest readings, which the screen calls tampering. So a
     * head ahead of the readings waits [CATCH_UP_MS] for them, showing the last head they agreed with meanwhile. Still
     * behind after that, the pair is shown as it is and the mismatch stands.
     */
    val shownHistory: StateFlow<ShownHistory> = channelFlow {
        var agreed: ServerChainHead? = null
        combine(readings, serverHead, ::Pair).collectLatest { (events, head) ->
            if (head != null && events.size < head.count) {
                send(ShownHistory(events, agreed?.takeIf { it.count <= events.size }))
                delay(CATCH_UP_MS)
            } else {
                agreed = head
            }
            send(ShownHistory(events, head))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), ShownHistory())

    val tripAlerts: StateFlow<List<Alert>> = perShipment(emptyList(), alerts::getAllAlerts)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), emptyList())

    /** A handover already recorded for this shipment, which the screen must show rather than replace. */
    val storedHandover: StateFlow<Handover?> = perShipment(null, handovers::getHandover)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), null)

    /** With no signal the server can't say who holds custody; the phone running the logger stands in. */
    val monitorOnly: StateFlow<Boolean> = simulator.isMonitorOnly

    private val serverRefresh = MutableStateFlow(0)

    init {
        // This ViewModel outlives a tab switch, so another shipment starts from nothing: no result, notice or
        // server view of the last one, and its inspection answers only if they were given for it.
        viewModelScope.launch {
            simulator.activeShipment.collect { s ->
                _state.update { current ->
                    if (s?.id == current.shipment?.id) current.copy(shipment = s)
                    else freshFor(s).copy(me = current.me)
                }
            }
        }
        viewModelScope.launch {
            account.state.collect { s -> _state.update { it.copy(me = s as? AccountState.Ready) } }
        }
    }

    /** Fetches what the screen shows from the server, until the caller is cancelled. */
    suspend fun runWhileVisible(): Unit = coroutineScope {
        // The history, again and again: the courier keeps recording until custody moves, so the verdict on screen must
        // keep up with what reaches the server. Every 3 s while readings arrive, less often while none do, up to every
        // 30 s (finding 11); confirming fetches it again first anyway. One kept on this phone alone has nothing to fetch.
        launch {
            simulator.activeShipment.map { s -> s?.let { it.id to (it.genesisHash != null) } }
                .distinctUntilChanged()
                .collectLatest { opened ->
                    val (id, onServer) = opened ?: return@collectLatest
                    if (!onServer) {
                        history.markReady(id)
                        // Debug local mode: the check against this phone's virtual logger, again as readings change.
                        telemetry.getTemperatures(id).collect {
                            simulator.activeShipment.value?.takeIf { it.id == id }?.let { shipment ->
                                val logger = localLoggerCheck(shipment)
                                _state.update { it.copy(loggerCheck = logger) }
                            }
                        }
                        return@collectLatest
                    }
                    val backoff = PollBackoff()
                    while (true) {
                        val fetched = history.hydrateHistory(id)
                        if (fetched.isSuccess) _state.update { it.copy(checkedAt = System.currentTimeMillis()) }
                        val arrived = fetched.getOrNull() ?: 0
                        delay(backoff.after(changed = arrived > 0))
                    }
                }
        }
        // A confirmation is held against the head it was made on. New readings arriving before the other person
        // confirms leave it behind, and only this person can confirm again.
        launch {
            combine(serverHead, _state) { head, s -> head?.headHash to s.confirmedHead }
                .distinctUntilChanged()
                .collect { (head, confirmed) ->
                    if (head != null && confirmed != null && head != confirmed) {
                        _state.update { it.copy(confirmedHead = null, notice = OVERTAKEN_NOTICE) }
                    }
                }
        }
        // Who holds it and who is on it, again whenever the readings or a confirmation change.
        launch {
            val readingsHere = perShipment(emptyList(), telemetry::getTemperatures)
            // The refresh count is part of the key: without it a confirmation or Refresh was filtered out as "nothing
            // changed" and the view waited for the next reading (found by Gate 4's mutant run).
            combine(_state.map { it.shipment?.id to it.onServer }.distinctUntilChanged(), readingsHere, serverRefresh) { key, events, tick ->
                Triple(key, events, tick)
            }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second.size == b.second.size && a.third == b.third }
                .collect { (key, events, _) -> if (key.second) loadServerView(key.first!!, events) }
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
        saveInspection(vvmStage = stage)
        _state.update { it.copy(vvmStage = stage) }
    }

    fun setShakeTest(result: ShakeTestResult) {
        saveInspection(shakeTest = result)
        _state.update { it.copy(shakeTest = result) }
    }

    /** The answers are kept with the shipment they were given for, so they can't be restored onto another. */
    private fun saveInspection(
        vvmStage: VvmStage = _state.value.vvmStage,
        shakeTest: ShakeTestResult = _state.value.shakeTest
    ) {
        saved[KEY_INSPECTED] = _state.value.shipment?.id
        saved[KEY_VVM] = vvmStage
        saved[KEY_SHAKE] = shakeTest
    }

    private fun freshFor(shipment: Shipment?): HandoverUiState {
        val mine = saved.get<String>(KEY_INSPECTED) == shipment?.id
        return HandoverUiState(
            shipment = shipment,
            vvmStage = (if (mine) saved.get<VvmStage>(KEY_VVM) else null) ?: VvmStage.NOT_RECORDED,
            shakeTest = (if (mine) saved.get<ShakeTestResult>(KEY_SHAKE) else null) ?: ShakeTestResult.NOT_DONE
        )
    }

    /**
     * The custodian names who takes over. Recording carries on: the receiver's verdict covers readings up to their own
     * confirmation, so the logger runs until custody moves, and this phone confirms again on each new head
     * (`ServerHandover.renewRelease`).
     */
    fun release(toUserId: String): Job = action(clearNotice = false) { shipment ->
        serverHandover.release(shipment.id, toUserId)
            .onSuccess { outcome ->
                val notice = if (outcome.state == HandoverState.WAITING) RELEASED_NOTICE else describeOutcome(outcome)
                _state.update { it.copy(notice = notice) }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
        serverRefresh.update { it + 1 }
    }

    fun refresh(): Job = viewModelScope.launch {
        val shipment = _state.value.shipment ?: return@launch
        loggerChecked = null
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
                    // The coordinator's head is the one the confirmation was just made on; the screen's copy can lag.
                    val waitingOn = (result as? HandoverResult.Confirmed)
                        ?.takeIf { it.outcome.state == HandoverState.WAITING }
                        ?.let { history.getServerHead(shipment.id).value?.headHash }
                    _state.update { it.copy(notice = notice, confirmedHead = waitingOn) }
                    serverRefresh.update { it + 1 }
                    // A courier taking over from another courier (5.3) records from now on: the one before stops as
                    // soon as their phone sees custody move. The history was fetched just before confirming. Their
                    // role on this shipment decides, not their profile's: a receiver's FAIL leaves it QUARANTINED,
                    // which counts as on the move (Gate 5 review S6).
                    val taken = (result as? HandoverResult.Confirmed)?.outcome
                    if (taken?.state == HandoverState.TRANSFERRED && taken.status in CourierRecorder.ON_THE_MOVE &&
                        roleOnShipment(shipment.id, current.me?.userId) == MemberRole.COURIER
                    ) {
                        recorder.start(shipment.copy(status = taken.status))
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        } else {
            // The same check the screen shows is what the handover records.
            val logger = localLoggerCheck(shipment)
            _state.update { it.copy(loggerCheck = logger) }
            orchestrator.processHandover(
                shipmentId = shipment.id,
                workerSigned = true,
                pharmacistSigned = true,
                declaredVerdict = verdict,
                reason = reason,
                vvmStage = current.vvmStage,
                shakeTest = current.shakeTest,
                historySynced = syncState.value == HistorySyncState.HISTORY_READY,
                loggerCheck = logger.outcome
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

    /** [userId]'s role on the shipment as the server has it; the screen's copy of the members is used without an answer. */
    private suspend fun roleOnShipment(shipmentId: String, userId: String?): MemberRole? {
        if (userId == null) return null
        val members = remote.getMembers(shipmentId).getOrNull() ?: _state.value.members
        return members.firstOrNull { it.userId == userId }?.role
    }

    private suspend fun loadServerView(shipmentId: String, events: List<TemperatureEvent>) {
        val custody = remote.getCustody(shipmentId).getOrNull()
        val members = remote.getMembers(shipmentId).getOrDefault(emptyList())
        val issues = remote.getBatches(shipmentId).getOrNull()?.let { UploadAudit.check(shipmentId, it, events) }
        _state.update { it.copy(custody = custody, members = members, uploadIssues = issues) }
        // The logger is read again only for a new head or when the person asks (Gate 4 review G4-7): a real one is a
        // Bluetooth exchange, not something to repeat on each look at the server. Recording reads it afresh anyway.
        val head = shipmentId to events.maxByOrNull { it.seq }?.currentHash
        val logger = loggerChecked?.takeIf { it.first == head }?.second
            ?: serverHandover.loggerCheck(shipmentId).also { loggerChecked = head to it }
        _state.update { if (it.shipment?.id == shipmentId) it.copy(loggerCheck = logger) else it }
    }

    /** The last logger check on a server shipment and the shipment and newest reading it was made against. */
    @Volatile
    private var loggerChecked: Pair<Pair<String, String?>, LoggerCheckResult>? = null

    companion object {
        /** How long a new server head waits for the readings saved with it to reach the screen. */
        const val CATCH_UP_MS = 2_000L
        const val RELEASED_NOTICE = "Confirmed. This phone keeps recording until custody moves. Waiting for the " +
            "other person to confirm on their own phone."
        const val OVERTAKEN_NOTICE = "New readings arrived after you confirmed, so your confirmation no longer " +
            "matches the history. Check them and confirm again."
        private const val STOP_SHARING_MS = 5_000L
        private const val KEY_VVM = "vvmStage"
        private const val KEY_SHAKE = "shakeTest"
        private const val KEY_INSPECTED = "inspectedShipmentId"
    }
}

internal fun describeOutcome(outcome: HandoverOutcome): String = when (outcome.state) {
    HandoverState.TRANSFERRED -> "Custody has moved. The shipment is now ${outcome.status.name}."
    HandoverState.WAITING -> "Confirmed. Waiting for the other person to confirm on their own phone."
}
