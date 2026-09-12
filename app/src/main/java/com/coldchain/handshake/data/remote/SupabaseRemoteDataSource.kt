package com.coldchain.handshake.data.remote

import com.coldchain.handshake.data.remote.dto.RemoteAlertDto
import com.coldchain.handshake.data.remote.dto.RemoteHandoverDto
import com.coldchain.handshake.data.remote.dto.RemoteShipmentDto
import com.coldchain.handshake.data.remote.dto.RemoteShipmentCustodyDto
import com.coldchain.handshake.data.remote.dto.RemoteShipmentLocationDto
import com.coldchain.handshake.data.remote.dto.RemoteTemperatureEventDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

/**
 * Remote data source for Supabase interactions.
 * Executes idempotent upserts keyed by primary logical IDs,
 * ensuring retried sync operations never duplicate remote records.
 */
open class SupabaseRemoteDataSource(
    private val clientProvider: () -> SupabaseClient = { SupabaseClientProvider.getClient() }
) {

    companion object {
        const val TABLE_SHIPMENTS = "shipments"
        const val TABLE_TEMPERATURE_EVENTS = "temperature_events"
        const val TABLE_ALERTS = "alerts"
        const val TABLE_HANDOVERS = "handovers"
        const val TABLE_SHIPMENT_LOCATIONS = "shipment_locations"
        const val TABLE_SHIPMENT_CUSTODY = "shipment_custody"
    }

    private val client: SupabaseClient get() = clientProvider()

    // -------------------------------------------------------------------------
    // Idempotent Remote Writes (Upsert by logical ID)
    // -------------------------------------------------------------------------

    open suspend fun upsertShipments(shipments: List<RemoteShipmentDto>): Result<Unit> = runCatching {
        if (shipments.isEmpty()) return@runCatching
        client.postgrest.from(TABLE_SHIPMENTS).upsert(shipments, onConflict = "id")
        Unit
    }

    open suspend fun upsertShipment(shipment: RemoteShipmentDto): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_SHIPMENTS).upsert(shipment, onConflict = "id")
        Unit
    }

    open suspend fun upsertTemperatureEvents(events: List<RemoteTemperatureEventDto>): Result<Unit> = runCatching {
        if (events.isEmpty()) return@runCatching
        // Idempotent upsert preserves append-only event stream and avoids duplicate rows on retry.
        client.postgrest.from(TABLE_TEMPERATURE_EVENTS).upsert(events, onConflict = "id")
        Unit
    }

    open suspend fun upsertTemperatureEvent(event: RemoteTemperatureEventDto): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_TEMPERATURE_EVENTS).upsert(event, onConflict = "id")
        Unit
    }

    open suspend fun upsertAlerts(alerts: List<RemoteAlertDto>): Result<Unit> = runCatching {
        if (alerts.isEmpty()) return@runCatching
        client.postgrest.from(TABLE_ALERTS).upsert(alerts, onConflict = "id")
        Unit
    }

    open suspend fun upsertAlert(alert: RemoteAlertDto): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_ALERTS).upsert(alert, onConflict = "id")
        Unit
    }

    open suspend fun upsertHandover(handover: RemoteHandoverDto): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_HANDOVERS).upsert(handover, onConflict = "id")
        Unit
    }

    open suspend fun upsertShipmentLocation(location: RemoteShipmentLocationDto): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_SHIPMENT_LOCATIONS).upsert(location, onConflict = "id")
        Unit
    }

    open suspend fun upsertCustody(custody: RemoteShipmentCustodyDto): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_SHIPMENT_CUSTODY).upsert(custody, onConflict = "shipment_id")
        Unit
    }

    suspend fun getCustodyState(shipmentId: String): Result<RemoteShipmentCustodyDto?> = runCatching {
        client.postgrest.from(TABLE_SHIPMENT_CUSTODY)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
            }
            .decodeSingleOrNull<RemoteShipmentCustodyDto>()
    }

    suspend fun transferCustody(
        shipmentId: String,
        newDeviceId: String,
        custodyState: String = "TRANSFERRED"
    ): Result<Unit> = upsertCustody(
        RemoteShipmentCustodyDto(
            shipmentId = shipmentId,
            activeDeviceId = newDeviceId,
            custodyState = custodyState,
            updatedAt = System.currentTimeMillis()
        )
    )



    // -------------------------------------------------------------------------
    // Remote Reads (Verification & Cloud Visibility)
    // -------------------------------------------------------------------------

    suspend fun getShipments(): Result<List<RemoteShipmentDto>> = runCatching {
        client.postgrest.from(TABLE_SHIPMENTS).select().decodeList<RemoteShipmentDto>()
    }

    suspend fun getShipment(id: String): Result<RemoteShipmentDto?> = runCatching {
        client.postgrest.from(TABLE_SHIPMENTS)
            .select {
                filter {
                    eq("id", id)
                }
            }
            .decodeSingleOrNull<RemoteShipmentDto>()
    }

    suspend fun getLatestShipmentLocation(shipmentId: String): Result<RemoteShipmentLocationDto?> = runCatching {
        client.postgrest.from(TABLE_SHIPMENT_LOCATIONS)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
            }
            .decodeList<RemoteShipmentLocationDto>()
            .maxByOrNull { it.timestamp }
    }

    suspend fun getTemperatureEvents(shipmentId: String): Result<List<RemoteTemperatureEventDto>> = runCatching {
        client.postgrest.from(TABLE_TEMPERATURE_EVENTS)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
                order("timestamp", Order.ASCENDING)
            }
            .decodeList<RemoteTemperatureEventDto>()
    }

    suspend fun getAlerts(shipmentId: String): Result<List<RemoteAlertDto>> = runCatching {
        client.postgrest.from(TABLE_ALERTS)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
                order("timestamp", Order.ASCENDING)
            }
            .decodeList<RemoteAlertDto>()
    }

    suspend fun getHandover(shipmentId: String): Result<RemoteHandoverDto?> = runCatching {
        client.postgrest.from(TABLE_HANDOVERS)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
            }
            .decodeSingleOrNull<RemoteHandoverDto>()
    }

    // -------------------------------------------------------------------------
    // Safe Remote Cleanup (Used for Test Data Hygiene)
    // -------------------------------------------------------------------------

    suspend fun deleteShipment(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_SHIPMENTS).delete { filter { eq("id", id) } }
        Unit
    }

    suspend fun deleteTemperatureEvent(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_TEMPERATURE_EVENTS).delete { filter { eq("id", id) } }
        Unit
    }

    suspend fun deleteAlert(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_ALERTS).delete { filter { eq("id", id) } }
        Unit
    }

    suspend fun deleteHandover(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE_HANDOVERS).delete { filter { eq("id", id) } }
        Unit
    }
}
