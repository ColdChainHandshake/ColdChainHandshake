package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.ShipmentDao
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Concrete implementation of ShipmentRepository backed by Room persistence.
 * Persists shipments locally first with PENDING sync status.
 */
class ShipmentRepositoryImpl(
    private val shipmentDao: ShipmentDao
) : ShipmentRepository {

    override suspend fun saveShipment(shipment: Shipment): Result<Unit> = runCatching {
        shipmentDao.upsertShipment(shipment.toEntity(SyncStatus.PENDING))
    }

    override fun getShipment(id: String): Flow<Shipment?> {
        return shipmentDao.getShipment(id).map { it?.toDomain() }
    }

    override fun getAllShipments(): Flow<List<Shipment>> {
        return shipmentDao.getAllShipments().map { entities ->
            entities.map { it.toDomain() }
        }
    }
}
