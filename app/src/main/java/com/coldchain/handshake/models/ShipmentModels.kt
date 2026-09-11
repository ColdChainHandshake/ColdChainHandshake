package com.coldchain.handshake.models

/**
 * Official shipment lifecycle states.
 */
enum class ShipmentStatus {
    CREATED,
    DISPATCHED,
    IN_TRANSIT,
    ARRIVED,
    INSPECTED,
    ACCEPTED,
    QUARANTINED,
    REPLACEMENT_REQUESTED
}

/**
 * Official Shipment model.
 */
data class Shipment(
    val id: String,
    val qrCode: String,
    val loggerId: String,
    val origin: String,
    val destination: String,
    val workerId: String,
    val status: ShipmentStatus = ShipmentStatus.CREATED
)
