package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.ShipmentDao
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.safety.IllegalTransitionException
import com.coldchain.handshake.safety.ShipmentLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Concrete implementation of ShipmentRepository backed by Room persistence.
 * Persists shipments locally first with PENDING sync status.
 */
class ShipmentRepositoryImpl(
    private val shipmentDao: ShipmentDao
) : ShipmentRepository {

    /**
     * Every status change on this device passes through here, so an illegal move is refused
     * whichever screen or engine attempts it. A shipment seen for the first time keeps its status,
     * because a receiver imports it part-way through its journey.
     */
    override suspend fun saveShipment(shipment: Shipment): Result<Unit> = runCatching {
        val current = shipmentDao.getShipmentDirect(shipment.id)?.status
        if (current != null && current != shipment.status && !ShipmentLifecycle.canTransition(current, shipment.status)) {
            throw IllegalTransitionException(current, shipment.status)
        }
        shipmentDao.upsertShipment(shipment.toEntity(SyncStatus.PENDING))
    }

    override suspend fun adoptRemoteShipment(shipment: Shipment): Result<Unit> = runCatching {
        val current = shipmentDao.getShipmentDirect(shipment.id)?.status
        if (current != null && !ShipmentLifecycle.canReach(current, shipment.status)) {
            throw IllegalTransitionException(current, shipment.status)
        }
        shipmentDao.upsertShipment(shipment.toEntity(SyncStatus.SYNCED))
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
