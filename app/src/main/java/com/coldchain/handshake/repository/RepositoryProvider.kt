package com.coldchain.handshake.repository

import android.content.Context
import com.coldchain.handshake.alerts.AndroidAlertNotifier
import com.coldchain.handshake.data.local.ColdChainDatabase
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.RoomTransactionRunner
import com.coldchain.handshake.data.network.AndroidNetworkMonitor
import com.coldchain.handshake.data.local.SharedPreferencesProfileCache
import com.coldchain.handshake.data.remote.SupabaseAccountRepository
import com.coldchain.handshake.data.remote.SupabaseClientProvider
import com.coldchain.handshake.data.remote.SupabaseColdChainRemote
import com.coldchain.handshake.data.security.KeystoreDeviceSigner
import com.coldchain.handshake.util.DeviceIdProvider
import com.coldchain.handshake.debugtools.DebugTools
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.ChaosScenarioType
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
    internal var accountRepo: AccountRepository? = null

    @Volatile
    internal var remoteService: ColdChainRemote? = null

    @Volatile
    private var chaosService: ChaosEngineService = DebugTools.createChaosService()

    @Volatile
    private var safetyEngineInstance: SafetyEngine? = null

    @Volatile
    private var handoverOrchestratorInstance: HandoverOrchestrator? = null

    @Volatile
    private var simulator: TemperatureSimulator? = null
    internal var syncCoordinator: ShipmentHistorySyncCoordinator? = null

    @Volatile
    private var localSession: LocalSession? = null

    /** Whose records this phone holds; built by [initialize]. */
    val session: LocalSession
        get() = localSession ?: throw IllegalStateException("RepositoryProvider not initialized. Call initialize(context) first.")

    /** This install's name to the server. Tests set it; [initialize] reads it from the phone. */
    @Volatile
    internal var deviceIdValue: String? = null

    val deviceId: String
        get() = deviceIdValue ?: throw IllegalStateException("RepositoryProvider not initialized. Call initialize(context) first.")

    val remote: ColdChainRemote
        get() = remoteService ?: synchronized(this) {
            remoteService ?: SupabaseColdChainRemote(SupabaseClientProvider.getClient()).also { remoteService = it }
        }

    val shipmentHistorySyncCoordinator: ShipmentHistorySyncCoordinator
        get() = syncCoordinator ?: synchronized(this) {
            syncCoordinator ?: ShipmentHistorySyncCoordinator(telemetryRepository, remote).also { syncCoordinator = it }
        }

    val shipmentWorkflow: ShipmentWorkflow
        get() = ShipmentWorkflow(remote, shipmentRepository) { deviceId }

    val serverHandover: ServerHandover
        get() = ServerHandover(
            remote = remote,
            orchestrator = handoverOrchestrator,
            shipments = shipmentRepository,
            handovers = handoverRepository,
            history = shipmentHistorySyncCoordinator,
            uploadPending = { shipmentId -> syncService.uploadReadings(shipmentId) },
            deviceId = { deviceId }
        )

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

    /** Stays signed out until [initialize] connects it, which is what code without a server sees. */
    val accountRepository: AccountRepository
        get() = accountRepo ?: AccountRepository.NONE

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
                safetyEngine = safetyEngine,
                wallClock = System::currentTimeMillis
            ).also { simulator = it }
        }
        set(value) { simulator = value }

    /**
     * Builds everything once per process. Later calls do nothing: `MainActivity` calls this on every creation,
     * rotation included, and a worker may already have called it when WorkManager started the process. Building
     * again would leave the first set's background jobs running beside the second's. [reset] starts over. A call
     * that failed part-way didn't count, so the next one tries again.
     */
    fun initialize(context: Context) {
        synchronized(this) {
            if (initialized) return
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

            val sEngine = SafetyEngine(aRepo, sRepo, AndroidAlertNotifier(appContext))
            safetyEngineInstance = sEngine

            val hOrchestrator = handoverOrchestratorFor(db, sRepo, tRepo, hRepo)
            handoverOrchestratorInstance = hOrchestrator

            val baseNetworkMonitor = AndroidNetworkMonitor(appContext)
            val networkMonitor = DebugTools.wrapNetworkMonitor(baseNetworkMonitor, chaosService)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            syncScope = scope

            val deviceId = DeviceIdProvider.getDeviceId(appContext)
            deviceIdValue = deviceId
            val remote = remoteService ?: SupabaseColdChainRemote(SupabaseClientProvider.getClient())
            remoteService = remote
            val signer = KeystoreDeviceSigner("cch-device-$deviceId")
            if (accountRepo == null) {
                accountRepo = SupabaseAccountRepository(
                    client = SupabaseClientProvider.getClient(),
                    remote = remote,
                    signer = signer,
                    deviceId = { deviceId },
                    profileCache = SharedPreferencesProfileCache(appContext),
                    scope = scope
                )
            }
            val account = accountRepository

            syncCoordinator = ShipmentHistorySyncCoordinator(tRepo, remote)

            syncServiceInstance = SyncServiceImpl(
                shipmentDao = db.shipmentDao(),
                temperatureEventDao = db.temperatureEventDao(),
                alertDao = db.alertDao(),
                handoverDao = db.handoverDao(),
                remote = remote,
                uploader = ReadingUploader(remote, signer, { deviceId }),
                account = account,
                networkMonitor = networkMonitor,
                deviceId = { deviceId },
                coroutineScope = scope
            )

            val gate = LocalSession(
                db = db,
                account = account,
                ownerPrefs = appContext.getSharedPreferences(LocalSession.OWNER_PREFS, Context.MODE_PRIVATE),
                forgetInMemory = {
                    simulator?.reset()
                    syncCoordinator = null
                }
            )
            localSession = gate
            gate.watch(scope)

            if (simulator == null) {
                simulator = TemperatureSimulator(
                    telemetryRepository = tRepo,
                    chaosEngineService = chaosService,
                    safetyEngine = sEngine,
                    wallClock = System::currentTimeMillis
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
                        sEngine.handleLoggerDisconnect(
                            shipmentId = shipmentId,
                            loggerId = loggerId,
                            reason = "Chaos injected: Logger sensor disconnected"
                        )
                    }
                    wasDisconnectActive = isDisconnectActive
                }
            }
            initialized = true
        }
    }

    /** Set by the last line of [initialize] from a context, cleared by [reset]. */
    @Volatile
    private var initialized = false

    /**
     * The app's own orchestrator. A handover runs in one database transaction, so a breach arriving
     * part-way can't leave a PASS beside a quarantined shipment.
     */
    internal fun handoverOrchestratorFor(
        db: ColdChainDatabase,
        shipments: ShipmentRepository,
        telemetry: TelemetryRepository,
        handovers: HandoverRepository
    ) = HandoverOrchestrator(shipments, telemetry, handovers, RoomTransactionRunner(db))

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
            initialized = false
            database = null
            localSession = null
            accountRepo = null
            remoteService = null
            deviceIdValue = null
            shipmentRepo = null
            telemetryRepo = null
            alertRepo = null
            handoverRepo = null
            syncServiceInstance = null
            safetyEngineInstance = null
            handoverOrchestratorInstance = null
            simulator = null
            syncCoordinator = null
            chaosService = DebugTools.createChaosService()
        }
    }

    fun resetForTesting() {
        reset()
    }
}
