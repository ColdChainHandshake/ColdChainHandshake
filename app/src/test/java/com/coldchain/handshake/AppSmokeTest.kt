package com.coldchain.handshake

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.startup.InitializationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.alerts.AlertEscalationScheduler
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.FakeAccountRepository
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.UserProfile
import com.coldchain.handshake.di.ServerModule
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.safety.SafetyEngine
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import com.coldchain.handshake.ui.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Launches the real app and opens every tab, including with notifications not yet granted, which is
 * how a first launch looks. This is the startup check available without an emulator: it catches a
 * crash on launch or a screen that throws on first render, not how anything looks.
 */
@HiltAndroidTest
@UninstallModules(ServerModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class AppSmokeTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    /** Signed out unless a test says otherwise, before it launches the app. */
    @BindValue @JvmField
    var account: AccountRepository = AccountRepository.NONE

    @BindValue @JvmField
    val remote: ColdChainRemote = FakeColdChainServer(currentUser = "user-1")

    @Inject lateinit var safetyEngine: SafetyEngine

    private val ready = AccountState.Ready("user-1", "ana@example.com", UserProfile("Ana", MemberRole.COURIER))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        // Launching books an escalation check. Here it is booked but not run: run on WorkManager's own threads, it
        // could finish after this test's reset and build the app's objects on this test's context, for the next
        // test to inherit. `AlertNotificationsTest` and `AlertEscalationColdStartTest` run the worker itself.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(BookedOnlyWorkerFactory)
                .build()
        )
    }

    private object BookedOnlyWorkerFactory : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
            object : Worker(appContext, workerParameters) {
                override fun doWork() = Result.success()
            }
    }

    @After
    fun tearDown() {
        DatabaseProvider.closeForTesting()
    }

    @Test
    fun theFirstLaunchAsksForNotificationsAndChecksForAlerts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Only a check booked by this launch counts.
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManager.getInstance(context).pruneWork().result.get()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val asked = shadowOf(activity).lastRequestedPermission?.requestedPermissions.orEmpty()
                assertTrue(Manifest.permission.POST_NOTIFICATIONS in asked)
            }
        }

        val checks = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AlertEscalationScheduler.WORK_NAME)
            .get()
        assertTrue("checks: ${checks.map { it.state }}", checks.any { it.state != WorkInfo.State.CANCELLED })
    }

    @Test
    fun theAppsOwnEngineNotifies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val t0 = System.currentTimeMillis()
        val shipment = Shipment(
            "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id, t0
        )
        hilt.inject()

        safetyEngine.processTelemetryAndEnforceSafety(
            shipment,
            listOf(TemperatureEvent("TE-1", "SHIP-1", "LOG-1", t0, -3.0, "", "", SyncStatus.PENDING, 1L))
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun someoneSignedOutCanOnlySeeSignIn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Send Code")).fetchSemanticsNodes().isNotEmpty() }

            assertTrue(Screen.bottomNavItems.none { screen ->
                compose.onAllNodes(hasText(screen.title) and isSelectable()).fetchSemanticsNodes().isNotEmpty()
            })
        }
    }

    @Test
    fun signingOutReturnsToSignIn() {
        val account = FakeAccountRepository(ready)
        this.account = account

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("Sign Out")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Send Code")).fetchSemanticsNodes().isNotEmpty() }
        }

        assertTrue(account.signedOut)
    }

    @Test
    fun aPhoneThatBelongsToSomeoneElseSaysSo() {
        account = FakeAccountRepository(
            ready.copy(deviceProblem = "This phone is registered to another account.")
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("This phone is registered to another account.")).assertExists()
        }
    }

    @Test
    fun theAppStartsAndEveryTabOpens() {
        account = FakeAccountRepository(ready)

        ActivityScenario.launch(MainActivity::class.java).use {
            Screen.bottomNavItems.forEach { screen ->
                val tab = hasText(screen.title) and isSelectable()
                compose.onNode(tab).performClick()
                compose.waitForIdle()
                compose.onNode(tab).assertIsSelected()
            }
        }
    }
}
