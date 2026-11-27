package com.coldchain.handshake.alerts

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.di.AppStartup
import com.coldchain.handshake.di.ServerModule
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.recording.RecordingStore
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.safety.AlertEscalationEngine
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * WorkManager can start the process for the escalation worker with no activity ever created. The worker must still
 * reach the stored alerts, through the same objects the screens use, and an activity created again (a rotation)
 * must not start a second set of background jobs beside the first.
 */
@HiltAndroidTest
@UninstallModules(ServerModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class AlertEscalationColdStartTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @BindValue @JvmField
    val account: AccountRepository = FakeAccountRepository()

    @BindValue @JvmField
    val remote: ColdChainRemote = FakeColdChainServer(currentUser = "courier")

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var alerts: AlertRepository
    @Inject lateinit var startup: AppStartup
    @Inject lateinit var shipments: ShipmentRepository
    @Inject lateinit var telemetry: TelemetryRepository
    @Inject lateinit var recording: RecordingStore

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(workerFactory).build()
        )
    }

    @After
    fun tearDown() {
        DatabaseProvider.closeForTesting()
    }

    @Test
    fun theWorkerEscalatesAStoredAlertWhenNoActivityHasStarted() = runBlocking {
        alerts.saveAlert(
            Alert(
                id = "SHIP-1:TEMPERATURE_BREACH:1000",
                shipmentId = "SHIP-1",
                type = AlertType.TEMPERATURE_BREACH,
                message = "Reached 11.0°C",
                timestamp = System.currentTimeMillis() - AlertEscalationEngine.ESCALATION_INTERVAL_MS - 1_000L,
                escalationLevel = EscalationLevel.WORKER,
                acknowledged = false
            )
        )

        val result = TestListenableWorkerBuilder<AlertEscalationWorker>(context).setWorkerFactory(workerFactory).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(EscalationLevel.SUPERVISOR, alerts.getOpenAlerts().single().escalationLevel)
    }

    /** A server shipment on the move whose newest reading on this phone is twelve hours old. */
    private fun aShipmentLastHeardTwelveHoursAgo(id: String) = runBlocking {
        val genesis = "a".repeat(64)
        val lastHeard = System.currentTimeMillis() - 12 * 60 * 60_000L
        shipments.adoptRemoteShipment(
            Shipment(id, id, "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, journeyStartedAt = lastHeard - 5 * 60_000L, genesisHash = genesis)
        ).getOrThrow()
        val readings = HashChainService.buildChain(
            (4 downTo 0).map { back ->
                TemperatureEvent("TE-$id-$back", id, "LOG-1", lastHeard - back * 60_000L, 5.0, "", "", SyncStatus.SYNCED)
            },
            genesis
        )
        telemetry.saveTemperatures(readings).getOrThrow()
    }

    // Found on the dev project (27 Nov): the receiver's phone recorded three DATA_GAP alerts on SHIP-49A74F, one for
    // "718:54", measured from the newest reading it had downloaded before a night switched off. The readings were all
    // on the server. Only the phone that records a shipment can say its logger went quiet.
    @Test
    fun theWorkerRaisesNoGapForAShipmentThisPhoneOnlyWatches() = runBlocking {
        aShipmentLastHeardTwelveHoursAgo("SHIP-W")
        recording.shipmentId = null

        TestListenableWorkerBuilder<AlertEscalationWorker>(context).setWorkerFactory(workerFactory).build().doWork()

        assertTrue(alerts.getOpenAlerts().toString(), alerts.getOpenAlerts().none { it.type == AlertType.DATA_GAP })
    }

    @Test
    fun theWorkerRaisesAGapForTheShipmentThisPhoneRecords() = runBlocking {
        aShipmentLastHeardTwelveHoursAgo("SHIP-R")
        recording.shipmentId = "SHIP-R"

        TestListenableWorkerBuilder<AlertEscalationWorker>(context).setWorkerFactory(workerFactory).build().doWork()

        assertEquals(listOf(AlertType.DATA_GAP), alerts.getOpenAlerts().map { it.type })
        recording.shipmentId = null
    }

    @Test
    fun rotatingTheActivityDoesNotStartASecondSetOfBackgroundJobs() {
        startup.start()
        val first = startup.jobs

        startup.start() // what MainActivity.onCreate does, on every creation

        assertEquals(2, first.size)
        assertSame(first, startup.jobs)
        // The owner check runs as long as the process. The chaos watcher may already have ended: a release build has
        // no chaos lab, so its scenario list is empty and complete.
        assertTrue(first.first().isActive)
    }
}
