package com.coldchain.handshake.data.repository

import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe shared local storage implementation of [TelemetryRepository].
 *
 * Persists all fields of [TemperatureEvent] in memory/process cache.
 * MANDATORY: Strictly guarantees chronological sorting by timestamp ASC for HashChainService.
 */
class LocalTelemetryRepository : TelemetryRepository {
    private val eventsMap = ConcurrentHashMap<String, MutableList<TemperatureEvent>>()
    private val stateFlow = MutableStateFlow<Map<String, List<TemperatureEvent>>>(emptyMap())

    override suspend fun saveTemperature(event: TemperatureEvent): Result<Unit> {
        synchronized(this) {
            val list = eventsMap.getOrPut(event.shipmentId) { mutableListOf() }
            val index = list.indexOfFirst { it.id == event.id }
            if (index >= 0) {
                list[index] = event
            } else {
                list.add(event)
            }
            // Mandatory: Sort strictly by timestamp ASC
            list.sortBy { it.timestamp }
            stateFlow.value = eventsMap.mapValues { it.value.toList() }
        }
        return Result.success(Unit)
    }

    override fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>> {
        return stateFlow.map { map ->
            map[shipmentId]?.sortedBy { it.timestamp } ?: emptyList()
        }
    }

    fun getAllEventsDirect(shipmentId: String): List<TemperatureEvent> {
        return eventsMap[shipmentId]?.toList() ?: emptyList()
    }
}

/**
 * Thread-safe shared local storage implementation of [ShipmentRepository].
 */
class LocalShipmentRepository : ShipmentRepository {
    private val shipmentsMap = ConcurrentHashMap<String, Shipment>()
    private val stateFlow = MutableStateFlow<Map<String, Shipment>>(emptyMap())

    override suspend fun saveShipment(shipment: Shipment): Result<Unit> {
        synchronized(this) {
            shipmentsMap[shipment.id] = shipment
            stateFlow.value = shipmentsMap.toMap()
        }
        return Result.success(Unit)
    }

    override fun getShipment(id: String): Flow<Shipment?> {
        return stateFlow.map { it[id] }
    }

    override fun getAllShipments(): Flow<List<Shipment>> {
        return stateFlow.map { it.values.toList() }
    }

    fun getShipmentDirect(id: String): Shipment? = shipmentsMap[id]
}

/**
 * Thread-safe shared local storage implementation of [HandoverRepository].
 */
class LocalHandoverRepository : HandoverRepository {
    private val handoversMap = ConcurrentHashMap<String, Handover>()
    private val stateFlow = MutableStateFlow<Map<String, Handover>>(emptyMap())

    override suspend fun saveHandover(handover: Handover): Result<Unit> {
        synchronized(this) {
            handoversMap[handover.shipmentId] = handover
            stateFlow.value = handoversMap.toMap()
        }
        return Result.success(Unit)
    }

    override fun getHandover(shipmentId: String): Flow<Handover?> {
        return stateFlow.map { it[shipmentId] }
    }

    fun getHandoverDirect(shipmentId: String): Handover? = handoversMap[shipmentId]
}

