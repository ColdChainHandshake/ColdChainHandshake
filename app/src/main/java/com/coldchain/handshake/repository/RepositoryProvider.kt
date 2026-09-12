package com.coldchain.handshake.repository

import com.coldchain.handshake.data.local.dao.ChaosEventDao
import com.coldchain.handshake.data.local.entities.ChaosEventEntity

import android.content.Context
import com.coldchain.handshake.data.local.ColdChainDatabase
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.network.AndroidNetworkMonitor
import com.coldchain.handshake.data.network.ChaosAwareNetworkMonitor
import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.models.ChaosScenarioType
import com.coldchain.handshake.models.SyncStatus
import java.util.UUID
import com.coldchain.handshake.repository.impl.InMemoryChaosEngineService
import com.coldchain.handshake.safety.LoggerDisconnectMonitor
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Unified Single Source of Truth Provider for ColdChainHandshake.
 * Bridges Person 1 (Room + Supabase), Person 2 (Dispatch/Transit/Simulator),
 * Person 3 (Safety Decision Layer), and Person 4 (Integrity & Handover Layer).
 */
object RepositoryProvider {

    @Volatile
    private var database: ColdChainDatabase? = null

    @Volatile
    private var shipmentRepo: ShipmentRepository? = null

    @Volatile
    private var telemetryRepo: TelemetryRepository? = null

    @Volatile
    private var alertRepo: AlertRepository? = null

    @Volatile
    private var handoverRepo: HandoverRepository? = null

    @Volatile
    private var syncServiceInstance: SyncService? = null

    @Volatile
    private var chaosService: ChaosEngineService = InMemoryChaosEngineService()

    @Volatile
    private var safetyEngineInstance: SafetyEngine? = null

    @Volatile
    private var handoverOrchestratorInstance: HandoverOrchestrator? = null

    @Volatile
    private var simulator: TemperatureSimulator? = null

    @Volatile
    internal var syncScope: CoroutineScope? = null

    val shipmentRepository: ShipmentRepository
        get() = shipmentRepo ?: throw IllegalStateException("RepositoryProvider not initialized. Call initialize(context) first.")

    val telemetryRepository: TelemetryRepository
        get() = telemetryRepo ?: throw IllegalStateException("RepositoryProvider not initialized. Call initialize(context) first.")

    val alertRepository: AlertRepository
        get() = alertRepo ?: throw IllegalStateException("RepositoryProvider not initialized. Call initialize(context) first.")

    val handoverRepository: HandoverRepository
        get() = handoverRepo ?: throw IllegalStateException("RepositoryProvider not initialized. Call initialize(context) first.")

    val syncService: SyncService
        get() = syncServiceInstance ?: throw IllegalStateException("RepositoryProvider not initialized. Call initialize(context) first.")

    var chaosEngineService: ChaosEngineService
        get() = chaosService
        set(value) { chaosService = value }

    val safetyEngine: SafetyEngine
        get() = safetyEngineInstance ?: synchronized(this) {
            safetyEngineInstance ?: SafetyEngine(alertRepository, shipmentRepository).also { safetyEngineInstance = it }
        }

    val handoverOrchestrator: HandoverOrchestrator
        get() = handoverOrchestratorInstance ?: synchronized(this) {
            handoverOrchestratorInstance ?: HandoverOrchestrator(
                shipmentRepository,
                telemetryRepository,
                handoverRepository
            ).also { handoverOrchestratorInstance = it }
        }

    var temperatureSimulator: TemperatureSimulator
        get() = simulator ?: synchronized(this) {
            simulator ?: TemperatureSimulator(
                telemetryRepository = telemetryRepository,
                chaosEngineService = chaosEngineService,
                safetyEngine = safetyEngine
            ).also { simulator = it }
        }
        set(value) { simulator = value }

    fun initialize(context: Context) {
        synchronized(this) {
            val appContext = context.applicationContext
            val db = DatabaseProvider.getDatabase(appContext)
            database = db

            val sRepo = ShipmentRepositoryImpl(db.shipmentDao())
            val tRepo = TelemetryRepositoryImpl(db.temperatureEventDao())
            val aRepo = AlertRepositoryImpl(db.alertDao())
            val hRepo = HandoverRepositoryImpl(db.handoverDao())

            shipmentRepo = sRepo
            telemetryRepo = tRepo
            alertRepo = aRepo
            handoverRepo = hRepo

            val sEngine = SafetyEngine(aRepo, sRepo)
            safetyEngineInstance = sEngine

            val hOrchestrator = HandoverOrchestrator(sRepo, tRepo, hRepo)
            handoverOrchestratorInstance = hOrchestrator

            val baseNetworkMonitor = AndroidNetworkMonitor(appContext)
            val chaosNetworkMonitor = ChaosAwareNetworkMonitor(baseNetworkMonitor, chaosService)
            val remoteDataSource = SupabaseRemoteDataSource()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            syncScope = scope

            syncServiceInstance = SyncServiceImpl(
                shipmentDao = db.shipmentDao(),
                temperatureEventDao = db.temperatureEventDao(),
                alertDao = db.alertDao(),
                handoverDao = db.handoverDao(),
                remoteDataSource = remoteDataSource,
                networkMonitor = chaosNetworkMonitor,
                coroutineScope = scope
            )

            if (simulator == null) {
                simulator = TemperatureSimulator(
                    telemetryRepository = tRepo,
                    chaosEngineService = chaosService,
                    safetyEngine = sEngine
                )
            }

            // Wire Logger Disconnect Chaos scenario to Person 3 LoggerDisconnectMonitor
            scope.launch {
                var wasDisconnectActive = false
                chaosService.observeActiveScenarios().collect { scenarios ->
                    val isDisconnectActive = scenarios.any {
                        it.scenarioType == ChaosScenarioType.LOGGER_DISCONNECT && it.isActive
                    }
                    if (isDisconnectActive && !wasDisconnectActive) {
                        val activeShipment = simulator?.activeShipment?.value
                        val shipmentId = activeShipment?.id ?: "SHIP-ACTIVE"
                        val loggerId = activeShipment?.loggerId ?: "LOG-SIMULATED"
                        LoggerDisconnectMonitor.onLoggerDisconnectedAndPersist(
                            shipmentId = shipmentId,
                            loggerId = loggerId,
                            repository = aRepo,
                            reason = "Chaos injected: Logger sensor disconnected"
                        )
                    }
                    wasDisconnectActive = isDisconnectActive
                }
            }
        }
    }

    /**
     * DEMO/CHAOS ONLY: Mutates a protected telemetry event in Room without recomputing its SHA-256 hash.
     * Used to test cryptographic tamper detection during Handover verification.
     */
    suspend fun corruptLatestEventForDemo(shipmentId: String, corruptedTemp: Double = 99.9): Boolean {
        val db = database ?: return false
        val events = db.temperatureEventDao().getTemperaturesDirect(shipmentId)
        val latest = events.lastOrNull() ?: return false
        db.temperatureEventDao().corruptEventTemperatureForDemo(latest.id, corruptedTemp)
        return true
    }

    fun getChaosEventDao(context: Context? = null): ChaosEventDao? {
        if (database == null && context != null) {
            initialize(context)
        }
        return database?.chaosEventDao()
    }

    suspend fun logChaosEvent(
        shipmentId: String,
        scenarioType: String,
        message: String,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) {
        val db = database ?: return
        db.chaosEventDao().insertChaosEvent(
            ChaosEventEntity(
                id = UUID.randomUUID().toString(),
                shipmentId = shipmentId,
                scenarioType = scenarioType,
                timestamp = System.currentTimeMillis(),
                message = message,
                syncStatus = syncStatus
            )
        )
    }

    fun getShipmentRepository(context: Context): ShipmentRepository {
        return shipmentRepo ?: synchronized(this) {
            shipmentRepo ?: run {
                initialize(context)
                shipmentRepo!!
            }
        }
    }

    fun getTelemetryRepository(context: Context): TelemetryRepository {
        return telemetryRepo ?: synchronized(this) {
            telemetryRepo ?: run {
                initialize(context)
                telemetryRepo!!
            }
        }
    }

    fun getAlertRepository(context: Context): AlertRepository {
        return alertRepo ?: synchronized(this) {
            alertRepo ?: run {
                initialize(context)
                alertRepo!!
            }
        }
    }

    fun getHandoverRepository(context: Context): HandoverRepository {
        return handoverRepo ?: synchronized(this) {
            handoverRepo ?: run {
                initialize(context)
                handoverRepo!!
            }
        }
    }

    fun getSyncService(context: Context): SyncService {
        return syncServiceInstance ?: synchronized(this) {
            syncServiceInstance ?: run {
                initialize(context)
                syncServiceInstance!!
            }
        }
    }

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
            syncServiceImpl?.let { syncServiceInstance = it }

            if (alertRepo != null && shipmentRepo != null) {
                safetyEngineInstance = SafetyEngine(alertRepo!!, shipmentRepo!!)
            }
            if (shipmentRepo != null && telemetryRepo != null && handoverRepo != null) {
                handoverOrchestratorInstance = HandoverOrchestrator(shipmentRepo!!, telemetryRepo!!, handoverRepo!!)
            }
            if (telemetryRepo != null) {
                simulator = TemperatureSimulator(
                    telemetryRepository = telemetryRepo!!,
                    chaosEngineService = chaosService,
                    safetyEngine = safetyEngineInstance
                )
            }
        }
    }

    fun reset() {
        synchronized(this) {
            syncScope?.cancel()
            syncScope = null
            database = null
            shipmentRepo = null
            telemetryRepo = null
            alertRepo = null
            handoverRepo = null
            syncServiceInstance = null
            safetyEngineInstance = null
            handoverOrchestratorInstance = null
            simulator = null
            chaosService = InMemoryChaosEngineService()
        }
    }

    fun resetForTesting() {
        reset()
    }
}
