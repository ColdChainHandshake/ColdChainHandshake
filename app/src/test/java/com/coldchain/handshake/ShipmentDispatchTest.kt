package com.coldchain.handshake

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.impl.InMemoryShipmentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShipmentDispatchTest {

    private lateinit var shipmentRepository: InMemoryShipmentRepository

    @Before
    fun setUp() {
        shipmentRepository = InMemoryShipmentRepository()
    }

    @Test
    fun testShipmentCreationAndSave() = runTest {
        val shipment = Shipment(
            id = "SHIP-TEST-001",
            qrCode = "CCH:SHIP:SHIP-TEST-001:LOG-902",
            loggerId = "LOG-902",
            origin = "Central Cold Hub",
            destination = "St. Jude Pharmacy",
            workerId = "W-14",
            status = ShipmentStatus.CREATED
        )

        val saveResult = shipmentRepository.saveShipment(shipment)
        assertTrue("Save shipment should succeed", saveResult.isSuccess)

        val retrieved = shipmentRepository.getShipment("SHIP-TEST-001").first()
        assertNotNull("Retrieved shipment should not be null", retrieved)
        assertEquals("SHIP-TEST-001", retrieved?.id)
        assertEquals("CCH:SHIP:SHIP-TEST-001:LOG-902", retrieved?.qrCode)
        assertEquals("LOG-902", retrieved?.loggerId)
        assertEquals("Central Cold Hub", retrieved?.origin)
        assertEquals("St. Jude Pharmacy", retrieved?.destination)
        assertEquals("W-14", retrieved?.workerId)
        assertEquals(ShipmentStatus.CREATED, retrieved?.status)
    }

    @Test
    fun testShipmentStatusTransitionToDispatchedAndInTransit() = runTest {
        val initialShipment = Shipment(
            id = "SHIP-TEST-002",
            qrCode = "CCH:SHIP:SHIP-TEST-002:LOG-501",
            loggerId = "LOG-501",
            origin = "Airport Cold Warehouse",
            destination = "Metro Hospital",
            workerId = "W-22",
            status = ShipmentStatus.CREATED
        )
        shipmentRepository.saveShipment(initialShipment)

        // Transition: CREATED -> DISPATCHED
        val dispatchedShipment = initialShipment.copy(status = ShipmentStatus.DISPATCHED)
        shipmentRepository.saveShipment(dispatchedShipment)

        var current = shipmentRepository.getShipment("SHIP-TEST-002").first()
        assertEquals(ShipmentStatus.DISPATCHED, current?.status)

        // Transition: DISPATCHED -> IN_TRANSIT
        val inTransitShipment = dispatchedShipment.copy(status = ShipmentStatus.IN_TRANSIT)
        shipmentRepository.saveShipment(inTransitShipment)

        current = shipmentRepository.getShipment("SHIP-TEST-002").first()
        assertEquals(ShipmentStatus.IN_TRANSIT, current?.status)
        assertEquals("LOG-501", current?.loggerId)
    }

    @Test
    fun testGetAllShipments() = runTest {
        val s1 = Shipment("S1", "QR1", "L1", "Origin1", "Dest1", "W1", ShipmentStatus.CREATED)
        val s2 = Shipment("S2", "QR2", "L2", "Origin2", "Dest2", "W2", ShipmentStatus.DISPATCHED)

        shipmentRepository.saveShipment(s1)
        shipmentRepository.saveShipment(s2)

        val all = shipmentRepository.getAllShipments().first()
        assertEquals(2, all.size)
    }
}
