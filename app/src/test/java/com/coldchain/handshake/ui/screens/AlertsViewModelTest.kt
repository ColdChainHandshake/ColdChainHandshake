package com.coldchain.handshake.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.safety.FakeAlertRepository
import com.coldchain.handshake.safety.FakeShipmentRepository
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Alerts screen without the screen: one shipment's alerts, the filter, acknowledging and escalating. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModelTest {

    private val alerts = FakeAlertRepository()
    private val safety = SafetyEngine(alerts, FakeShipmentRepository())
    private val simulator = TemperatureSimulator(InMemoryTelemetryRepository(), dispatcher = Dispatchers.Default)
    private val shipment = Shipment("SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id)

    private fun alert(id: String, shipmentId: String = "SHIP-1", acknowledged: Boolean = false) = Alert(
        id = id,
        shipmentId = shipmentId,
        type = AlertType.TEMPERATURE_BREACH,
        message = "Reached 11.0°C",
        timestamp = 1_700_000_000_000,
        escalationLevel = EscalationLevel.WORKER,
        acknowledged = acknowledged
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = AlertsViewModel(simulator, alerts, safety, saved)

    @Test
    fun onlyTheShipmentOnScreenHasItsAlertsShown() = runTest {
        alerts.saveAlert(alert("A-1"))
        alerts.saveAlert(alert("A-2", shipmentId = "SHIP-OTHER"))
        simulator.attachShipmentForMonitoring(shipment)
        val vm = viewModel()
        backgroundScope.launch { vm.shown.collect {} }

        assertEquals(listOf("A-1"), vm.shown.first { it.isNotEmpty() }.map { it.id })
    }

    @Test
    fun theFilterShowsOpenOrAcknowledgedAlertsAndSurvivesProcessDeath() = runTest {
        alerts.saveAlert(alert("A-OPEN"))
        alerts.saveAlert(alert("A-DONE", acknowledged = true))
        simulator.attachShipmentForMonitoring(shipment)
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        backgroundScope.launch { vm.shown.collect {} }

        vm.setFilter(AlertFilter.UNACKNOWLEDGED)
        assertEquals(listOf("A-OPEN"), vm.shown.first { it.size == 1 }.map { it.id })

        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        backgroundScope.launch { restored.shown.collect {} }
        assertEquals(AlertFilter.UNACKNOWLEDGED, restored.state.value.filter)
        assertEquals(listOf("A-OPEN"), restored.shown.first { it.size == 1 }.map { it.id })
    }

    @Test
    fun acknowledgingIsStoredAndSaid() = runTest {
        alerts.saveAlert(alert("A-1234567890"))
        simulator.attachShipmentForMonitoring(shipment)
        val vm = viewModel()

        vm.acknowledge(alerts.savedAlerts.single()).join()

        assertTrue(alerts.savedAlerts.single().acknowledged)
        assertEquals("Acknowledged alert A-123456", vm.state.value.feedback)
    }

    @Test
    fun escalatingMovesItUpALevel() = runTest {
        alerts.saveAlert(alert("A-1"))
        simulator.attachShipmentForMonitoring(shipment)
        val vm = viewModel()

        vm.escalate(alerts.savedAlerts.single()).join()

        assertEquals(EscalationLevel.SUPERVISOR, alerts.savedAlerts.single().escalationLevel)
        assertEquals("Escalated alert to SUPERVISOR", vm.state.value.feedback)
    }

    @Test
    fun aFailedWriteIsSaid() = runTest {
        alerts.saveAlert(alert("A-1"))
        simulator.attachShipmentForMonitoring(shipment)
        val vm = viewModel()
        alerts.shouldFail = true

        vm.acknowledge(alerts.savedAlerts.single()).join()

        assertEquals("Error: Alert repository write failure", vm.state.value.feedback)
    }
}
