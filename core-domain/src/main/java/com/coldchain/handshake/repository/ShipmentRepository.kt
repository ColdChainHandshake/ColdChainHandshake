package com.coldchain.handshake.repository

import com.coldchain.handshake.models.Shipment
import kotlinx.coroutines.flow.Flow

/**
 * Shared repository contract for shipment management.
 */
interface ShipmentRepository {
    suspend fun saveShipment(shipment: Shipment): Result<Unit>

    /**
     * Takes on a status another device has already recorded, which may be several steps ahead.
     */
    suspend fun adoptRemoteShipment(shipment: Shipment): Result<Unit> = saveShipment(shipment)
    fun getShipment(id: String): Flow<Shipment?>
    fun getAllShipments(): Flow<List<Shipment>>
}
