package com.coldchain.handshake.di

import android.content.Context
import com.coldchain.handshake.alerts.AlertChecks
import com.coldchain.handshake.alerts.AlertEscalationScheduler
import com.coldchain.handshake.alerts.AndroidAlertNotifier
import com.coldchain.handshake.data.local.ColdChainDatabase
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.RoomTransactionRunner
import com.coldchain.handshake.data.local.SharedPreferencesPendingReleases
import com.coldchain.handshake.data.local.SharedPreferencesProfileCache
import com.coldchain.handshake.data.network.AndroidNetworkMonitor
import com.coldchain.handshake.data.remote.SupabaseAccountRepository
import com.coldchain.handshake.data.remote.SupabaseClientProvider
import com.coldchain.handshake.data.remote.SupabaseColdChainRemote
import com.coldchain.handshake.data.security.KeystoreDeviceSigner
import com.coldchain.handshake.debugtools.DebugTools
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.recording.RecordingLoop
import com.coldchain.handshake.recording.RecordingService
import com.coldchain.handshake.recording.FusedLocationSource
import com.coldchain.handshake.recording.LocationRecorder
import com.coldchain.handshake.recording.LocationSource
import com.coldchain.handshake.recording.RecordedLocations
import com.coldchain.handshake.data.local.entities.toRecordedLocation
import kotlinx.coroutines.flow.map
import com.coldchain.handshake.recording.RecordingStore
import com.coldchain.handshake.recording.SharedPreferencesRecordingStore
import com.coldchain.handshake.recording.SyncRequests
import com.coldchain.handshake.recording.SyncScheduler
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.repository.AlertRepositoryImpl
import com.coldchain.handshake.repository.ChaosEngineService
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.LocalSession
import com.coldchain.handshake.repository.ReadingUploader
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.ShipmentWorkflow
import com.coldchain.handshake.repository.SyncService
import com.coldchain.handshake.repository.SyncServiceImpl
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.safety.SafetyEngine
import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.simulator.LocalLoggers
import com.coldchain.handshake.simulator.TemperatureSimulator
import com.coldchain.handshake.ui.navigation.SessionGate
import com.coldchain.handshake.util.DeviceIdProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** The process-wide scope for background jobs: sync, the owner check, the chaos watcher. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * Everything the app is built from, one of each per process. Hilt builds it on first use, from an activity or from
 * WorkManager starting the process for a worker, and both get the same objects.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): ColdChainDatabase = DatabaseProvider.getDatabase(context)

    @Provides @Singleton
    fun shipmentRepository(db: ColdChainDatabase): ShipmentRepository = ShipmentRepositoryImpl(db.shipmentDao())

    @Provides @Singleton
    fun telemetryRepository(db: ColdChainDatabase): TelemetryRepository = TelemetryRepositoryImpl(db.temperatureEventDao())

    @Provides @Singleton
    fun alertRepository(db: ColdChainDatabase): AlertRepository = AlertRepositoryImpl(db.alertDao())

    @Provides @Singleton
    fun handoverRepository(db: ColdChainDatabase): HandoverRepository = HandoverRepositoryImpl(db.handoverDao())

    @Provides @Singleton @DeviceId
    fun deviceId(@ApplicationContext context: Context): String = DeviceIdProvider.getDeviceId(context)

    @Provides @Singleton
    fun deviceSigner(@DeviceId deviceId: String): DeviceSigner = KeystoreDeviceSigner("cch-device-$deviceId")

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun chaosService(): ChaosEngineService = DebugTools.createChaosService()

    @Provides @Singleton
    fun safetyEngine(
        @ApplicationContext context: Context,
        alerts: AlertRepository,
        shipments: ShipmentRepository
    ): SafetyEngine = SafetyEngine(alerts, shipments, AndroidAlertNotifier(context))

    /** A handover runs in one database transaction, so a breach arriving part-way can't leave a PASS beside a quarantine. */
    @Provides @Singleton
    fun handoverOrchestrator(
        db: ColdChainDatabase,
        shipments: ShipmentRepository,
        telemetry: TelemetryRepository,
        handovers: HandoverRepository
    ): HandoverOrchestrator = handoverOrchestratorFor(db, shipments, telemetry, handovers)

    fun handoverOrchestratorFor(
        db: ColdChainDatabase,
        shipments: ShipmentRepository,
        telemetry: TelemetryRepository,
        handovers: HandoverRepository
    ) = HandoverOrchestrator(shipments, telemetry, handovers, RoomTransactionRunner(db))

    @Provides @Singleton
    fun temperatureSimulator(
        telemetry: TelemetryRepository,
        chaos: ChaosEngineService,
        safetyEngine: SafetyEngine,
        localLoggers: LocalLoggers
    ): TemperatureSimulator = TemperatureSimulator(
        telemetryRepository = telemetry,
        chaosEngineService = chaos,
        safetyEngine = safetyEngine,
        wallClock = System::currentTimeMillis,
        localLoggers = localLoggers
    )

    @Provides @Singleton
    fun historySync(telemetry: TelemetryRepository, remote: ColdChainRemote): ShipmentHistorySyncCoordinator =
        ShipmentHistorySyncCoordinator(telemetry, remote)

    @Provides @Singleton
    fun syncService(
        @ApplicationContext context: Context,
        db: ColdChainDatabase,
        remote: ColdChainRemote,
        signer: DeviceSigner,
        account: AccountRepository,
        chaos: ChaosEngineService,
        @DeviceId deviceId: String,
        @AppScope scope: CoroutineScope
    ): SyncServiceImpl = SyncServiceImpl(
        shipmentDao = db.shipmentDao(),
        temperatureEventDao = db.temperatureEventDao(),
        alertDao = db.alertDao(),
        handoverDao = db.handoverDao(),
        gpsPointDao = db.gpsPointDao(),
        remote = remote,
        uploader = ReadingUploader(remote, signer, { deviceId }),
        account = account,
        networkMonitor = DebugTools.wrapNetworkMonitor(AndroidNetworkMonitor(context), chaos),
        deviceId = { deviceId },
        coroutineScope = scope
    )

    @Provides
    fun syncServiceInterface(impl: SyncServiceImpl): SyncService = impl

    @Provides @Singleton
    fun pendingReleases(@ApplicationContext context: Context): SharedPreferencesPendingReleases =
        SharedPreferencesPendingReleases(context)

    @Provides @Singleton
    fun localSession(
        @ApplicationContext context: Context,
        db: ColdChainDatabase,
        account: AccountRepository,
        simulator: TemperatureSimulator,
        history: ShipmentHistorySyncCoordinator,
        pendingReleases: SharedPreferencesPendingReleases,
        recording: RecordingStore
    ): LocalSession = LocalSession(
        db = db,
        account = account,
        ownerPrefs = context.getSharedPreferences(LocalSession.OWNER_PREFS, Context.MODE_PRIVATE),
        forgetInMemory = {
            simulator.reset()
            history.clear()
            pendingReleases.clear()
            recording.shipmentId = null
        }
    )

    @Provides
    fun sessionGate(session: LocalSession): SessionGate = session

    @Provides
    fun shipmentWorkflow(remote: ColdChainRemote, shipments: ShipmentRepository, @DeviceId deviceId: String) =
        ShipmentWorkflow(remote, shipments) { deviceId }

    @Provides
    fun serverHandover(
        remote: ColdChainRemote,
        orchestrator: HandoverOrchestrator,
        shipments: ShipmentRepository,
        handovers: HandoverRepository,
        history: ShipmentHistorySyncCoordinator,
        sync: SyncServiceImpl,
        pendingReleases: SharedPreferencesPendingReleases,
        syncRequests: SyncRequests,
        @DeviceId deviceId: String
    ) = ServerHandover(
        remote = remote,
        orchestrator = orchestrator,
        shipments = shipments,
        handovers = handovers,
        history = history,
        uploadPending = { shipmentId -> sync.uploadReadings(shipmentId) },
        deviceId = { deviceId },
        pendingReleases = pendingReleases,
        onQueuedOffline = { syncRequests.request() }
    )

    @Provides
    fun syncRequests(@ApplicationContext context: Context): SyncRequests = SyncRequests { SyncScheduler.request(context) }

    @Provides @Singleton
    fun recordingStore(@ApplicationContext context: Context): RecordingStore = SharedPreferencesRecordingStore(context)

    @Provides @Singleton
    fun courierRecorder(
        @ApplicationContext context: Context,
        simulator: TemperatureSimulator,
        store: RecordingStore
    ): CourierRecorder = CourierRecorder(simulator, store) { RecordingService.keepRunning(context) }

    @Provides @Singleton
    fun recordingLoop(
        simulator: TemperatureSimulator,
        recorder: CourierRecorder,
        sync: SyncService,
        handover: ServerHandover,
        remote: ColdChainRemote,
        account: AccountRepository,
        shipments: ShipmentRepository,
        store: RecordingStore,
        syncRequests: SyncRequests
    ): RecordingLoop = RecordingLoop(simulator, recorder, sync, handover, remote, account, shipments, store, syncRequests)

    @Provides
    fun alertChecks(@ApplicationContext context: Context): AlertChecks =
        AlertChecks { AlertEscalationScheduler.checkNow(context) }

    @Provides @Singleton
    fun locationRecorder(simulator: TemperatureSimulator, db: ColdChainDatabase): LocationRecorder =
        LocationRecorder(simulator, db.gpsPointDao())

    @Provides
    fun recordedLocations(db: ColdChainDatabase, recorder: LocationRecorder): RecordedLocations = object : RecordedLocations {
        override val active = recorder.active
        override fun latest(shipmentId: String) = db.gpsPointDao().latest(shipmentId).map { it?.toRecordedLocation() }
    }
}

/** Where the recording service's fixes come from, apart from the rest so tests can replace it. */
@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides @Singleton
    fun locationSource(@ApplicationContext context: Context): LocationSource = FusedLocationSource(context)
}

/** The server and the account, apart from the rest so tests can replace them. */
@Module
@InstallIn(SingletonComponent::class)
object ServerModule {

    @Provides @Singleton
    fun remote(): ColdChainRemote = SupabaseColdChainRemote(SupabaseClientProvider.getClient())

    @Provides @Singleton
    fun accountRepository(
        @ApplicationContext context: Context,
        remote: ColdChainRemote,
        signer: DeviceSigner,
        @DeviceId deviceId: String,
        @AppScope scope: CoroutineScope
    ): AccountRepository = SupabaseAccountRepository(
        client = SupabaseClientProvider.getClient(),
        remote = remote,
        signer = signer,
        deviceId = { deviceId },
        profileCache = SharedPreferencesProfileCache(context),
        scope = scope
    )
}
