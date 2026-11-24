package com.coldchain.handshake.logger.ble

import com.coldchain.handshake.logger.LoggerHistoryVerifier
import com.coldchain.handshake.logger.LoggerIngestor
import com.coldchain.handshake.logger.LoggerProtocol
import com.coldchain.handshake.logger.VirtualLogger
import com.coldchain.handshake.logger.hexToBytes
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth link against a fake GATT server. The checks that matter for trust are the verifier's; these make
 * sure the link hands it exactly what the logger signed, and that a transfer problem is a failure, never a
 * shortened history that looks complete.
 */
class BleLoggerLinkTest {

    private val logger = VirtualLogger("LOG-BLE-1").apply {
        (1..20).forEach { record(1_700_000_000_000 + it * 60_000L, 450 + it) }
    }

    private fun kindOf(result: Result<*>) = (result.exceptionOrNull() as? LoggerGattException)?.kind

    @Test
    fun historyArrivesAsTheLoggerSignedItAtEveryMtu() = runTest {
        listOf(148, 185, 247, 517).forEach { mtu ->
            val link = BleLoggerLink(FakeLoggerGatt(logger, grantedMtu = mtu))
            val batch = link.readHistory(fromSeq = 1, maxRecords = 500).getOrThrow()!!
            val direct = logger.readHistory(1, 500).getOrThrow()!!

            assertEquals("MTU $mtu", direct.records, batch.records)
            assertEquals(direct.startHash, batch.startHash)
            assertEquals(direct.endHash, batch.endHash)
            assertEquals(1L, batch.firstSeq)
            assertEquals(20L, batch.lastSeq)
            val verification = LoggerHistoryVerifier.verify("LOG-BLE-1", logger.publicKeyUncompressed, listOf(batch))
            assertTrue("MTU $mtu: ${verification.issues}", verification.trusted)
        }
    }

    @Test
    fun theLinkAsksForALargerMtuBeforeReadingHistory() = runTest {
        val gatt = FakeLoggerGatt(logger, grantedMtu = 247)
        assertEquals(23, gatt.attMtu)
        BleLoggerLink(gatt).readHistory(1, 500).getOrThrow()
        assertEquals(247, gatt.attMtu)
    }

    @Test
    fun anMtuTooSmallForTheBatchHeaderFailsWithoutAskingForHistory() = runTest {
        val gatt = FakeLoggerGatt(logger, grantedMtu = 147)
        val result = BleLoggerLink(gatt).readHistory(1, 500)
        assertEquals(LoggerGattException.Kind.MTU_TOO_SMALL, kindOf(result))
        assertTrue(gatt.writes.isEmpty())
    }

    @Test
    fun nothingNewerIsNullAndNoRequestIsWritten() = runTest {
        val gatt = FakeLoggerGatt(logger)
        val result = BleLoggerLink(gatt).readHistory(fromSeq = 21, maxRecords = 500)
        assertNull(result.getOrThrow())
        assertTrue(gatt.writes.none { it.first == LoggerGatt.HISTORY_REQUEST })
    }

    @Test
    fun aRequestFromTheMiddleReturnsOnlyThatRange() = runTest {
        val batch = BleLoggerLink(FakeLoggerGatt(logger)).readHistory(fromSeq = 8, maxRecords = 5).getOrThrow()!!
        assertEquals((8L..12L).toList(), batch.records.map { it.seq })
        val verification = LoggerHistoryVerifier.verify("LOG-BLE-1", logger.publicKeyUncompressed, listOf(batch), fromSeq = 8)
        assertTrue(verification.issues.toString(), verification.trusted)
    }

    @Test
    fun aLostPageIsAFailedTransferNotAShorterHistory() = runTest {
        val result = BleLoggerLink(FakeLoggerGatt(logger, grantedMtu = 148, dropPage = 1)).readHistory(1, 500)
        assertEquals(LoggerGattException.Kind.INCOMPLETE, kindOf(result))
    }

    // Security review S1: the range a header claims comes from the device, so nothing may be built in its size.
    @Test(timeout = 10_000)
    fun aHeaderClaimingAHugeRangeIsRefusedWithoutBuildingIt() = runTest {
        val fake = FakeLoggerGatt(logger).apply { forgeHeader = { it.copy(lastSeq = Long.MAX_VALUE) } }
        assertEquals(LoggerGattException.Kind.INCOMPLETE, kindOf(BleLoggerLink(fake).readHistory(1, 500)))

        fake.forgeHeader = { it.copy(firstSeq = Long.MIN_VALUE) }
        assertEquals(LoggerGattException.Kind.INCOMPLETE, kindOf(BleLoggerLink(fake).readHistory(1, 500)))
    }

    @Test(timeout = 10_000)
    fun theVerifierRefusesAHugeClaimedRangeWithoutBuildingIt() = runTest {
        val honest = logger.readHistory(1, 500).getOrThrow()!!
        val forged = honest.copy(lastSeq = Long.MAX_VALUE)

        val verification = LoggerHistoryVerifier.verify("LOG-BLE-1", logger.publicKeyUncompressed, listOf(forged))

        assertTrue(!verification.trusted)
    }

    // Security review S2: a device that sends more than was asked for is not following the protocol.
    @Test
    fun aBatchWithMoreRecordsThanAskedForIsMalformed() = runTest {
        val result = BleLoggerLink(FakeLoggerGatt(logger, oversend = 1)).readHistory(fromSeq = 1, maxRecords = 5)
        assertEquals(LoggerGattException.Kind.MALFORMED, kindOf(result))
    }

    @Test
    fun aLoggerThatNeverAnswersTimesOut() = runTest {
        val history = BleLoggerLink(FakeLoggerGatt(logger, silent = true), timeoutMs = 5_000).readHistory(1, 500)
        assertEquals(LoggerGattException.Kind.TIMED_OUT, kindOf(history))
        val head = BleLoggerLink(FakeLoggerGatt(logger, silent = true), timeoutMs = 5_000).challenge(ByteArray(16))
        assertEquals(LoggerGattException.Kind.TIMED_OUT, kindOf(head))
    }

    @Test
    fun theHeadIsSignedOverThisReadersNonce() = runTest {
        val nonce = ByteArray(16) { (it * 7).toByte() }
        val head = BleLoggerLink(FakeLoggerGatt(logger)).challenge(nonce).getOrThrow()
        assertEquals(20L, head.headSeq)
        assertTrue(
            LoggerProtocol.verify(
                logger.publicKeyUncompressed,
                LoggerProtocol.headPayload("LOG-BLE-1", head.headSeq, head.headHash.hexToBytes(), nonce),
                head.signature.hexToBytes()
            )
        )
    }

    @Test
    fun aNonceOfTheWrongLengthIsRefusedBeforeAnythingIsSent() = runTest {
        val gatt = FakeLoggerGatt(logger)
        assertEquals(LoggerGattException.Kind.MALFORMED, kindOf(BleLoggerLink(gatt).challenge(ByteArray(15))))
        assertTrue(gatt.writes.isEmpty())
    }

    @Test
    fun infoPublicKeyAndStatusAreReadAsTheLoggerHoldsThem() = runTest {
        val link = BleLoggerLink(FakeLoggerGatt(logger))
        val info = link.info().getOrThrow()
        assertEquals("LOG-BLE-1", info.loggerId)
        assertEquals(1, info.protocolVersion)
        assertEquals("virtual-1", info.firmwareVersion)
        assertArrayEquals(logger.publicKeyUncompressed, link.publicKey().getOrThrow())
        assertEquals(logger.status().getOrThrow(), link.status().getOrThrow())
    }

    @Test
    fun aLoggerSpeakingAnotherProtocolVersionIsRefused() = runTest {
        val result = BleLoggerLink(FakeLoggerGatt(logger, protocolVersion = 2)).info()
        assertEquals(LoggerGattException.Kind.UNSUPPORTED_VERSION, kindOf(result))
    }

    @Test
    fun theIngestorBackFillsOverBluetoothAndResumesWhereItStopped() = runTest {
        val gatt = FakeLoggerGatt(logger, grantedMtu = 148)
        val telemetry = MemoryTelemetry()
        val shipment = Shipment("SHIP-BLE", "SHIP-BLE", "LOG-BLE-1", "A", "B", "W", genesisHash = "00".repeat(32))
        val ingestor = LoggerIngestor(BleLoggerLink(gatt), telemetry, "LOG-BLE-1", logger.publicKeyUncompressed, maxRecords = 7)

        assertEquals(7, ingestor.ingest(shipment).getOrThrow())
        assertEquals(7, ingestor.ingest(shipment).getOrThrow())
        // A dropped connection loses one answer; the next one starts from where the phone got to.
        gatt.dropPage = 0
        assertTrue(ingestor.ingest(shipment).isFailure)
        gatt.dropPage = null
        assertEquals(6, ingestor.ingest(shipment).getOrThrow())
        assertEquals(0, ingestor.ingest(shipment).getOrThrow())

        val stored = telemetry.events.value
        assertEquals((1L..20L).toList(), stored.map { it.seq })
        assertEquals(logger.readHistory(1, 500).getOrThrow()!!.records.map { it.tempCentiC }, stored.map { it.tempCentiC })
    }

    private class MemoryTelemetry : TelemetryRepository {
        val events = MutableStateFlow<List<TemperatureEvent>>(emptyList())
        override suspend fun saveTemperature(event: TemperatureEvent) = saveTemperatures(listOf(event))
        override suspend fun saveTemperatures(events: List<TemperatureEvent>): Result<Unit> {
            this.events.value = this.events.value + events
            return Result.success(Unit)
        }
        override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> =
            events.map { all -> all.filter { it.shipmentId == shipmentId } }
    }
}
