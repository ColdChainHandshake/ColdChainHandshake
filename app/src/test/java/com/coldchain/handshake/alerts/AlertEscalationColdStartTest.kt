package com.coldchain.handshake.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.repository.AlertRepositoryImpl
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.safety.AlertEscalationEngine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.startup.InitializationProvider
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WorkManager can start the process for the escalation worker with no activity ever created, so nothing has
 * called `RepositoryProvider.initialize` yet. The worker must still reach the stored alerts, and an activity
 * opening afterwards must not build a second set of everything beside the first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertEscalationColdStartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // A real process runs its content providers first, whichever component started it; App Startup's is
        // where supabase-kt's session storage gets its context.
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        RepositoryProvider.reset()
    }

    @After
    fun tearDown() {
        RepositoryProvider.reset()
        DatabaseProvider.closeForTesting()
    }

    @Test
    fun theWorkerEscalatesAStoredAlertWhenNoActivityHasStarted() = runBlocking {
        val stored = AlertRepositoryImpl(DatabaseProvider.getDatabase(context).alertDao())
        stored.saveAlert(
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

        val result = TestListenableWorkerBuilder<AlertEscalationWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(EscalationLevel.SUPERVISOR, stored.getOpenAlerts().single().escalationLevel)
    }

    @Test
    fun anActivityOpeningAfterTheWorkerReusesWhatTheWorkerBuilt() {
        val workerRepository = RepositoryProvider.getAlertRepository(context)
        val workerScope = RepositoryProvider.syncScope
        assertNotNull(workerScope)

        RepositoryProvider.initialize(context) // what MainActivity.onCreate does, on every creation

        assertSame(workerRepository, RepositoryProvider.alertRepository)
        assertSame(workerScope, RepositoryProvider.syncScope)
        assertTrue(workerScope!!.isActive)
    }

    @Test
    fun rotatingTheActivityDoesNotStartASecondSetOfBackgroundJobs() {
        RepositoryProvider.initialize(context)
        val first = RepositoryProvider.syncScope!!

        RepositoryProvider.initialize(context)

        assertSame(first, RepositoryProvider.syncScope)
        assertTrue(first.isActive)
    }

    @Test
    fun afterAResetTheNextInitializeBuildsAFreshSet() {
        RepositoryProvider.initialize(context)
        val first = RepositoryProvider.syncScope!!

        RepositoryProvider.reset()
        RepositoryProvider.initialize(context)

        assertFalse(first.isActive)
        assertTrue(RepositoryProvider.syncScope!!.isActive)
    }
}
