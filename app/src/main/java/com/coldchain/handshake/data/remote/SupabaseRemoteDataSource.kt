package com.coldchain.handshake.data.remote

import com.coldchain.handshake.data.remote.dto.RemoteAlertDto
import com.coldchain.handshake.data.remote.dto.RemoteHandoverDto
import com.coldchain.handshake.data.remote.dto.RemoteShipmentDto
import com.coldchain.handshake.data.remote.dto.RemoteTemperatureEventDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

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



    // -------------------------------------------------------------------------
    // Remote Reads (Verification & Cloud Visibility)
    // -------------------------------------------------------------------------

    suspend fun getShipments(): Result<List<RemoteShipmentDto>> = runCatching {
        client.postgrest.from(TABLE_SHIPMENTS).select().decodeList<RemoteShipmentDto>()
    }

    suspend fun getTemperatureEvents(shipmentId: String): Result<List<RemoteTemperatureEventDto>> = runCatching {
        client.postgrest.from(TABLE_TEMPERATURE_EVENTS)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
            }
            .decodeList<RemoteTemperatureEventDto>()
    }

    suspend fun getAlerts(shipmentId: String): Result<List<RemoteAlertDto>> = runCatching {
        client.postgrest.from(TABLE_ALERTS)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
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
}
