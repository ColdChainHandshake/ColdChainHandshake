package com.coldchain.handshake.data.local

import com.coldchain.handshake.data.local.converters.RoomTypeConverters
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoomMappingAndConverterTest {

    private val converters = RoomTypeConverters()

    @Test
    fun testShipmentStatusConverter() {
        for (status in ShipmentStatus.values()) {
            val stringVal = converters.fromShipmentStatus(status)
            val back = converters.toShipmentStatus(stringVal)
            assertEquals(status, back)
        }
        assertNull(converters.toShipmentStatus("NON_EXISTENT"))
        assertNull(converters.fromShipmentStatus(null))
        assertNull(converters.toShipmentStatus(null))
    }

    @Test
    fun testSyncStatusConverter() {
        for (status in SyncStatus.values()) {
            val stringVal = converters.fromSyncStatus(status)
            val back = converters.toSyncStatus(stringVal)
            assertEquals(status, back)
        }
        assertNull(converters.toSyncStatus("INVALID"))
    }

    @Test
    fun testAlertTypeConverter() {
        for (type in AlertType.values()) {
            val stringVal = converters.fromAlertType(type)
            val back = converters.toAlertType(stringVal)
            assertEquals(type, back)
        }
    }

    @Test
    fun testEscalationLevelConverter() {
        for (level in EscalationLevel.values()) {
            val stringVal = converters.fromEscalationLevel(level)
            val back = converters.toEscalationLevel(stringVal)
            assertEquals(level, back)
        }
    }

    @Test
    fun testHandoverVerdictConverter() {
        for (verdict in HandoverVerdict.values()) {
            val stringVal = converters.fromHandoverVerdict(verdict)
            val back = converters.toHandoverVerdict(stringVal)
            assertEquals(verdict, back)
        }
    }

    @Test
    fun testShipmentEntityMapping() {
        val domain = Shipment(
            id = "ship-001",
            qrCode = "QR_SHIP_001",
            loggerId = "LOG-99",
            origin = "Central Cold Storage",
            destination = "Regional Clinic A",
            workerId = "WRK-42",
            status = ShipmentStatus.DISPATCHED
        )
        val entity = domain.toEntity(SyncStatus.PENDING)
        assertEquals("ship-001", entity.id)
        assertEquals(SyncStatus.PENDING, entity.syncStatus)

        val restored = entity.toDomain()
        assertEquals(domain, restored)
    }

    @Test
    fun testTemperatureEventEntityMapping_preservesHashesAndTimestamps() {
        val event = TemperatureEvent(
            id = "evt-12345",
            shipmentId = "ship-001",
            loggerId = "LOG-99",
            timestamp = 1700000000000L,
            temperature = 4.5,
            previousHash = "0000000000000000000000000000000000000000000000000000000000000000",
            currentHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0",
            syncStatus = SyncStatus.PENDING
        )
        val entity = event.toEntity()
        assertEquals("evt-12345", entity.id)
        assertEquals(1700000000000L, entity.timestamp)
        assertEquals(4.5, entity.temperature, 0.0001)
        assertEquals(event.previousHash, entity.previousHash)
        assertEquals(event.currentHash, entity.currentHash)
        assertEquals(SyncStatus.PENDING, entity.syncStatus)

        val restored = entity.toDomain()
        assertEquals(event, restored)
    }

    @Test
    fun testAlertEntityMapping() {
        val alert = Alert(
            id = "alt-999",
            shipmentId = "ship-001",
            type = AlertType.TEMPERATURE_BREACH,
            message = "Temperature exceeded 8.0C for 5 minutes",
            timestamp = 1700000500000L,
            escalationLevel = EscalationLevel.WORKER,
            acknowledged = false
        )
        val entity = alert.toEntity(SyncStatus.PENDING)
        assertEquals("alt-999", entity.id)
        assertEquals(SyncStatus.PENDING, entity.syncStatus)

        val restored = entity.toDomain()
        assertEquals(alert, restored)
    }

    @Test
    fun testHandoverEntityMapping() {
        val handover = Handover(
            id = "hnd-555",
            shipmentId = "ship-001",
            workerSigned = true,
            pharmacistSigned = true,
            integrityVerified = true,
            temperaturePassed = true,
            verdict = HandoverVerdict.PASS,
            timestamp = 1700001000000L
        )
        val entity = handover.toEntity(SyncStatus.PENDING)
        assertEquals("hnd-555", entity.id)
        assertEquals(SyncStatus.PENDING, entity.syncStatus)

        val restored = entity.toDomain()
        assertEquals(handover, restored)
    }
}
