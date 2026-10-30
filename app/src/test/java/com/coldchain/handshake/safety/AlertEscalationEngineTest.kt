package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEscalationEngineTest {

    private fun breachAlert(hotMs: Long = 301_000L, temperature: Double = 9.5): Alert {
        val evaluation = BreachDetector.evaluateTelemetry(hotThenSafe(1_700_000_000_000L, hotMs, temperature))
        return AlertEscalationEngine.alertsFor("SHIP-001", evaluation, 1_700_000_000_000L).single()
    }

    @Test
    fun createBreachAlertInitializesAtWorkerTierUnacknowledged() {
        val alert = breachAlert(hotMs = 320_000L, temperature = 10.4)

        assertEquals("SHIP-001", alert.shipmentId)
        assertEquals(AlertType.TEMPERATURE_BREACH, alert.type)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse(alert.acknowledged)
        assertTrue(alert.message.contains("10.4°C"))
        assertTrue(alert.message.contains("05:20"))
    }

    @Test
    fun deterministicEscalationFollowsWorkerSupervisorPharmacistChain() {
        val initialAlert = breachAlert()
        assertEquals(EscalationLevel.WORKER, initialAlert.escalationLevel)

        // Step 1: Worker -> Supervisor
        val supervisorAlert = AlertEscalationEngine.escalateAlert(initialAlert)
        assertEquals(EscalationLevel.SUPERVISOR, supervisorAlert.escalationLevel)
        assertEquals(initialAlert.id, supervisorAlert.id)

        // Step 2: Supervisor -> Pharmacist
        val pharmacistAlert = AlertEscalationEngine.escalateAlert(supervisorAlert)
        assertEquals(EscalationLevel.PHARMACIST, pharmacistAlert.escalationLevel)

        // Step 3: Pharmacist is the terminal tier; remains Pharmacist
        val terminalAlert = AlertEscalationEngine.escalateAlert(pharmacistAlert)
        assertEquals(EscalationLevel.PHARMACIST, terminalAlert.escalationLevel)
    }

    @Test
    fun acknowledgeAlertUpdatesAcknowledgedField() {
        val alert = breachAlert()
        assertFalse(alert.acknowledged)

        val acknowledgedAlert = AlertEscalationEngine.acknowledgeAlert(alert)
        assertTrue(acknowledgedAlert.acknowledged)
        assertEquals(alert.id, acknowledgedAlert.id)
    }

    @Test
    fun createDisconnectAlertSetsCorrectProperties() {
        val alert = AlertEscalationEngine.createDisconnectAlert(
            shipmentId = "SHIP-002",
            loggerId = "LOG-999",
            reason = "Sensor unresponsive",
            timestamp = 1_700_000_000_000L
        )

        assertEquals(AlertType.LOGGER_DISCONNECT, alert.type)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse(alert.acknowledged)
        assertTrue(alert.message.contains("LOG-999"))
        assertTrue(alert.message.contains("Sensor unresponsive"))
    }

    @Test
    fun createOperationalWarningSetsCorrectProperties() {
        val alert = AlertEscalationEngine.createOperationalWarning(
            shipmentId = "SHIP-003",
            message = "Low battery warning"
        )

        assertEquals(AlertType.OPERATIONAL_WARNING, alert.type)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse(alert.acknowledged)
        assertEquals("Low battery warning", alert.message)
    }
}
