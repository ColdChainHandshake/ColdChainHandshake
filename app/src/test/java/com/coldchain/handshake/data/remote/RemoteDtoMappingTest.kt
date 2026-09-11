package com.coldchain.handshake.data.remote

import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.data.remote.dto.RemoteAlertDto
import com.coldchain.handshake.data.remote.dto.RemoteHandoverDto
import com.coldchain.handshake.data.remote.dto.RemoteShipmentDto
import com.coldchain.handshake.data.remote.dto.RemoteTemperatureEventDto
import com.coldchain.handshake.data.remote.dto.toDomain
import com.coldchain.handshake.data.remote.dto.toRemoteDto
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteDtoMappingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    @After
    fun cleanup() {
        SupabaseClientProvider.reset()
    }

    @Test
    fun testShipmentDtoMappingAndSerialization() {
        val domain = Shipment(
            id = "ship-remote-001",
            qrCode = "QR_PAYLOAD_REMOTE",
            loggerId = "LOG-REMOTE-99",
            origin = "Berlin Depot",
            destination = "Warsaw Hospital",
            workerId = "WRK-501",
            status = ShipmentStatus.IN_TRANSIT
        )

        val dtoFromDomain = domain.toRemoteDto()
        val jsonStr = json.encodeToString(dtoFromDomain)
        assertTrue(jsonStr.contains("\"qr_code\":\"QR_PAYLOAD_REMOTE\""))
        assertTrue(jsonStr.contains("\"logger_id\":\"LOG-REMOTE-99\""))
        assertTrue(jsonStr.contains("\"status\":\"IN_TRANSIT\""))

        val decodedDto = json.decodeFromString<RemoteShipmentDto>(jsonStr)
        val restoredDomain = decodedDto.toDomain()

        assertEquals(domain, restoredDomain)

        // Also test Entity -> DTO mapping
        val entity = domain.toEntity(SyncStatus.PENDING)
        val dtoFromEntity = entity.toRemoteDto()
        assertEquals(dtoFromDomain, dtoFromEntity)
    }

    @Test
    fun testTemperatureEventDtoMapping_preservesCryptographicHashesAndTimestamps() {
        val exactTimestamp = 1719000000888L
        val previousHash = "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4"
        val currentHash = "fbc752a23e93673c24ffeb09ec7beabfeccfa7f369ee69a48972df7a7daaa2f8"

        val event = TemperatureEvent(
            id = "evt-remote-777",
            shipmentId = "ship-remote-001",
            loggerId = "LOG-REMOTE-99",
            timestamp = exactTimestamp,
            temperature = 4.75,
            previousHash = previousHash,
            currentHash = currentHash,
            syncStatus = SyncStatus.PENDING
        )

        val dto = event.toRemoteDto()
        val jsonStr = json.encodeToString(dto)
        assertTrue(jsonStr.contains("\"previous_hash\":\"$previousHash\""))
        assertTrue(jsonStr.contains("\"current_hash\":\"$currentHash\""))
        assertTrue(jsonStr.contains("\"temperature\":4.75"))

        val decoded = json.decodeFromString<RemoteTemperatureEventDto>(jsonStr)
        val restored = decoded.toDomain()

        assertEquals(event.id, restored.id)
        assertEquals(event.shipmentId, restored.shipmentId)
        assertEquals(event.loggerId, restored.loggerId)
        assertEquals(exactTimestamp, restored.timestamp)
        assertEquals(4.75, restored.temperature, 0.0001)
        assertEquals(previousHash, restored.previousHash)
        assertEquals(currentHash, restored.currentHash)
    }

    @Test
    fun testAlertDtoMappingAndSerialization() {
        val alert = Alert(
            id = "alt-remote-333",
            shipmentId = "ship-remote-001",
            type = AlertType.TEMPERATURE_BREACH,
            message = "Thermal excursion detected > 8.0C for 6 mins",
            timestamp = 1719000360000L,
            escalationLevel = EscalationLevel.SUPERVISOR,
            acknowledged = true
        )

        val dto = alert.toRemoteDto()
        val jsonStr = json.encodeToString(dto)
        assertTrue(jsonStr.contains("\"escalation_level\":\"SUPERVISOR\""))
        assertTrue(jsonStr.contains("\"acknowledged\":true"))

        val decoded = json.decodeFromString<RemoteAlertDto>(jsonStr)
        val restored = decoded.toDomain()

        assertEquals(alert, restored)
    }

    @Test
    fun testHandoverDtoMappingAndSerialization() {
        val handover = Handover(
            id = "hnd-remote-444",
            shipmentId = "ship-remote-001",
            workerSigned = true,
            pharmacistSigned = true,
            integrityVerified = true,
            temperaturePassed = true,
            verdict = HandoverVerdict.PASS,
            timestamp = 1719001000000L
        )

        val dto = handover.toRemoteDto()
        val jsonStr = json.encodeToString(dto)
        assertTrue(jsonStr.contains("\"worker_signed\":true"))
        assertTrue(jsonStr.contains("\"pharmacist_signed\":true"))
        assertTrue(jsonStr.contains("\"verdict\":\"PASS\""))

        val decoded = json.decodeFromString<RemoteHandoverDto>(jsonStr)
        val restored = decoded.toDomain()

        assertEquals(handover, restored)
    }

    @Test
    fun testSupabaseClientProviderConfiguration() {
        assertEquals("https://ljqwaqesiszczixbclwg.supabase.co", SupabaseClientProvider.getTargetUrl())
        assertNotNull(SupabaseClientProvider.getClient())

        SupabaseClientProvider.initialize(
            url = "https://my-real-project.supabase.co",
            key = "my-real-anon-key-12345"
        )
        assertTrue("Configured provider should report configured = true", SupabaseClientProvider.isConfigured())
        assertEquals("https://my-real-project.supabase.co", SupabaseClientProvider.getTargetUrl())

        SupabaseClientProvider.reset()
        assertEquals("https://ljqwaqesiszczixbclwg.supabase.co", SupabaseClientProvider.getTargetUrl())
    }

    @Test
    fun testDataSourceGracefulFailureWithPlaceholder() = runTest {
        val invalidClient = io.github.jan.supabase.createSupabaseClient(
            SupabaseClientProvider.PLACEHOLDER_URL,
            SupabaseClientProvider.PLACEHOLDER_KEY
        ) {
            install(io.github.jan.supabase.postgrest.Postgrest)
        }
        val dataSource = SupabaseRemoteDataSource { invalidClient }
        val dummyShipment = RemoteShipmentDto(
            id = "test-fail-1",
            qrCode = "QR",
            loggerId = "L",
            origin = "O",
            destination = "D",
            workerId = "W",
            status = "CREATED"
        )

        // Calling placeholder remote endpoint should gracefully return Result.failure without throwing an uncaught crash
        val result = dataSource.upsertShipment(dummyShipment)
        assertTrue("Placeholder endpoint write should gracefully fail", result.isFailure)
    }
}
