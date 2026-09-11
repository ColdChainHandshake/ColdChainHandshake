package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineManagerTest {

    private val testShipment = Shipment(
        id = "SHIP-100",
        qrCode = "QR-100",
        loggerId = "LOG-100",
        origin = "Central Warehouse",
        destination = "Metro Hospital",
        workerId = "WRK-01",
        status = ShipmentStatus.IN_TRANSIT
    )

    @Test
    fun shouldQuarantineIsTrueOnlyWhenBreachOccurs() {
        val safeEvaluation = BreachEvaluation(
            hasBreach = false,
            cumulativeBreachDurationMs = 299_000L,
            temperaturePassed = true
        )
        assertFalse(QuarantineManager.shouldQuarantine(safeEvaluation))

        val breachEvaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 301_000L,
            temperaturePassed = false
        )
        assertTrue(QuarantineManager.shouldQuarantine(breachEvaluation))
    }

    @Test
    fun applyQuarantineTransitionsInTransitShipmentToQuarantined() {
        val breachEvaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 350_000L,
            temperaturePassed = false
        )

        val updated = QuarantineManager.applyQuarantine(testShipment, breachEvaluation)
        assertEquals(ShipmentStatus.QUARANTINED, updated.status)
        assertEquals(testShipment.id, updated.id)
    }

    @Test
    fun applyQuarantinePreservesStatusWhenSafe() {
        val safeEvaluation = BreachEvaluation(
            hasBreach = false,
            cumulativeBreachDurationMs = 100_000L,
            temperaturePassed = true
        )

        val updated = QuarantineManager.applyQuarantine(testShipment, safeEvaluation)
        assertEquals(ShipmentStatus.IN_TRANSIT, updated.status)
    }

    @Test
    fun applyQuarantineDoesNotRevertReplacementRequested() {
        val replacementShipment = testShipment.copy(status = ShipmentStatus.REPLACEMENT_REQUESTED)
        val breachEvaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 400_000L,
            temperaturePassed = false
        )

        val updated = QuarantineManager.applyQuarantine(replacementShipment, breachEvaluation)
        assertEquals(ShipmentStatus.REPLACEMENT_REQUESTED, updated.status)
    }

    @Test
    fun applyQuarantineDoesNotAlterAlreadyQuarantinedShipment() {
        val quarantinedShipment = testShipment.copy(status = ShipmentStatus.QUARANTINED)
        val breachEvaluation = BreachEvaluation(
            hasBreach = true,
            cumulativeBreachDurationMs = 400_000L,
            temperaturePassed = false
        )

        val updated = QuarantineManager.applyQuarantine(quarantinedShipment, breachEvaluation)
        assertEquals(ShipmentStatus.QUARANTINED, updated.status)
    }
}
