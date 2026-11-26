package com.coldchain.handshake.data.remote

import android.content.Context
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.realtime
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auth stores its session through a library set up by the androidx.startup provider. A device runs
 * that provider at launch and Robolectric doesn't, so this test starts it the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseClientProviderTest {

    @Before
    fun setUp() {
        val packageName = ApplicationProvider.getApplicationContext<Context>().packageName
        Robolectric.setupContentProvider(InitializationProvider::class.java, "$packageName.androidx-startup")
    }

    @After
    fun tearDown() {
        SupabaseClientProvider.reset()
    }

    @Test
    fun theProductionClientHasAuthDatabaseAndRealtime() {
        val client = SupabaseClientProvider.getClient()

        assertNotNull(client.auth)
        assertNotNull(client.postgrest)
        assertNotNull(client.realtime)
    }

    // supabase-kt's default drops the session from memory whenever the app leaves the foreground (its TROUBLESHOOTING,
    // "Retrieving data in the background does not work"). On the S24 on 26 Nov that stopped every upload while the
    // screen was off, with the recording service still running. The account loads the session itself instead.
    @Test
    fun theSessionStaysWhenTheAppGoesToTheBackground() {
        val client = SupabaseClientProvider.getClient()

        assertFalse(client.auth.config.enableLifecycleCallbacks)
    }
}
