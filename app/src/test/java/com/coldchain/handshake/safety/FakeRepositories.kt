package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.repository.ShipmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory test fake for [AlertRepository] supporting failure injection.
 */
class FakeAlertRepository : AlertRepository {
    val savedAlerts = mutableListOf<Alert>()
    private val alertsFlow = MutableStateFlow<List<Alert>>(emptyList())
    var shouldFail: Boolean = false
    var failureException: Throwable = RuntimeException("Alert repository write failure")

    override suspend fun saveAlert(alert: Alert): Result<Unit> {
        if (shouldFail) {
            return Result.failure(failureException)
        }
        val existingIndex = savedAlerts.indexOfFirst { it.id == alert.id }
        if (existingIndex >= 0) {
            savedAlerts[existingIndex] = alert
        } else {
            savedAlerts.add(alert)
        }
        alertsFlow.value = savedAlerts.toList()
        return Result.success(Unit)
    }

    override fun getActiveAlerts(shipmentId: String): Flow<List<Alert>> {
        return alertsFlow.map { list ->
            list.filter { it.shipmentId == shipmentId && !it.acknowledged }
        }
    }
}

/**
 * In-memory test fake for [ShipmentRepository] supporting failure injection.
 */
class FakeShipmentRepository : ShipmentRepository {
    val savedShipments = mutableMapOf<String, Shipment>()
    private val shipmentsFlow = MutableStateFlow<List<Shipment>>(emptyList())
    var shouldFail: Boolean = false
    var failureException: Throwable = RuntimeException("Shipment repository write failure")

    override suspend fun saveShipment(shipment: Shipment): Result<Unit> {
        if (shouldFail) {
            return Result.failure(failureException)
        }
        savedShipments[shipment.id] = shipment
        shipmentsFlow.value = savedShipments.values.toList()
        return Result.success(Unit)
    }

    override fun getShipment(id: String): Flow<Shipment?> {
        return shipmentsFlow.map { list -> list.find { it.id == id } }
    }

    override fun getAllShipments(): Flow<List<Shipment>> {
        return shipmentsFlow
    }
}
