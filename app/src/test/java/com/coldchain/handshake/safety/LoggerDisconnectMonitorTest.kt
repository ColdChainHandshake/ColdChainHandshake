package com.coldchain.handshake.safety

import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerDisconnectMonitorTest {

    @Test
    fun onLoggerDisconnectedDirectlyCreatesWorkerAlert() {
        val timestamp = 1_700_000_000_000L
        val alert = LoggerDisconnectMonitor.onLoggerDisconnected(
            shipmentId = "SHIP-005",
            loggerId = "LOG-005",
            timestamp = timestamp
        )

        assertEquals(AlertType.LOGGER_DISCONNECT, alert.type)
        assertEquals("SHIP-005", alert.shipmentId)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertFalse("Initial disconnect alert must be unacknowledged", alert.acknowledged)
        assertEquals(timestamp, alert.timestamp)
        assertTrue(alert.message.contains("LOG-005"))
        assertTrue(alert.message.contains("Sensor connection dropped"))
    }

    @Test
    fun onLoggerDisconnectedWithCustomReason() {
        val alert = LoggerDisconnectMonitor.onLoggerDisconnected(
            shipmentId = "SHIP-006",
            loggerId = "LOG-006",
            reason = "BLE link loss in transit",
            timestamp = 1_700_000_000_123L
        )

        assertEquals(AlertType.LOGGER_DISCONNECT, alert.type)
        assertEquals("SHIP-006", alert.shipmentId)
        assertEquals(EscalationLevel.WORKER, alert.escalationLevel)
        assertTrue(alert.message.contains("BLE link loss in transit"))
        assertTrue(alert.message.contains("LOG-006"))
    }

    @Test
    fun loggerDisconnectAlertEscalatesDeterministically() {
        val initialAlert = LoggerDisconnectMonitor.onLoggerDisconnected(
            shipmentId = "SHIP-007",
            loggerId = "LOG-007"
        )
        assertEquals(EscalationLevel.WORKER, initialAlert.escalationLevel)

        val supervisorAlert = AlertEscalationEngine.escalateAlert(initialAlert)
        assertEquals(EscalationLevel.SUPERVISOR, supervisorAlert.escalationLevel)

        val pharmacistAlert = AlertEscalationEngine.escalateAlert(supervisorAlert)
        assertEquals(EscalationLevel.PHARMACIST, pharmacistAlert.escalationLevel)

        val terminalAlert = AlertEscalationEngine.escalateAlert(pharmacistAlert)
        assertEquals(EscalationLevel.PHARMACIST, terminalAlert.escalationLevel)
    }

    @Test
    fun loggerDisconnectAlertAcknowledgment() {
        val alert = LoggerDisconnectMonitor.onLoggerDisconnected(
            shipmentId = "SHIP-008",
            loggerId = "LOG-008"
        )
        assertFalse(alert.acknowledged)

        val acknowledged = AlertEscalationEngine.acknowledgeAlert(alert)
        assertTrue(acknowledged.acknowledged)
        assertEquals(alert.id, acknowledged.id)
    }
}
