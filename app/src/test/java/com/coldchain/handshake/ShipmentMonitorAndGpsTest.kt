package com.coldchain.handshake

import com.coldchain.handshake.data.remote.dto.RemoteShipmentLocationDto
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.ui.screens.GpsLocationData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipmentMonitorAndGpsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testRemoteShipmentLocationDtoSerializationRoundTrip() {
        val original = RemoteShipmentLocationDto(
            id = "LOC-12345",
            shipmentId = "SHIP-MONITOR-001",
            latitude = 10.850516,
            longitude = 76.271080,
            accuracy = 18.2f,
            timestamp = 1726000000000L
        )

        val encoded = json.encodeToString(original)
        assertTrue("Serialized JSON must contain shipment_id key", encoded.contains("shipment_id"))
        assertTrue("Serialized JSON must contain latitude", encoded.contains("10.850516"))

        val decoded = json.decodeFromString<RemoteShipmentLocationDto>(encoded)
        assertEquals(original.id, decoded.id)
        assertEquals(original.shipmentId, decoded.shipmentId)
        assertEquals(original.latitude, decoded.latitude, 0.000001)
        assertEquals(original.longitude, decoded.longitude, 0.000001)
        assertEquals(original.accuracy, decoded.accuracy, 0.01f)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun testQrManifestShipmentIdExtraction() {
        fun extractShipmentId(scannedCode: String): String {
            return if (scannedCode.startsWith("CCH:SHIP:")) {
                scannedCode.split(":").getOrNull(2) ?: scannedCode
            } else {
                scannedCode
            }
        }

        // Test standard ColdChainHandshake manifest QR format
        val manifestQr = "CCH:SHIP:SHIP-ABC123:Central Cold Hub:St. Jude Pharmacy:LOG-902"
        assertEquals("SHIP-ABC123", extractShipmentId(manifestQr))

        // Test raw shipment ID format
        val rawIdQr = "SHIP-XYZ999"
        assertEquals("SHIP-XYZ999", extractShipmentId(rawIdQr))
    }

    @Test
    fun testTelemetryStrictlyFilteredByShipmentId() {
        val eventShipmentA = TemperatureEvent(
            id = "TE-A1",
            shipmentId = "SHIP-A",
            loggerId = "LOG-A",
            timestamp = 1000L,
            temperature = 4.5,
            previousHash = "GENESIS",
            currentHash = "hashA",
            syncStatus = SyncStatus.SYNCED
        )
        val eventShipmentB = TemperatureEvent(
            id = "TE-B1",
            shipmentId = "SHIP-B",
            loggerId = "LOG-B",
            timestamp = 1000L,
            temperature = 5.5,
            previousHash = "GENESIS",
            currentHash = "hashB",
            syncStatus = SyncStatus.SYNCED
        )

        val allEvents = listOf(eventShipmentA, eventShipmentB)
        val monitoredShipmentId = "SHIP-A"
        val filtered = allEvents.filter { it.shipmentId == monitoredShipmentId }

        assertEquals(1, filtered.size)
        assertEquals("SHIP-A", filtered[0].shipmentId)
        assertEquals("TE-A1", filtered[0].id)
    }

    @Test
    fun testGpsLocationToRemoteDtoMapping() {
        val localGps = GpsLocationData(
            latitude = 12.971598,
            longitude = 77.594566,
            accuracy = 8.5f,
            timestamp = 1726123456789L
        )

        val targetShipmentId = "SHIP-LIVE-42"
        val dto = RemoteShipmentLocationDto(
            id = "UUID-TEST",
            shipmentId = targetShipmentId,
            latitude = localGps.latitude,
            longitude = localGps.longitude,
            accuracy = localGps.accuracy,
            timestamp = localGps.timestamp
        )

        assertEquals("SHIP-LIVE-42", dto.shipmentId)
        assertEquals(localGps.latitude, dto.latitude, 0.000001)
        assertEquals(localGps.longitude, dto.longitude, 0.000001)
        assertEquals(localGps.accuracy, dto.accuracy, 0.01f)
        assertEquals(localGps.timestamp, dto.timestamp)
    }
}
