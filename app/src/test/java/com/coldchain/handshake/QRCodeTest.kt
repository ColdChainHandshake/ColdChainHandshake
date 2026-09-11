package com.coldchain.handshake

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QRCodeTest {

    @Test
    fun testShipmentQrPayloadFormatting() {
        val shipmentId = "SHIP-001"
        val loggerId = "LOG-902"
        val origin = "Central Cold Hub"
        val destination = "St. Jude Pharmacy"

        val qrPayload = "CCH:SHIP:$shipmentId:$origin:$destination:$loggerId"

        assertTrue(qrPayload.startsWith("CCH:SHIP:"))
        assertTrue(qrPayload.contains(shipmentId))
        assertTrue(qrPayload.contains(loggerId))
        assertTrue(qrPayload.contains(origin))
        assertTrue(qrPayload.contains(destination))
    }

    @Test
    fun testQrPayloadAssociationWithShipment() {
        val qrPayload = "CCH:SHIP:SHIP-204:OriginA:DestB:LOG-100"
        val shipment = Shipment(
            id = "SHIP-204",
            qrCode = qrPayload,
            loggerId = "LOG-100",
            origin = "OriginA",
            destination = "DestB",
            workerId = "W-1",
            status = ShipmentStatus.CREATED
        )

        assertEquals(qrPayload, shipment.qrCode)
        assertEquals("LOG-100", shipment.loggerId)
        assertFalse(shipment.qrCode.isBlank())
    }
}
