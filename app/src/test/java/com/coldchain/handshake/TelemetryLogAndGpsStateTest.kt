package com.coldchain.handshake

import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.ui.screens.GpsLocationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryLogAndGpsStateTest {

    @Test
    fun testTelemetryLogNewestFirstSorting() {
        val event1 = TemperatureEvent(
            id = "TE-001",
            shipmentId = "SHIP-1",
            loggerId = "LOG-1",
            timestamp = 1000L,
            temperature = 4.5,
            previousHash = "GENESIS",
            currentHash = "hash1",
            syncStatus = SyncStatus.SYNCED
        )
        val event2 = TemperatureEvent(
            id = "TE-002",
            shipmentId = "SHIP-1",
            loggerId = "LOG-1",
            timestamp = 2000L,
            temperature = 5.2,
            previousHash = "hash1",
            currentHash = "hash2",
            syncStatus = SyncStatus.PENDING
        )
        val event3 = TemperatureEvent(
            id = "TE-003",
            shipmentId = "SHIP-1",
            loggerId = "LOG-1",
            timestamp = 3000L,
            temperature = 9.8,
            previousHash = "hash2",
            currentHash = "hash3",
            syncStatus = SyncStatus.PENDING
        )

        val rawList = listOf(event1, event2, event3) // chronological ascending
        val sortedList = rawList.sortedByDescending { it.timestamp } // newest first for display

        assertEquals("Newest event must be first in list", "TE-003", sortedList[0].id)
        assertEquals("Middle event must be second", "TE-002", sortedList[1].id)
        assertEquals("Oldest event must be last", "TE-001", sortedList[2].id)

        assertEquals(SyncStatus.PENDING, sortedList[0].syncStatus)
        assertEquals(SyncStatus.SYNCED, sortedList[2].syncStatus)
    }

    @Test
    fun testGpsLocationDataPreservesAccuracyAndPrecision() {
        val gps = GpsLocationData(
            latitude = 10.850516,
            longitude = 76.271080,
            accuracy = 14.5f,
            timestamp = 1700000000000L
        )

        assertEquals(10.850516, gps.latitude, 0.000001)
        assertEquals(76.271080, gps.longitude, 0.000001)
        assertEquals(14.5f, gps.accuracy, 0.01f)
        assertTrue(gps.timestamp > 0)
    }
}
