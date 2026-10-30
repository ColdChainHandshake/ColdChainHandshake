package com.coldchain.handshake

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.startup.InitializationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.ui.navigation.Screen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
    fun theAppStartsAndEveryTabOpens() {
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
