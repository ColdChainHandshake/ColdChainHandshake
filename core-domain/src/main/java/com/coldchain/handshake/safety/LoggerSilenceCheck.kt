package com.coldchain.handshake.safety

import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * Evaluation otherwise runs only when a reading arrives, so a logger that stopped would raise nothing.
 * This runs on a timer instead. It holds no state of its own, so it recovers from process death.
 *
 * Only a shipment whose readings this phone records ([recordsHere]) can go quiet here. One it only watches is
 * checked up to its newest downloaded reading: whether no reading has reached the server lately is for the
 * watching screen to say, not an alert on the shipment's record.
 */
class LoggerSilenceCheck(
    private val shipmentRepository: ShipmentRepository,
    private val telemetryRepository: TelemetryRepository,
    private val safetyEngine: SafetyEngine,
    private val recordsHere: suspend (Shipment) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Evaluates every moving shipment up to now, raising an alert for any that has gone quiet.
     *
     * @return when to look again, or null if nothing is moving. A shipment already quiet is still
     *   looked at, because its logger may come back and go quiet again.
     * @throws Exception if an alert couldn't be saved, so the caller can retry.
     */
    suspend fun run(): Long? {
        val now = clock()
        val moving = shipmentRepository.getAllShipments().first()
            .filter { it.status == ShipmentStatus.IN_TRANSIT }

        return moving.mapNotNull { shipment ->
            val events = telemetryRepository.getTemperatures(shipment.id).firstOrNull().orEmpty()
            // A phone that only watches a shipment holds what it has downloaded so far, so the time since its newest
            // reading is its own download's silence, not the logger's. Its readings are still checked, up to the last.
            val recordedHere = recordsHere(shipment)
            val windowEnd = if (recordedHere) now else events.maxOfOrNull { it.timestamp } ?: return@mapNotNull null
            safetyEngine.processTelemetryAndEnforceSafety(shipment, events, now, windowEnd = windowEnd)
                .alertSaveResult?.getOrThrow()
            if (!recordedHere) return@mapNotNull null

            val silenceLimit = ProductProfiles.byId(shipment.productProfileId).maxSilenceMs
            val lastHeard = events.maxOfOrNull { it.timestamp } ?: shipment.journeyStartedAt
            lastHeard?.let { (it + silenceLimit + 1).coerceAtLeast(now + silenceLimit) }
        }.minOrNull()
    }
}
