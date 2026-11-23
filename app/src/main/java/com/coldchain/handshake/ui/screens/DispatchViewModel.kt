package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.ShipmentDraft
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.simulator.TemperatureSimulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** What the sender has typed into the new-shipment form. */
data class DispatchForm(
    val productProfileId: String,
    val origin: String,
    val destination: String,
    val workerId: String,
    val loggerId: String
)

/**
 * @property shipment The shipment on screen, if any.
 * @property banner The last thing that happened, for the status line.
 * @property signedIn False in debug local mode, where nothing reaches the server.
 */
data class DispatchUiState(
    val shipment: Shipment? = null,
    val banner: String? = null,
    val signedIn: Boolean = false,
    val boundQrCode: String? = null
)

sealed interface DispatchEvent {
    data object OpenTransit : DispatchEvent
}

/** What a scanned code did. A code only ever names a shipment or a logger; nothing is built from its text. */
sealed interface ScanOutcome {
    data object Opened : ScanOutcome
    data object Associated : ScanOutcome
    data class FillForm(val loggerId: String? = null, val origin: String? = null, val destination: String? = null) : ScanOutcome
}

/**
 * The sender's Dispatch screen: create a shipment, name its courier and receiver, dispatch it. In debug local mode
 * it also starts the journey on this phone. The shipment on screen is kept by ID in [SavedStateHandle], so a
 * rotation or a killed process doesn't lose a shipment just created.
 */
@HiltViewModel
class DispatchViewModel @Inject constructor(
    private val account: AccountRepository,
    private val shipments: ShipmentRepository,
    private val workflow: ShipmentWorkflow,
    private val simulator: TemperatureSimulator,
    private val history: ShipmentHistorySyncCoordinator,
    private val saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(DispatchUiState(signedIn = account.state.value is AccountState.Ready))
    val state: StateFlow<DispatchUiState> = _state.asStateFlow()

    private val _events = Channel<DispatchEvent>(Channel.BUFFERED)
    val events: Flow<DispatchEvent> = _events.receiveAsFlow()

    /** The scanned code to bind to the next shipment created on this phone. */
    private var boundQrCode: String?
        get() = _state.value.boundQrCode
        set(value) = _state.update { it.copy(boundQrCode = value) }

    private val signedIn: Boolean get() = account.state.value is AccountState.Ready

    init {
        viewModelScope.launch { account.state.collect { s -> _state.update { it.copy(signedIn = s is AccountState.Ready) } } }
        val savedId = saved.get<String>(KEY_SHIPMENT_ID)
        if (savedId != null) {
            viewModelScope.launch { shipments.getShipment(savedId).first()?.let(::show) }
        } else {
            simulator.activeShipment.value?.let(::show)
        }
    }

    fun create(form: DispatchForm, courierEmail: String, receiverEmail: String): Job = viewModelScope.launch {
        val shipmentId = "SHIP-" + UUID.randomUUID().toString().take(6).uppercase()
        if (signedIn) {
            val draft = ShipmentDraft(shipmentId, form.productProfileId, form.loggerId.trim(), form.origin.trim(), form.destination.trim())
            workflow.create(draft)
                .onSuccess { created ->
                    show(created)
                    banner(
                        workflow.addPeople(created.id, courierEmail, receiverEmail).fold(
                            { "Created ${created.id}. The courier and receiver can open it now." },
                            { "Created ${created.id}, but people weren't added: ${it.message}" }
                        )
                    )
                }
                .onFailure { banner("Couldn't create the shipment: ${it.message}") }
            return@launch
        }

        // Local mode only: this phone's own shipment, which no server knows.
        val qrCode = boundQrCode ?: "CCH:SHIP:$shipmentId:${form.origin}:${form.destination}:${form.loggerId}"
        val newShipment = Shipment(
            id = shipmentId,
            qrCode = qrCode,
            loggerId = form.loggerId.trim(),
            origin = form.origin.trim(),
            destination = form.destination.trim(),
            workerId = form.workerId.trim(),
            status = ShipmentStatus.CREATED,
            productProfileId = form.productProfileId
        )
        show(newShipment)
        val stored = shipments.saveShipment(newShipment)
        if (stored.isFailure) {
            show(null)
            banner("Couldn't save: ${stored.exceptionOrNull()?.message}")
            return@launch
        }
        simulator.attachShipment(newShipment)
        banner("Created $shipmentId on this phone only")
    }

    fun addPeople(courierEmail: String, receiverEmail: String): Job = viewModelScope.launch {
        val shipment = _state.value.shipment ?: return@launch
        banner(
            workflow.addPeople(shipment.id, courierEmail, receiverEmail)
                .fold({ "People added to ${shipment.id}" }, { "Couldn't add people: ${it.message}" })
        )
    }

    fun dispatch(): Job = viewModelScope.launch {
        val shipment = _state.value.shipment ?: return@launch
        if (shipment.genesisHash != null) {
            workflow.dispatch(shipment.id)
                .onSuccess {
                    show(it)
                    banner("${it.id} dispatched. The courier takes custody on their own phone.")
                }
                .onFailure { banner("Couldn't dispatch: ${it.message}") }
            return@launch
        }
        val dispatched = shipment.copy(status = ShipmentStatus.DISPATCHED)
        if (saveOrRevert(dispatched, shipment)) {
            simulator.attachShipment(dispatched)
            banner("Consignment ${dispatched.id} DISPATCHED and sealed")
        }
    }

    /** Local mode only: the journey starts on this phone, which records the readings itself. */
    fun startLocalJourney(): Job = viewModelScope.launch {
        val shipment = _state.value.shipment ?: return@launch
        if (shipment.genesisHash != null) return@launch
        val inTransit = shipment.copy(status = ShipmentStatus.IN_TRANSIT, journeyStartedAt = System.currentTimeMillis())
        if (!saveOrRevert(inTransit, shipment)) return@launch
        simulator.attachShipment(inTransit)
        history.markReady(inTransit.id)
        simulator.startContinuousSimulation(intervalMs = 1500L, simulatedStepSeconds = 60L)
        _events.send(DispatchEvent.OpenTransit)
    }

    suspend fun onCodeScanned(code: String): ScanOutcome {
        val targetId = if (code.startsWith("CCH:SHIP:")) code.split(":").getOrNull(2) ?: code else code

        // A code names a shipment this phone holds, or else one the server lets this person open.
        var matched = shipments.getAllShipments().first().firstOrNull { it.qrCode == code || it.id == code || it.id == targetId }
        if (matched == null && signedIn) {
            matched = workflow.open(targetId).onFailure { banner(it.message) }.getOrNull()
        }

        val current = _state.value.shipment
        return when {
            matched != null -> {
                show(matched)
                boundQrCode = matched.qrCode
                simulator.attachShipmentForMonitoring(matched)
                viewModelScope.launch {
                    if (matched.genesisHash != null) history.hydrateHistory(matched.id) else history.markReady(matched.id)
                }
                banner("Identified consignment ${matched.id} via QR (Monitor Mode)")
                ScanOutcome.Opened
            }
            current != null && current.status == ShipmentStatus.CREATED && current.genesisHash == null -> {
                val updated = if (code.startsWith("LOG-")) current.copy(loggerId = code) else current.copy(qrCode = code)
                show(updated)
                boundQrCode = updated.qrCode
                shipments.saveShipment(updated).onFailure { banner("Couldn't save: ${it.message}") }
                simulator.attachShipment(updated)
                banner("Associated QR payload with consignment ${updated.id}")
                ScanOutcome.Associated
            }
            else -> {
                boundQrCode = code
                when {
                    code.startsWith("LOG-") -> {
                        banner("Paired Logger $code via QR scan")
                        ScanOutcome.FillForm(loggerId = code)
                    }
                    code.startsWith("CCH:SHIP:") -> {
                        banner("Bound scanned manifest QR to new consignment")
                        val parts = code.split(":")
                        if (parts.size >= 6) ScanOutcome.FillForm(loggerId = parts[5], origin = parts[3], destination = parts[4])
                        else ScanOutcome.FillForm()
                    }
                    else -> {
                        banner("Bound scanned QR identifier to new consignment")
                        ScanOutcome.FillForm()
                    }
                }
            }
        }
    }

    /** The demo preset replaces the form, so a code scanned for the old one no longer applies. */
    fun forgetScannedCode() {
        boundQrCode = null
    }

    fun reset() {
        simulator.reset()
        show(null)
        boundQrCode = null
        banner("Reset consignment configuration")
    }

    fun dismissBanner() = banner(null)

    private fun show(shipment: Shipment?) {
        _state.update { it.copy(shipment = shipment) }
        saved[KEY_SHIPMENT_ID] = shipment?.id
    }

    private fun banner(message: String?) = _state.update { it.copy(banner = message) }

    /** Shows [next] and stores it; if storing fails, shows [previous] again and says why. */
    private suspend fun saveOrRevert(next: Shipment, previous: Shipment): Boolean {
        show(next)
        val stored = shipments.saveShipment(next)
        if (stored.isFailure) {
            show(previous)
            banner("Couldn't save: ${stored.exceptionOrNull()?.message}")
        }
        return stored.isSuccess
    }

    private companion object {
        const val KEY_SHIPMENT_ID = "shipmentId"
    }
}
