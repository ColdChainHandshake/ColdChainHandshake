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
import androidx.work.WorkManager
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
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.repository.UserProfile
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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ready = AccountState.Ready("user-1", "ana@example.com", UserProfile("Ana", MemberRole.COURIER))

    @Before
    fun setUp() {
        val packageName = ApplicationProvider.getApplicationContext<Context>().packageName
        Robolectric.setupContentProvider(InitializationProvider::class.java, "$packageName.androidx-startup")
    }

    @After
    fun tearDown() {
        RepositoryProvider.reset()
        DatabaseProvider.closeForTesting()
    }

    @Test
    fun theFirstLaunchAsksForNotificationsAndChecksForAlerts() {
        val context = ApplicationProvider.getApplicationContext<Context>()

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
        assertTrue(checks.isNotEmpty())
    }

    @Test
    fun theAppsOwnEngineNotifies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val t0 = System.currentTimeMillis()
        val shipment = Shipment(
            "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id, t0
        )
        RepositoryProvider.initialize(context)

        RepositoryProvider.safetyEngine.processTelemetryAndEnforceSafety(
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
        RepositoryProvider.accountRepo = account

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("Sign Out")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Send Code")).fetchSemanticsNodes().isNotEmpty() }
        }

        assertTrue(account.signedOut)
    }

    @Test
    fun aPhoneThatBelongsToSomeoneElseSaysSo() {
        RepositoryProvider.accountRepo = FakeAccountRepository(
            ready.copy(deviceProblem = "This phone is registered to another account.")
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("This phone is registered to another account.")).assertExists()
        }
    }

    @Test
    fun theAppStartsAndEveryTabOpens() {
        RepositoryProvider.accountRepo = FakeAccountRepository(ready)

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
