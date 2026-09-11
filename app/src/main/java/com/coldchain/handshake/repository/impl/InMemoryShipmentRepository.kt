package com.coldchain.handshake.repository.impl

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.ShipmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Thread-safe in-memory implementation of [ShipmentRepository].
 * Serves as the baseline shared repository for shipment lifecycle management.
 */
class InMemoryShipmentRepository : ShipmentRepository {

    private val shipmentsFlow = MutableStateFlow<Map<String, Shipment>>(emptyMap())

    override suspend fun saveShipment(shipment: Shipment): Result<Unit> {
        return try {
            shipmentsFlow.value = shipmentsFlow.value + (shipment.id to shipment)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getShipment(id: String): Flow<Shipment?> {
        return shipmentsFlow.map { map -> map[id] }
    }

    override fun getAllShipments(): Flow<List<Shipment>> {
        return shipmentsFlow.map { map -> map.values.toList() }
    }
}
