package com.coldchain.handshake.simulator

import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Debug local mode's virtual logger (4.1, MASTER 5.4 item 2): what the simulator measures for a shipment kept on this
 * phone alone is also recorded by that shipment's virtual logger, one record per reading, so the logger check at
 * handover compares like with like. A server shipment has none: its readings come from the courier's phone and a
 * virtual logger there would prove nothing (DECISIONS, 28 Nov).
 */
class LocalLoggersTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val telemetry = InMemoryTelemetryRepository()
    private val loggers = LocalLoggers()
    private var clock = t0
    private val simulator = TemperatureSimulator(telemetry, wallClock = { clock }, localLoggers = loggers)

    private fun shipment(genesisHash: String? = null) = Shipment(
        "SHIP-L", "QR", "LOG-7", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id, t0,
        genesisHash = genesisHash
    )

    private fun recordFive(shipment: Shipment) = runBlocking {
        simulator.attachShipment(shipment)
        (1..5).forEach { clock = t0 + it * minute; simulator.tickOnce() }
    }

    @Test
    fun aLocalShipmentsReadingsAreAlsoItsLoggersRecords() = runBlocking {
        recordFive(shipment())

        val readings = telemetry.getTemperatures("SHIP-L").first().sortedBy { it.seq }
        val logger = loggers.of("SHIP-L")!!
        val records = logger.readHistory(1, 100).getOrThrow()!!.records
        assertEquals("LOG-7", logger.loggerId)
        assertEquals(readings.map { it.seq }, records.map { it.seq })
        assertEquals(readings.map { it.timestamp }, records.map { it.loggerTs })
        assertEquals(readings.map { Math.round(it.temperature * 100).toInt() }, records.map { it.tempCentiC })
    }

    @Test
    fun aServerShipmentHasNoLocalLogger() {
        recordFive(shipment(genesisHash = "a".repeat(64)))

        assertNull(loggers.of("SHIP-L"))
    }
}
