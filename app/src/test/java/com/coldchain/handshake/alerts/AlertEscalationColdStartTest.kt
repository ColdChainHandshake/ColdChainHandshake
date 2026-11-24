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
