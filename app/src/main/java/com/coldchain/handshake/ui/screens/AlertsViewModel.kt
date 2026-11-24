package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.simulator.TemperatureSimulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 * @property filter Which of the shipment's alerts are shown; kept through process death.
 * @property feedback What the last acknowledge or escalate did.
 */
data class AlertsUiState(val filter: AlertFilter = AlertFilter.ALL, val feedback: String? = null)

/** The Alerts screen: the alerts of the shipment on screen, and nothing from any other shipment. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val simulator: TemperatureSimulator,
    private val alerts: AlertRepository,
    private val safety: SafetyEngine,
    private val saved: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(AlertsUiState(filter = saved[KEY_FILTER] ?: AlertFilter.ALL))
    val state: StateFlow<AlertsUiState> = _state.asStateFlow()

    val shipment: StateFlow<Shipment?> = simulator.activeShipment

    /** Every alert of the shipment on screen. */
    val all: StateFlow<List<Alert>> = simulator.activeShipment.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else alerts.getAllAlerts(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), emptyList())

    /** The ones the filter lets through. */
    val shown: StateFlow<List<Alert>> = combine(all, _state.map { it.filter }.distinctUntilChanged()) { list, filter ->
        when (filter) {
            AlertFilter.ALL -> list
            AlertFilter.UNACKNOWLEDGED -> list.filter { !it.acknowledged }
            AlertFilter.ACKNOWLEDGED -> list.filter { it.acknowledged }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_SHARING_MS), emptyList())

    fun setFilter(filter: AlertFilter) {
        saved[KEY_FILTER] = filter
        _state.update { it.copy(filter = filter) }
    }

    fun acknowledge(alert: Alert): Job = say {
        safety.acknowledgeAlert(alert)
        "Acknowledged alert ${alert.id.take(8)}"
    }

    fun escalate(alert: Alert): Job = say {
        "Escalated alert to ${safety.escalateAlert(alert).escalationLevel}"
    }

    private fun say(block: suspend () -> String): Job = viewModelScope.launch {
        val message = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
        _state.update { it.copy(feedback = message) }
    }

    private companion object {
        const val STOP_SHARING_MS = 5_000L
        const val KEY_FILTER = "filter"
    }
}
