package com.coldchain.handshake.data.remote.dto

import com.coldchain.handshake.data.local.entities.AlertEntity
import com.coldchain.handshake.data.local.entities.HandoverEntity
import com.coldchain.handshake.data.local.entities.ShipmentEntity
import com.coldchain.handshake.data.local.entities.TemperatureEventEntity
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Remote DTO for Supabase 'shipments' table.
 */
@Serializable
data class RemoteShipmentDto(
    @SerialName("id") val id: String,
    @SerialName("qr_code") val qrCode: String,
    @SerialName("logger_id") val loggerId: String,
    @SerialName("origin") val origin: String,
    @SerialName("destination") val destination: String,
    @SerialName("worker_id") val workerId: String,
    @SerialName("status") val status: String
)

fun Shipment.toRemoteDto(): RemoteShipmentDto = RemoteShipmentDto(
    id = id,
    qrCode = qrCode,
    loggerId = loggerId,
    origin = origin,
    destination = destination,
    workerId = workerId,
    status = status.name
)

fun ShipmentEntity.toRemoteDto(): RemoteShipmentDto = RemoteShipmentDto(
    id = id,
    qrCode = qrCode,
    loggerId = loggerId,
    origin = origin,
    destination = destination,
    workerId = workerId,
    status = status.name
)

fun RemoteShipmentDto.toDomain(): Shipment = Shipment(
    id = id,
    qrCode = qrCode,
    loggerId = loggerId,
    origin = origin,
    destination = destination,
    workerId = workerId,
    status = runCatching { ShipmentStatus.valueOf(status) }.getOrDefault(ShipmentStatus.CREATED)
)

/**
 * Remote DTO for Supabase 'temperature_events' table.
 */
@Serializable
data class RemoteTemperatureEventDto(
    @SerialName("id") val id: String,
    @SerialName("shipment_id") val shipmentId: String,
    @SerialName("logger_id") val loggerId: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("temperature") val temperature: Double,
    @SerialName("previous_hash") val previousHash: String,
    @SerialName("current_hash") val currentHash: String,
    @SerialName("sync_status") val syncStatus: String
)

fun TemperatureEvent.toRemoteDto(): RemoteTemperatureEventDto = RemoteTemperatureEventDto(
    id = id,
    shipmentId = shipmentId,
    loggerId = loggerId,
    timestamp = timestamp,
    temperature = temperature,
    previousHash = previousHash,
    currentHash = currentHash,
    syncStatus = syncStatus.name
)

fun TemperatureEventEntity.toRemoteDto(): RemoteTemperatureEventDto = RemoteTemperatureEventDto(
    id = id,
    shipmentId = shipmentId,
    loggerId = loggerId,
    timestamp = timestamp,
    temperature = temperature,
    previousHash = previousHash,
    currentHash = currentHash,
    syncStatus = syncStatus.name
)

fun RemoteTemperatureEventDto.toDomain(): TemperatureEvent = TemperatureEvent(
    id = id,
    shipmentId = shipmentId,
    loggerId = loggerId,
    timestamp = timestamp,
    temperature = temperature,
    previousHash = previousHash,
    currentHash = currentHash,
    syncStatus = runCatching { SyncStatus.valueOf(syncStatus) }.getOrDefault(SyncStatus.SYNCED)
)

/**
 * Remote DTO for Supabase 'alerts' table.
 */
@Serializable
data class RemoteAlertDto(
    @SerialName("id") val id: String,
    @SerialName("shipment_id") val shipmentId: String,
    @SerialName("type") val type: String,
    @SerialName("message") val message: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("escalation_level") val escalationLevel: String,
    @SerialName("acknowledged") val acknowledged: Boolean
)

fun Alert.toRemoteDto(): RemoteAlertDto = RemoteAlertDto(
    id = id,
    shipmentId = shipmentId,
    type = type.name,
    message = message,
    timestamp = timestamp,
    escalationLevel = escalationLevel.name,
    acknowledged = acknowledged
)

fun AlertEntity.toRemoteDto(): RemoteAlertDto = RemoteAlertDto(
    id = id,
    shipmentId = shipmentId,
    type = type.name,
    message = message,
    timestamp = timestamp,
    escalationLevel = escalationLevel.name,
    acknowledged = acknowledged
)

fun RemoteAlertDto.toDomain(): Alert = Alert(
    id = id,
    shipmentId = shipmentId,
    type = runCatching { AlertType.valueOf(type) }.getOrDefault(AlertType.OPERATIONAL_WARNING),
    message = message,
    timestamp = timestamp,
    escalationLevel = runCatching { EscalationLevel.valueOf(escalationLevel) }.getOrDefault(EscalationLevel.WORKER),
    acknowledged = acknowledged
)

/**
 * Remote DTO for Supabase 'handovers' table.
 */
@Serializable
data class RemoteHandoverDto(
    @SerialName("id") val id: String,
    @SerialName("shipment_id") val shipmentId: String,
    @SerialName("worker_signed") val workerSigned: Boolean,
    @SerialName("pharmacist_signed") val pharmacistSigned: Boolean,
    @SerialName("integrity_verified") val integrityVerified: Boolean,
    @SerialName("temperature_passed") val temperaturePassed: Boolean,
    @SerialName("verdict") val verdict: String,
    @SerialName("timestamp") val timestamp: Long
)

fun Handover.toRemoteDto(): RemoteHandoverDto = RemoteHandoverDto(
    id = id,
    shipmentId = shipmentId,
    workerSigned = workerSigned,
    pharmacistSigned = pharmacistSigned,
    integrityVerified = integrityVerified,
    temperaturePassed = temperaturePassed,
    verdict = verdict.name,
    timestamp = timestamp
)

fun HandoverEntity.toRemoteDto(): RemoteHandoverDto = RemoteHandoverDto(
    id = id,
    shipmentId = shipmentId,
    workerSigned = workerSigned,
    pharmacistSigned = pharmacistSigned,
    integrityVerified = integrityVerified,
    temperaturePassed = temperaturePassed,
    verdict = verdict.name,
    timestamp = timestamp
)

fun RemoteHandoverDto.toDomain(): Handover = Handover(
    id = id,
    shipmentId = shipmentId,
    workerSigned = workerSigned,
    pharmacistSigned = pharmacistSigned,
    integrityVerified = integrityVerified,
    temperaturePassed = temperaturePassed,
    verdict = runCatching { HandoverVerdict.valueOf(verdict) }.getOrDefault(HandoverVerdict.FAIL),
    timestamp = timestamp
)
