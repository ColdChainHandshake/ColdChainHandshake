package com.coldchain.handshake.recording

import com.coldchain.handshake.data.local.dao.GpsPointDao
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.repository.GpsPoint
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** One location from the phone: where it was, how sure, and when (the phone's clock). */
data class LocationFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val time: Long)

/** A location this phone recorded, and whether the server has it yet. */
data class RecordedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val deviceTimestamp: Long,
    val sent: Boolean
)

/** This phone's newest recorded location for a shipment, as the queue holds it. */
fun interface RecordedLocations {
    fun latest(shipmentId: String): Flow<RecordedLocation?>
}

/**
 * Where fixes come from while this phone records a shipment: the fused location provider in the app, a fake in
 * tests. [start] is called only once the recording service runs with the `location` type.
 */
interface LocationSource {
    fun start(onFix: (LocationFix) -> Unit)
    fun stop()
}

/**
 * Queues each fix for the shipment this phone is recording, in Room, until the sync sends it ([GpsPoint], sent by
 * `SyncServiceImpl` like the readings). A fix while nothing records, while only watching, or for a shipment kept on
 * this phone alone has nowhere to go and is dropped.
 */
class LocationRecorder(
    private val simulator: TemperatureSimulator,
    private val points: GpsPointDao,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    /** True if [fix] was queued. */
    suspend fun record(fix: LocationFix): Boolean {
        val shipment = simulator.activeShipment.value ?: return false
        val recording = simulator.isRunning.value && !simulator.isMonitorOnly.value
        if (!recording || shipment.genesisHash == null) return false
        points.insert(GpsPoint(newId(), shipment.id, fix.latitude, fix.longitude, fix.accuracyMeters, fix.time).toEntity())
        return true
    }
}
