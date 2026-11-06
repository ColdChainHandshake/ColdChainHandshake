package com.coldchain.handshake.logger

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs in both the debug and the release unit tests, so the code of every build refuses a logger holding the key
 * whose private half the protocol's vectors publish.
 */
class TestLoggerKeyRefusedTest {

    private val shipment = Shipment("SHIP-T", "SHIP-T", VectorTestKey.loggerId, "Depot", "Clinic", "worker")

    private fun loggerWithThreeReadings(logger: VirtualLogger) = logger.apply {
        (1..3).forEach { record(1_700_000_000_000L + it * 60_000L, 500) }
    }

    @Test
    fun aLoggerHoldingTheTestKeyStoresNothing() = runBlocking {
        val logger = loggerWithThreeReadings(VirtualLogger(VectorTestKey.loggerId, VectorTestKey.keyPair()))
        val telemetry = InMemoryTelemetryRepository()

        val result = LoggerIngestor(logger, telemetry, VectorTestKey.loggerId, VectorTestKey.publicKeyUncompressed)
            .ingest(shipment)

        val refused = result.exceptionOrNull() as LoggerHistoryRejected
        assertEquals(listOf(LoggerIssueType.TEST_KEY), refused.verification.issues.map { it.type })
        assertTrue(telemetry.getTemperatures(shipment.id).first().isEmpty())
    }

    @Test
    fun aLoggerWithItsOwnKeyIsStillIngested() = runBlocking {
        val logger = loggerWithThreeReadings(VirtualLogger(VectorTestKey.loggerId))
        val telemetry = InMemoryTelemetryRepository()

        val result = LoggerIngestor(logger, telemetry, VectorTestKey.loggerId, logger.publicKeyUncompressed)
            .ingest(shipment)

        assertEquals(3, result.getOrThrow())
        assertEquals(3, telemetry.getTemperatures(shipment.id).first().size)
    }
}
