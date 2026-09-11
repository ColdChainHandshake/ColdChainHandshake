package com.coldchain.handshake.repository

import android.content.Context
import com.coldchain.handshake.data.local.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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

    @Volatile
    private var syncService: SyncService? = null

    @Volatile
    internal var syncScope: CoroutineScope? = null

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

    fun getSyncService(context: Context): SyncService {
        return syncService ?: synchronized(this) {
            syncService ?: run {
                val db = DatabaseProvider.getDatabase(context)
                val networkMonitor = com.coldchain.handshake.data.network.AndroidNetworkMonitor(context)
                val remoteDataSource = com.coldchain.handshake.data.remote.SupabaseRemoteDataSource()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                syncScope = scope
                SyncServiceImpl(
                    shipmentDao = db.shipmentDao(),
                    temperatureEventDao = db.temperatureEventDao(),
                    alertDao = db.alertDao(),
                    handoverDao = db.handoverDao(),
                    remoteDataSource = remoteDataSource,
                    networkMonitor = networkMonitor,
                    coroutineScope = scope
                ).also { syncService = it }
            }
        }
    }

    /**
     * Testing / initialization hook to supply mock or fake instances.
     */
    fun initialize(
        shipmentRepository: ShipmentRepository? = null,
        telemetryRepository: TelemetryRepository? = null,
        alertRepository: AlertRepository? = null,
        handoverRepository: HandoverRepository? = null,
        syncServiceImpl: SyncService? = null
    ) {
        synchronized(this) {
            syncScope?.cancel()
            syncScope = null
            shipmentRepository?.let { shipmentRepo = it }
            telemetryRepository?.let { telemetryRepo = it }
            alertRepository?.let { alertRepo = it }
            handoverRepository?.let { handoverRepo = it }
            syncServiceImpl?.let { syncService = it }
        }
    }

    /**
     * Reset repository references and cancel background network observers (useful between tests).
     */
    fun reset() {
        synchronized(this) {
            syncScope?.cancel()
            syncScope = null
            shipmentRepo = null
            telemetryRepo = null
            alertRepo = null
            handoverRepo = null
            syncService = null
        }
    }
}


