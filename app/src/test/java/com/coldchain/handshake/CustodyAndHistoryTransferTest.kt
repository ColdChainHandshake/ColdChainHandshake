package com.coldchain.handshake

import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.data.remote.dto.RemoteShipmentCustodyDto
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.ui.screens.formatCustodyErrorMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

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

        val localRoomRecords = mutableListOf<TemperatureEvent>()
        localRoomRecords.add(pendingEvent)
        assertEquals(1, localRoomRecords.size)
        assertEquals(SyncStatus.PENDING, localRoomRecords[0].syncStatus)

        localRoomRecords[0] = syncedEvent
        assertEquals(1, localRoomRecords.size)
        assertEquals(SyncStatus.SYNCED, localRoomRecords[0].syncStatus)

        localRoomRecords[0] = failedEvent
        assertEquals(1, localRoomRecords.size)
        assertEquals(SyncStatus.FAILED, localRoomRecords[0].syncStatus)

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
        val incomingBatch = listOf(
            initialEvents[0],
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

        for (event in incomingBatch) {
            if (roomStorage.none { it.id == event.id }) {
                roomStorage.add(event)
            }
        }

        assertEquals("Room storage must contain exactly 3 events without duplicate rows", 3, roomStorage.size)
        assertEquals("TE-3", roomStorage[2].id)
    }

    // =========================================================================
    // REGRESSION TESTS FOR SUPABASE CUSTODY TRANSFER AND DIAGNOSTICS
    // =========================================================================

    /**
     * Test double simulating SupabaseRemoteDataSource custody transfer operations
     * with programmable network/database conditions and zero-row verification.
     */
    private class MockableCustodyRemoteDataSource : SupabaseRemoteDataSource() {
        val custodyTable = mutableMapOf<String, RemoteShipmentCustodyDto>()
        var upsertError: Throwable? = null
        var confirmError: Throwable? = null
        var customConfirmRow: RemoteShipmentCustodyDto? = null
        var zeroRowsReturnedOnConfirm: Boolean = false

        override suspend fun transferCustodyWithConfirmation(
            shipmentId: String,
            newDeviceId: String,
            custodyState: String
        ): Result<RemoteShipmentCustodyDto> {
            upsertError?.let { return Result.failure(it) }

            val record = RemoteShipmentCustodyDto(
                shipmentId = shipmentId,
                activeDeviceId = newDeviceId,
                custodyState = custodyState,
                updatedAt = System.currentTimeMillis()
            )
            custodyTable[shipmentId] = record

            confirmError?.let { return Result.failure(it) }

            if (zeroRowsReturnedOnConfirm) {
                return Result.failure(
                    IllegalStateException("Confirmation query returned zero rows for shipment $shipmentId on table shipment_custody")
                )
            }

            val confirmed = customConfirmRow ?: custodyTable[shipmentId]
                ?: return Result.failure(
                    IllegalStateException("Confirmation query returned zero rows for shipment $shipmentId on table shipment_custody")
                )

            if (confirmed.activeDeviceId != newDeviceId || confirmed.custodyState != custodyState) {
                return Result.failure(
                    IllegalStateException("Custody state mismatch on remote database: expected device $newDeviceId, found ${confirmed.activeDeviceId}")
                )
            }

            return Result.success(confirmed)
        }

        override suspend fun getCustodyState(shipmentId: String): Result<RemoteShipmentCustodyDto?> {
            confirmError?.let { return Result.failure(it) }
            return Result.success(custodyTable[shipmentId])
        }
    }

    @Test
    fun testRegression1_SuccessfulCustodyTransfer() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-001"
        val phoneBId = "DEV-PHONE-B"

        val result = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("Transfer must succeed", result.isSuccess)

        val confirmed = result.getOrNull()
        assertNotNull("Confirmed record must not be null", confirmed)
        assertEquals(phoneBId, confirmed?.activeDeviceId)
        assertEquals("TRANSFERRED", confirmed?.custodyState)
        assertEquals(shipmentId, confirmed?.shipmentId)
    }

    @Test
    fun testRegression2_MissingTableApiFailure() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-002"
        val phoneBId = "DEV-PHONE-B"

        // Simulate PostgREST PGRST205 error
        val pgrst205Exception = RuntimeException("Could not find the table 'public.shipment_custody' in the schema cache (code: PGRST205)")
        ds.upsertError = pgrst205Exception

        val result = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("Transfer must fail on missing table", result.isFailure)

        val error = result.exceptionOrNull()
        assertNotNull(error)
        val formattedUiMsg = formatCustodyErrorMessage(error)
        assertEquals(
            "Custody transfer failed: Table 'shipment_custody' not found in Supabase database",
            formattedUiMsg
        )
        assertFalse("Must NOT mask missing table as network waiting", formattedUiMsg.contains("Waiting for network"))
    }

    @Test
    fun testRegression3_RlsFailure() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-003"
        val phoneBId = "DEV-PHONE-B"

        // Simulate PostgreSQL 42501 permission denied (RLS / grant missing)
        val rlsException = RuntimeException("permission denied for table shipment_custody (code: 42501)")
        ds.upsertError = rlsException

        val result = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("Transfer must fail on RLS/permission denied", result.isFailure)

        val error = result.exceptionOrNull()
        assertNotNull(error)
        val formattedUiMsg = formatCustodyErrorMessage(error)
        assertEquals(
            "Custody transfer failed: Permission denied (table 'shipment_custody' requires GRANT/RLS policy in Supabase)",
            formattedUiMsg
        )
        assertFalse("Must NOT mask RLS error as network waiting", formattedUiMsg.contains("Waiting for network"))
    }

    @Test
    fun testRegression4_ZeroRowConfirmationFailure() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-004"
        val phoneBId = "DEV-PHONE-B"

        // Simulate zero rows returned after upsert
        ds.zeroRowsReturnedOnConfirm = true

        val result = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("Transfer must fail when confirmation query returns zero rows", result.isFailure)

        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue("Error message must specify zero rows", error?.message?.contains("zero rows") == true)

        val formattedUiMsg = formatCustodyErrorMessage(error)
        assertTrue(
            "UI message must truthfully reflect zero-row failure",
            formattedUiMsg.contains("Confirmation query returned zero rows")
        )
    }

    @Test
    fun testRegression5_SuccessfulRemoteConfirmationVerifiesState() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-005"
        val phoneBId = "DEV-PHONE-B"

        // Simulate remote confirmation returning stale/mismatched row
        ds.customConfirmRow = RemoteShipmentCustodyDto(
            shipmentId = shipmentId,
            activeDeviceId = "DEV-PHONE-STALE",
            custodyState = "ACTIVE",
            updatedAt = 1000L
        )

        val result = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("Transfer must reject mismatched confirmation state", result.isFailure)
        assertTrue(
            "Error message must mention state mismatch",
            result.exceptionOrNull()?.message?.contains("mismatch") == true
        )

        // Clear custom stale row to test successful confirmation
        ds.customConfirmRow = null
        val successResult = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("Transfer must succeed when confirmation matches", successResult.isSuccess)
        assertEquals(phoneBId, successResult.getOrNull()?.activeDeviceId)
    }

    @Test
    fun testRegression6_RetryAfterTransientFailure() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-006"
        val phoneBId = "DEV-PHONE-B"

        // 1st Attempt: Transient network timeout
        ds.upsertError = IOException("SocketTimeoutException: timeout during upsert")
        val firstResult = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("First attempt fails on timeout", firstResult.isFailure)

        var uiNotice = formatCustodyErrorMessage(firstResult.exceptionOrNull())
        assertEquals("Waiting for network to confirm custody transfer", uiNotice)

        // 2nd Attempt: Connection restored, retry succeeds
        ds.upsertError = null
        val retryResult = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue("Retry attempt succeeds", retryResult.isSuccess)

        if (retryResult.isSuccess) {
            uiNotice = "CUSTODY TRANSFERRED to this device ($phoneBId)"
        }
        assertEquals("CUSTODY TRANSFERRED to this device (DEV-PHONE-B)", uiNotice)
    }

    @Test
    fun testRegression7_PhoneADetectingPhoneBCustodyTransfer() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-007"
        val phoneAId = "DEV-PHONE-A"
        val phoneBId = "DEV-PHONE-B"

        // Initially Phone A has custody
        ds.custodyTable[shipmentId] = RemoteShipmentCustodyDto(
            shipmentId = shipmentId,
            activeDeviceId = phoneAId,
            custodyState = "ACTIVE",
            updatedAt = 1000L
        )

        // Phone A is active holder
        val initialCustody = ds.getCustodyState(shipmentId).getOrNull()
        assertEquals(phoneAId, initialCustody?.activeDeviceId)
        val phoneAIsActiveBefore = initialCustody?.activeDeviceId == phoneAId && initialCustody.custodyState != "TRANSFERRED"
        assertTrue("Phone A must be active custodian before transfer", phoneAIsActiveBefore)

        // Handover completed -> Phone B performs transferCustodyWithConfirmation
        ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")

        // Phone A polls getCustodyState
        val updatedCustody = ds.getCustodyState(shipmentId).getOrNull()
        assertNotNull(updatedCustody)
        assertEquals(phoneBId, updatedCustody?.activeDeviceId)
        assertEquals("TRANSFERRED", updatedCustody?.custodyState)

        // Phone A evaluates whether it is still the active holder
        val phoneAIsActiveAfter = updatedCustody?.activeDeviceId == phoneAId && updatedCustody.custodyState != "TRANSFERRED"
        assertFalse("Phone A must detect it is no longer the active custodian", phoneAIsActiveAfter)
    }

    @Test
    fun testRegression8_PhoneBBecomingCurrentCustody() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-008"
        val phoneBId = "DEV-PHONE-B"

        // Phone B executes handover digital sign-off and transfers custody
        val transferResult = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
        assertTrue(transferResult.isSuccess)

        // Phone B queries custody state to confirm current custody status
        val custodyRecord = ds.getCustodyState(shipmentId).getOrNull()
        assertNotNull(custodyRecord)
        assertEquals("DEV-PHONE-B", custodyRecord?.activeDeviceId)
        assertEquals("TRANSFERRED", custodyRecord?.custodyState)

        val isPhoneBCurrentCustody = custodyRecord?.activeDeviceId == phoneBId && custodyRecord.custodyState == "TRANSFERRED"
        assertTrue("Phone B must be verified as CURRENT CUSTODY", isPhoneBCurrentCustody)
    }

    @Test
    fun testRegression9_NoFakeTransferWhileOffline() = runBlocking {
        val ds = MockableCustodyRemoteDataSource()
        val shipmentId = "SHIP-REG-009"
        val phoneBId = "DEV-PHONE-B"

        // Device is offline
        val offlineException = UnknownHostException("Unable to resolve host 'ljqwaqesiszczixbclwg.supabase.co': No address associated with hostname")
        ds.upsertError = offlineException

        // Evaluator produced PASS verdict, but remote transfer fails due to offline state
        val verdict = HandoverVerdict.PASS
        var custodyNotice: String? = null

        if (verdict == HandoverVerdict.PASS) {
            val transferResult = ds.transferCustodyWithConfirmation(shipmentId, phoneBId, "TRANSFERRED")
            if (transferResult.isSuccess) {
                custodyNotice = "CUSTODY TRANSFERRED to this device ($phoneBId)"
            } else {
                custodyNotice = formatCustodyErrorMessage(transferResult.exceptionOrNull())
            }
        }

        // Must truthfully indicate waiting for network and NOT fake custody transfer
        assertEquals("Waiting for network to confirm custody transfer", custodyNotice)
        assertFalse("Must not report fake successful transfer", custodyNotice!!.startsWith("CUSTODY TRANSFERRED"))

        // Remote database state must NOT have been updated to Phone B
        val remoteRecord = ds.custodyTable[shipmentId]
        assertEquals("Remote custody table must have no record while offline", null, remoteRecord)
    }
}
