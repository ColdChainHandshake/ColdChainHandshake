package com.coldchain.handshake.repository

import android.content.Context
import com.coldchain.handshake.data.local.DatabaseProvider

/**
 * Lightweight thread-safe provider for shared repository singletons.
 * Allows Persons 2, 3, and 4 to consume repository interfaces without coupling to Room.
 */
object RepositoryProvider {

    @Volatile
    private var shipmentRepo: ShipmentRepository? = null

    @Volatile
    private var telemetryRepo: TelemetryRepository? = null

    @Volatile
    private var alertRepo: AlertRepository? = null

    @Volatile
    private var handoverRepo: HandoverRepository? = null

    fun getShipmentRepository(context: Context): ShipmentRepository {
        return shipmentRepo ?: synchronized(this) {
            shipmentRepo ?: ShipmentRepositoryImpl(
                DatabaseProvider.getDatabase(context).shipmentDao()
            ).also { shipmentRepo = it }
        }
    }

    fun getTelemetryRepository(context: Context): TelemetryRepository {
        return telemetryRepo ?: synchronized(this) {
            telemetryRepo ?: TelemetryRepositoryImpl(
                DatabaseProvider.getDatabase(context).temperatureEventDao()
            ).also { telemetryRepo = it }
        }
    }

    fun getAlertRepository(context: Context): AlertRepository {
        return alertRepo ?: synchronized(this) {
            alertRepo ?: AlertRepositoryImpl(
                DatabaseProvider.getDatabase(context).alertDao()
            ).also { alertRepo = it }
        }
    }

    fun getHandoverRepository(context: Context): HandoverRepository {
        return handoverRepo ?: synchronized(this) {
            handoverRepo ?: HandoverRepositoryImpl(
                DatabaseProvider.getDatabase(context).handoverDao()
            ).also { handoverRepo = it }
        }
    }

    /**
     * Testing / initialization hook to supply mock or fake instances.
     */
    fun initialize(
        shipmentRepository: ShipmentRepository? = null,
        telemetryRepository: TelemetryRepository? = null,
        alertRepository: AlertRepository? = null,
        handoverRepository: HandoverRepository? = null
    ) {
        synchronized(this) {
            shipmentRepository?.let { shipmentRepo = it }
            telemetryRepository?.let { telemetryRepo = it }
            alertRepository?.let { alertRepo = it }
            handoverRepository?.let { handoverRepo = it }
        }
    }

    /**
     * Reset repository references (useful between tests).
     */
    fun reset() {
        synchronized(this) {
            shipmentRepo = null
            telemetryRepo = null
            alertRepo = null
            handoverRepo = null
        }
    }
}
