package com.coldchain.handshake.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceIdProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("coldchain_device_prefs", Context.MODE_PRIVATE)

    @Before
    @After
    fun forget() {
        DeviceIdProvider.setDeviceIdForTesting(null)
        prefs.edit().clear().commit()
    }

    @Test
    fun theIdIsAUuidBecauseTheServerStoresOne() {
        val id = DeviceIdProvider.getDeviceId(context)

        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test
    fun theIdSurvivesARestart() {
        val first = DeviceIdProvider.getDeviceId(context)

        DeviceIdProvider.setDeviceIdForTesting(null)

        assertEquals(first, DeviceIdProvider.getDeviceId(context))
    }

    @Test
    fun anOlderInstallGetsAnIdTheServerCanAccept() {
        prefs.edit().putString("device_id", "DEV-1A2B3C4D").commit()

        val id = DeviceIdProvider.getDeviceId(context)

        assertNotEquals("DEV-1A2B3C4D", id)
        assertEquals(id, UUID.fromString(id).toString())
    }
}
