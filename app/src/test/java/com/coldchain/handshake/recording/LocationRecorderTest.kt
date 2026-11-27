package com.coldchain.handshake.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MASTER 5.5 item 2: the recording service records where the shipment is, as well as its temperature, and queues each
 * point in Room until the server has it. Only the shipment this phone records, and only one the server holds, since a
 * point has nowhere else to go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationRecorderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val simulator = TemperatureSimulator(
        TelemetryRepositoryImpl(db.temperatureEventDao()), dispatcher = Dispatchers.Default, wallClock = System::currentTimeMillis
    )
    private var ids = 0
    private val recorder = LocationRecorder(simulator, db.gpsPointDao()) { "gps-${++ids}" }

    private val onServer = Shipment(
        "SHIP-1", "SHIP-1", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id,
        journeyStartedAt = 1_700_000_000_000L, genesisHash = "a".repeat(64)
    )
    private val fix = LocationFix(latitude = 9.98, longitude = 76.28, accuracyMeters = 12f, time = 1_700_000_060_000L)

    @After
    fun tearDown() {
        simulator.stopContinuousSimulation()
        db.close()
    }

    private fun recording(shipment: Shipment) {
        simulator.attachShipment(shipment)
        simulator.startContinuousSimulation(intervalMs = 60_000L)
    }

    @Test
    fun aFixWhileRecordingAShipmentOnTheServerIsQueuedForIt() = runBlocking {
        recording(onServer)

        assertTrue(recorder.record(fix))

        val queued = db.gpsPointDao().getPending().single()
        assertEquals("gps-1", queued.id)
        assertEquals("SHIP-1", queued.shipmentId)
        assertEquals(9.98, queued.latitude, 0.0)
        assertEquals(76.28, queued.longitude, 0.0)
        assertEquals(12f, queued.accuracyMeters, 0f)
        assertEquals(fix.time, queued.deviceTimestamp)
    }

    @Test
    fun aFixWhileOnlyWatchingIsNotQueued() = runBlocking {
        simulator.attachShipmentForMonitoring(onServer)

        assertFalse(recorder.record(fix))
        assertTrue(db.gpsPointDao().getPending().isEmpty())
    }

    @Test
    fun aFixWithRecordingPausedIsNotQueued() = runBlocking {
        simulator.attachShipment(onServer)

        assertFalse(recorder.record(fix))
        assertTrue(db.gpsPointDao().getPending().isEmpty())
    }

    @Test
    fun aFixForAShipmentKeptOnThisPhoneAloneIsNotQueued() = runBlocking {
        recording(onServer.copy(id = "LOCAL-1", genesisHash = null))

        assertFalse(recorder.record(fix))
        assertTrue(db.gpsPointDao().getPending().isEmpty())
    }
}
