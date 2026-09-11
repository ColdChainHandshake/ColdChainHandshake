package com.coldchain.handshake.repository

import com.coldchain.handshake.models.Shipment
import kotlinx.coroutines.flow.Flow

/**
 * Shared repository contract for shipment management.
 */
interface ShipmentRepository {
    suspend fun saveShipment(shipment: Shipment): Result<Unit>
    fun getShipment(id: String): Flow<Shipment?>
    fun getAllShipments(): Flow<List<Shipment>>
}
