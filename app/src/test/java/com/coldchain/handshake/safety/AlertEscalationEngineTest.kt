package com.coldchain.handshake.safety

import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEscalationEngineTest {

    @Test
    fun createBreachAlertInitializesAtWorkerTierUnacknowledged() {
        val evaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 320_000L,
            temperaturePassed = false,
            maxExcursionTemperature = 10.4,
            excursionIntervalCount = 1
        )

        val alert = AlertEscalationEngine.createBreachAlert(
            shipmentId = "SHIP-001",
            breachEvaluation = evaluation,
            timestamp = 1_700_000_000_000L
        )

        assertEquals("SHIP-001", alert.shipmentId)
        assertEquals(AlertType.TEMPERATURE_BREACH, alert.type)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse(alert.acknowledged)
        assertTrue(alert.message.contains("10.4°C"))
        assertTrue(alert.message.contains("05:20"))
    }

    @Test
    fun deterministicEscalationFollowsWorkerSupervisorPharmacistChain() {
        val evaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 301_000L,
            temperaturePassed = false
        )
        val initialAlert = AlertEscalationEngine.createBreachAlert("SHIP-001", evaluation)
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
        val evaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 305_000L,
            temperaturePassed = false
        )
        val alert = AlertEscalationEngine.createBreachAlert("SHIP-001", evaluation)
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
