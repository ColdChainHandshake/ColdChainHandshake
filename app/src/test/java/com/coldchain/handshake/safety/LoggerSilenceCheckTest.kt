package com.coldchain.handshake.safety

import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerSilenceCheckTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val limit = ProductProfiles.DEFAULT.maxSilenceMs

    private val shipments = FakeShipmentRepository()
    private val telemetry = InMemoryTelemetryRepository()
    private val alerts = FakeAlertRepository()
    private val engine = SafetyEngine(alerts, shipments)

    private fun moving(status: ShipmentStatus = ShipmentStatus.IN_TRANSIT, journeyStartedAt: Long? = t0) =
        Shipment("SHIP-1", "QR", "LOG-1", "A", "B", "W-1", status, ProductProfiles.DEFAULT.id, journeyStartedAt)

    private fun ownReadings(durationMs: Long, temperature: Double) =
        steadyReadings(t0, durationMs, temperature).map { it.copy(shipmentId = "SHIP-1") }

    private suspend fun prepare(shipment: Shipment = moving(), lastReadingAt: Long = t0 + 5 * minute) {
        shipments.saveShipment(shipment)
        telemetry.saveTemperatures(ownReadings(lastReadingAt - t0, 5.0)).getOrThrow()
    }

    private suspend fun check(now: Long) = LoggerSilenceCheck(shipments, telemetry, engine) { now }.run()

    @Test
    fun aLoggerThatStopsRaisesAnAlertWithoutAnotherReading() = runBlocking {
        val lastReading = t0 + 5 * minute
        prepare(lastReadingAt = lastReading)

        check(lastReading + limit + 1)

        val alert = alerts.savedAlerts.single()
        assertEquals(AlertType.DATA_GAP, alert.type)
        assertEquals(lastReading, alert.excursionStart)
    }

    @Test
    fun aLoggerStillWithinItsToleranceRaisesNothing() = runBlocking {
        val lastReading = t0 + 5 * minute
        prepare(lastReadingAt = lastReading)

        check(lastReading + limit)

        assertTrue(alerts.savedAlerts.isEmpty())
    }

    @Test
    fun theNextCheckIsWhenTheLoggerWouldGoQuiet() = runBlocking {
        val lastReading = t0 + 5 * minute
        prepare(lastReadingAt = lastReading)

        assertEquals(lastReading + limit + 1, check(lastReading))
    }

    @Test
    fun aRepeatCheckRaisesNoSecondAlert() = runBlocking {
        val lastReading = t0 + 5 * minute
        prepare(lastReadingAt = lastReading)

        check(lastReading + limit + 1)
        check(lastReading + 2 * limit)

        assertEquals(1, alerts.savedAlerts.size)
    }

    @Test
    fun aLoggerAlreadyQuietIsLookedAtAgainLater() = runBlocking {
        val lastReading = t0 + 5 * minute
        prepare(lastReadingAt = lastReading)
        val now = lastReading + 10 * limit

        assertEquals(now + limit, check(now))
    }

    @Test
    fun aJourneyWithNoReadingsAtAllRaisesAnAlertFromItsStart() = runBlocking {
        shipments.saveShipment(moving())

        check(t0 + limit + 1)

        assertEquals(t0, alerts.savedAlerts.single().excursionStart)
    }

    @Test
    fun aJourneyWithNoReadingsYetIsCheckedWhenItWouldGoQuiet() = runBlocking {
        shipments.saveShipment(moving())

        assertEquals(t0 + limit + 1, check(t0))
    }

    @Test
    fun aHotLoggerThatStopsIsCountedUpToNow() = runBlocking {
        shipments.saveShipment(moving())
        telemetry.saveTemperatures(ownReadings(3 * minute, 12.0)).getOrThrow()

        check(t0 + 3 * minute + limit)

        assertEquals(ShipmentStatus.QUARANTINED, shipments.savedShipments.getValue("SHIP-1").status)
    }

    // Found on the dev project (27 Nov): a receiver's phone, opened after a night switched off, recorded "No readings for
    // 718:54" on the server for SHIP-49A74F, whose readings were all there, never more than 125 s apart. It measured
    // from the newest reading it had downloaded, so the silence was its own download's, not the logger's.
    @Test
    fun aShipmentThisPhoneOnlyWatchesRaisesNoGapForReadingsNotYetDownloaded() = runBlocking {
        val lastReading = t0 + 5 * minute
        prepare(lastReadingAt = lastReading)

        val next = LoggerSilenceCheck(shipments, telemetry, engine, recordsHere = { false }) { lastReading + 12 * 60 * minute }.run()

        assertTrue(alerts.savedAlerts.toString(), alerts.savedAlerts.isEmpty())
        assertNull("nothing to wake up for: the next reading comes with a download", next)
    }

    @Test
    fun aShipmentThisPhoneOnlyWatchesStillRaisesWhatItsDownloadedReadingsShow() = runBlocking {
        shipments.saveShipment(moving())
        telemetry.saveTemperatures(ownReadings(30 * minute, 12.0)).getOrThrow()

        LoggerSilenceCheck(shipments, telemetry, engine, recordsHere = { false }) { t0 + 12 * 60 * minute }.run()

        assertEquals(listOf(AlertType.TEMPERATURE_BREACH), alerts.savedAlerts.map { it.type })
    }

    @Test
    fun shipmentsThatAreNotMovingAreLeftAlone() = runBlocking {
        listOf(ShipmentStatus.CREATED, ShipmentStatus.DISPATCHED, ShipmentStatus.ACCEPTED, ShipmentStatus.QUARANTINED)
            .forEach { status ->
                shipments.saveShipment(moving(status))

                assertNull(check(t0 + 60 * minute))
                assertTrue(alerts.savedAlerts.isEmpty())
            }
    }

    @Test
    fun nothingCanBeSaidAboutAJourneyWithNoStartAndNoReadings() = runBlocking {
        shipments.saveShipment(moving(journeyStartedAt = null))

        assertNull(check(t0 + 60 * minute))
    }

    @Test
    fun aFailedSaveIsNotHiddenSoTheCheckCanBeRetried() {
        runBlocking { prepare() }
        alerts.shouldFail = true

        assertThrows(RuntimeException::class.java) {
            runBlocking { check(t0 + 60 * minute) }
        }
    }
}
