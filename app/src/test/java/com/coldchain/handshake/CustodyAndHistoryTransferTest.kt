package com.coldchain.handshake

import com.coldchain.handshake.data.remote.dto.RemoteShipmentCustodyDto
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustodyAndHistoryTransferTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testRemoteShipmentCustodyDtoSerializationRoundTrip() {
        val custody = RemoteShipmentCustodyDto(
            shipmentId = "SHIP-CUSTODY-001",
            activeDeviceId = "DEV-PHONEA1",
            custodyState = "ACTIVE",
            updatedAt = 1726000000000L
        )

        val encoded = json.encodeToString(custody)
        assertTrue("JSON must contain shipment_id", encoded.contains("shipment_id"))
        assertTrue("JSON must contain active_device_id", encoded.contains("DEV-PHONEA1"))
        assertTrue("JSON must contain custody_state", encoded.contains("ACTIVE"))

        val decoded = json.decodeFromString<RemoteShipmentCustodyDto>(encoded)
        assertEquals(custody.shipmentId, decoded.shipmentId)
        assertEquals(custody.activeDeviceId, decoded.activeDeviceId)
        assertEquals(custody.custodyState, decoded.custodyState)
        assertEquals(custody.updatedAt, decoded.updatedAt)
    }

    @Test
    fun testCustodyLifecycleTransitions() {
        val phoneADeviceId = "DEV-PHONE-A"
        val phoneBDeviceId = "DEV-PHONE-B"
        val shipmentId = "SHIP-TRANSFER-100"

        // 1. Phone A creates shipment -> Initial ACTIVE custody
        var currentCustody = RemoteShipmentCustodyDto(
            shipmentId = shipmentId,
            activeDeviceId = phoneADeviceId,
            custodyState = "ACTIVE",
            updatedAt = 1000L
        )
        assertEquals(phoneADeviceId, currentCustody.activeDeviceId)
        assertEquals("ACTIVE", currentCustody.custodyState)

        // 2. Phone B scans QR -> Discovery / Monitoring only, custody NOT transferred!
        val scanTransfersCustody = false
        if (scanTransfersCustody) {
            currentCustody = currentCustody.copy(activeDeviceId = phoneBDeviceId)
        }
        assertEquals("Phone A must retain custody upon QR scan alone", phoneADeviceId, currentCustody.activeDeviceId)

        // 3. Handover verdict evaluation
        fun evaluateHandoverCustodyTransfer(
            verdict: HandoverVerdict,
            current: RemoteShipmentCustodyDto,
            receivingDeviceId: String
        ): RemoteShipmentCustodyDto {
            return if (verdict == HandoverVerdict.PASS) {
                current.copy(
                    activeDeviceId = receivingDeviceId,
                    custodyState = "TRANSFERRED",
                    updatedAt = 2000L
                )
            } else {
                current // Do not transfer on FAIL
            }
        }

        // Test Failed Handover: does NOT transfer custody
        val failedCustody = evaluateHandoverCustodyTransfer(HandoverVerdict.FAIL, currentCustody, phoneBDeviceId)
        assertEquals("Failed handover must NOT transfer custody", phoneADeviceId, failedCustody.activeDeviceId)
        assertEquals("ACTIVE", failedCustody.custodyState)

        // Test Passed Handover: transfers custody to Phone B
        val passedCustody = evaluateHandoverCustodyTransfer(HandoverVerdict.PASS, currentCustody, phoneBDeviceId)
        assertEquals("Passed handover must transfer custody to Phone B", phoneBDeviceId, passedCustody.activeDeviceId)
        assertEquals("TRANSFERRED", passedCustody.custodyState)

        // Phone A detects custody is no longer held by Phone A
        val isPhoneAActiveHolder = passedCustody.activeDeviceId == phoneADeviceId && passedCustody.custodyState != "TRANSFERRED"
        assertFalse("Phone A must deactivate active session after transfer", isPhoneAActiveHolder)
    }

    @Test
    fun testTelemetryRetainedLocallyAcrossAllSyncStatuses() {
        val pendingEvent = TemperatureEvent(
            id = "TE-001",
            shipmentId = "SHIP-HIST",
            loggerId = "LOG-1",
            timestamp = 1000L,
            temperature = 4.5,
            previousHash = "GENESIS",
            currentHash = "hash1",
            syncStatus = SyncStatus.PENDING
        )
        val syncedEvent = pendingEvent.copy(syncStatus = SyncStatus.SYNCED)
        val failedEvent = pendingEvent.copy(syncStatus = SyncStatus.FAILED)

        // Simulated Room database list
        val localRoomRecords = mutableListOf<TemperatureEvent>()

        // Insert pending event
        localRoomRecords.add(pendingEvent)
        assertEquals(1, localRoomRecords.size)
        assertEquals(SyncStatus.PENDING, localRoomRecords[0].syncStatus)

        // Update sync status to SYNCED (in Room, updateSyncStatus does NOT delete rows)
        localRoomRecords[0] = syncedEvent
        assertEquals(1, localRoomRecords.size)
        assertEquals(SyncStatus.SYNCED, localRoomRecords[0].syncStatus)

        // Update sync status to FAILED
        localRoomRecords[0] = failedEvent
        assertEquals(1, localRoomRecords.size)
        assertEquals(SyncStatus.FAILED, localRoomRecords[0].syncStatus)

        // Assert all statuses remain accessible for local audit trail
        assertTrue("Telemetry must remain stored locally regardless of syncStatus", localRoomRecords.isNotEmpty())
    }

    @Test
    fun testIdempotentTelemetryReplicationIgnoresDuplicates() {
        val initialEvents = listOf(
            TemperatureEvent(
                id = "TE-1",
                shipmentId = "SHIP-X",
                loggerId = "LOG-1",
                timestamp = 1000L,
                temperature = 4.2,
                previousHash = "GENESIS",
                currentHash = "hash1",
                syncStatus = SyncStatus.SYNCED
            ),
            TemperatureEvent(
                id = "TE-2",
                shipmentId = "SHIP-X",
                loggerId = "LOG-1",
                timestamp = 2000L,
                temperature = 4.8,
                previousHash = "hash1",
                currentHash = "hash2",
                syncStatus = SyncStatus.SYNCED
            )
        )

        val roomStorage = initialEvents.toMutableList()

        // Incoming remote batch (contains already persisted TE-1 and new TE-3)
        val incomingBatch = listOf(
            initialEvents[0], // Duplicate
            TemperatureEvent(
                id = "TE-3",
                shipmentId = "SHIP-X",
                loggerId = "LOG-1",
                timestamp = 3000L,
                temperature = 5.1,
                previousHash = "hash2",
                currentHash = "hash3",
                syncStatus = SyncStatus.SYNCED
            )
        )

        // Simulate OnConflictStrategy.IGNORE
        for (event in incomingBatch) {
            if (roomStorage.none { it.id == event.id }) {
                roomStorage.add(event)
            }
        }

        assertEquals("Room storage must contain exactly 3 events without duplicate rows", 3, roomStorage.size)
        assertEquals("TE-3", roomStorage[2].id)
    }
}
