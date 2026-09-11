package com.coldchain.handshake.safety

import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEngineTest {

    private val engine = SafetyEngine()

    private fun createEvent(
        id: String,
        timestamp: Long,
        temperature: Double,
        shipmentId: String = "SHIP-200"
    ): TemperatureEvent {
        return TemperatureEvent(
            id = id,
            shipmentId = shipmentId,
            loggerId = "LOG-200",
            timestamp = timestamp,
            temperature = temperature,
            previousHash = "00",
            currentHash = "aa",
            syncStatus = SyncStatus.SYNCED
        )
    }

    @Test
    fun endToEndEvaluationSafeShipment() {
        val shipment = Shipment(
            id = "SHIP-200",
            qrCode = "QR-200",
            loggerId = "LOG-200",
            origin = "Origin Hub",
            destination = "Dest Clinic",
            workerId = "W1",
            status = ShipmentStatus.IN_TRANSIT
        )

        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 4.5),
            createEvent("e2", baseTime + 60_000L, 5.0),
            createEvent("e3", baseTime + 120_000L, 4.8)
        )

        val decision = engine.evaluateShipment(shipment, events)

        assertFalse(decision.evaluation.hasBreach)
        assertTrue(decision.evaluation.temperaturePassed)
        assertFalse(decision.shouldQuarantine)
        assertEquals("00:00", decision.breachDurationFormatted)

        val handoverSafety = engine.evaluateHandoverTemperatureSafety(events)
        assertTrue("Handover temperature check must pass", handoverSafety)

        val alert = engine.createBreachAlertIfViolated(shipment, events)
        assertNull("No alert should be created for safe shipment", alert)

        val updatedShipment = engine.applyQuarantineIfBreached(shipment, events)
        assertEquals(ShipmentStatus.IN_TRANSIT, updatedShipment.status)
    }

    @Test
    fun endToEndEvaluationBreachedShipment() = runBlocking {
        val shipment = Shipment(
            id = "SHIP-201",
            qrCode = "QR-201",
            loggerId = "LOG-201",
            origin = "Origin Hub",
            destination = "Dest Clinic",
            workerId = "W1",
            status = ShipmentStatus.IN_TRANSIT
        )

        val baseTime = 1_700_000_000_000L
        val events = listOf(
            createEvent("e1", baseTime, 9.5),
            createEvent("e2", baseTime + 301_000L, 5.0)
        )

        val decision = engine.evaluateShipment(shipment, events)

        assertTrue(decision.evaluation.hasBreach)
        assertFalse(decision.evaluation.temperaturePassed)
        assertTrue(decision.shouldQuarantine)
        assertEquals("05:01", decision.breachDurationFormatted)

        val handoverSafety = engine.evaluateHandoverTemperatureSafety(events)
        assertFalse("Handover temperature check must fail on breach", handoverSafety)

        val alert = engine.createBreachAlertIfViolated(shipment, events)
        assertNotNull("Alert must be created for breached shipment", alert)
        assertEquals(EscalationLevel.WORKER, alert!!.escalationLevel)

        // Test escalation progression through enforcing engine with AlertRepository
        val fakeAlertRepo = FakeAlertRepository()
        val enforcingEngine = SafetyEngine(alertRepository = fakeAlertRepo)
        val escalated = enforcingEngine.escalateAlert(alert)
        assertEquals(EscalationLevel.SUPERVISOR, escalated.escalationLevel)

        val acknowledged = enforcingEngine.acknowledgeAlert(escalated)
        assertTrue(acknowledged.acknowledged)

        val updatedShipment = engine.applyQuarantineIfBreached(shipment, events)
        assertEquals(ShipmentStatus.QUARANTINED, updatedShipment.status)
    }
}
