package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.ShipmentDao
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.data.local.entities.ShipmentEntity
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
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
     * Every shipment change on this device passes through here, so a bad one is refused whichever
     * screen or engine attempts it. A new shipment starts as CREATED: one seen part-way through its
     * journey comes from the server through [adoptRemoteShipment].
     */
    override suspend fun saveShipment(shipment: Shipment): Result<Unit> = runCatching {
        val current = shipmentDao.getShipmentDirect(shipment.id)
        if (current == null) {
            if (shipment.status != ShipmentStatus.CREATED) {
                throw IllegalTransitionException(ShipmentStatus.CREATED, shipment.status)
            }
        } else {
            requireSameManifest(current, shipment)
            if (current.status != shipment.status &&
                !ShipmentLifecycle.canTransition(current.status, shipment.status)
            ) {
                throw IllegalTransitionException(current.status, shipment.status)
            }
        }
        shipmentDao.upsertShipment(shipment.toEntity(SyncStatus.PENDING))
    }

    override suspend fun adoptRemoteShipment(shipment: Shipment): Result<Unit> = runCatching {
        val current = shipmentDao.getShipmentDirect(shipment.id)
        if (current != null) {
            requireSameManifest(current, shipment)
            if (!ShipmentLifecycle.canReach(current.status, shipment.status)) {
                throw IllegalTransitionException(current.status, shipment.status)
            }
        }
        shipmentDao.upsertShipment(
            shipment.copy(
                journeyStartedAt = shipment.journeyStartedAt ?: current?.journeyStartedAt,
                genesisHash = shipment.genesisHash ?: current?.genesisHash
            ).toEntity(SyncStatus.SYNCED)
        )
    }

    /**
     * These decide which rules apply and which readings belong, so changing one would change the
     * outcome of a shipment already under way. The logger can still be chosen until dispatch.
     */
    private fun requireSameManifest(current: ShipmentEntity, shipment: Shipment) {
        val loggerLocked = current.status != ShipmentStatus.CREATED
        check(current.productProfileId == shipment.productProfileId) { "A shipment's product cannot change." }
        check(!loggerLocked || current.loggerId == shipment.loggerId) { "A shipment's logger cannot change." }
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
