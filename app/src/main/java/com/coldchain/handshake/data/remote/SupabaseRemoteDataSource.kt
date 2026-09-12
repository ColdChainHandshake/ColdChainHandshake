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

    open suspend fun getCustodyState(shipmentId: String): Result<RemoteShipmentCustodyDto?> = runCatching {
        android.util.Log.d("CustodyTransfer", "Querying custody state for shipment: $shipmentId from table '${TABLE_SHIPMENT_CUSTODY}'")
        val result = client.postgrest.from(TABLE_SHIPMENT_CUSTODY)
            .select {
                filter {
                    eq("shipment_id", shipmentId)
                }
            }
            .decodeSingleOrNull<RemoteShipmentCustodyDto>()
        android.util.Log.d("CustodyTransfer", "getCustodyState($shipmentId) result: $result")
        result
    }.onFailure { ex ->
        android.util.Log.e("CustodyTransfer", "getCustodyState($shipmentId) FAILED: ${ex.javaClass.simpleName} - ${ex.message}")
    }

    open suspend fun transferCustody(
        shipmentId: String,
        newDeviceId: String,
        custodyState: String = "TRANSFERRED"
    ): Result<Unit> = transferCustodyWithConfirmation(shipmentId, newDeviceId, custodyState).map { Unit }

    open suspend fun transferCustodyWithConfirmation(
        shipmentId: String,
        newDeviceId: String,
        custodyState: String = "TRANSFERRED"
    ): Result<RemoteShipmentCustodyDto> {
        val tag = "CustodyTransfer"
        android.util.Log.d(tag, "=== INITIATING CUSTODY TRANSFER ===")
        android.util.Log.d(tag, "Shipment ID: $shipmentId")
        android.util.Log.d(tag, "Local Device ID: $newDeviceId")
        android.util.Log.d(tag, "Target Custody State: $custodyState")
        android.util.Log.d(tag, "Target Supabase Table: $TABLE_SHIPMENT_CUSTODY")
        android.util.Log.d(tag, "HTTP Operation: UPSERT (onConflict = shipment_id)")

        val custodyDto = RemoteShipmentCustodyDto(
            shipmentId = shipmentId,
            activeDeviceId = newDeviceId,
            custodyState = custodyState,
            updatedAt = System.currentTimeMillis()
        )

        // 1. Execute UPSERT
        val upsertResult = runCatching {
            client.postgrest.from(TABLE_SHIPMENT_CUSTODY).upsert(custodyDto, onConflict = "shipment_id")
            Unit
        }

        if (upsertResult.isFailure) {
            val ex = upsertResult.exceptionOrNull() ?: Exception("Unknown upsert failure")
            android.util.Log.e(tag, "Custody UPSERT FAILED")
            android.util.Log.e(tag, "Exception Type: ${ex.javaClass.name}")
            android.util.Log.e(tag, "Exception Message: ${ex.message}")
            return Result.failure(ex)
        }

        android.util.Log.d(tag, "Custody UPSERT operation completed. Executing remote confirmation query...")

        // 2. Execute CONFIRMATION query
        val confirmResult = runCatching {
            client.postgrest.from(TABLE_SHIPMENT_CUSTODY)
                .select {
                    filter {
                        eq("shipment_id", shipmentId)
                    }
                }
                .decodeSingleOrNull<RemoteShipmentCustodyDto>()
        }

        if (confirmResult.isFailure) {
            val ex = confirmResult.exceptionOrNull() ?: Exception("Unknown query failure")
            android.util.Log.e(tag, "Custody confirmation query FAILED: ${ex.javaClass.name} - ${ex.message}")
            return Result.failure(ex)
        }

        val confirmed = confirmResult.getOrNull()
        if (confirmed == null) {
            android.util.Log.e(tag, "Custody confirmation returned ZERO rows! The record was not persisted remotely.")
            return Result.failure(IllegalStateException("Confirmation query returned zero rows for shipment $shipmentId on table $TABLE_SHIPMENT_CUSTODY"))
        }

        android.util.Log.d(tag, "Confirmation query result: active_device_id=${confirmed.activeDeviceId}, state=${confirmed.custodyState}, updated_at=${confirmed.updatedAt}")

        if (confirmed.activeDeviceId != newDeviceId || confirmed.custodyState != custodyState) {
            android.util.Log.e(tag, "Custody confirmation state mismatch: expected ($newDeviceId, $custodyState) but found (${confirmed.activeDeviceId}, ${confirmed.custodyState})")
            return Result.failure(IllegalStateException("Custody state mismatch on remote database: expected device $newDeviceId, found ${confirmed.activeDeviceId}"))
        }

        android.util.Log.d(tag, "=== CUSTODY TRANSFER CONFIRMED ON REMOTE SUPABASE ===")
        return Result.success(confirmed)
    }



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
                order("id", Order.ASCENDING)
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
