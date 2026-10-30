package com.coldchain.handshake.debugtools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.crypto.IntegrityFailureReason
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.local.entities.toEntity
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.ui.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugToolsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = DatabaseProvider.createInMemoryDatabase(context)

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun event(index: Int, temperature: Double) = TemperatureEvent(
        id = "TE-$index",
        shipmentId = "SHIP-DEBUG",
        loggerId = "LOG-1",
        timestamp = 1_700_000_000_000L + index * 60_000L,
        temperature = temperature,
        previousHash = "",
        currentHash = "",
        syncStatus = SyncStatus.PENDING
    )

    @Test
    fun theChaosTabIsOffered() {
        assertTrue(DebugTools.ENABLED)
        assertTrue(Screen.Chaos in Screen.bottomNavItems)
    }

    @Test
    fun corruptingTheLatestReadingIsCaughtByChainVerification() = runBlocking {
        val dao = database.temperatureEventDao()
        HashChainService.buildChain(listOf(event(0, 5.0), event(1, 5.5))).forEach {
            dao.insertEvent(it.toEntity())
        }

        assertTrue(DebugTools.corruptLatestEvent(database, "SHIP-DEBUG", corruptedTemp = 99.9))

        val stored = dao.getTemperaturesDirect("SHIP-DEBUG").map { it.toDomain() }
        assertEquals(99.9, stored.last().temperature, 0.0)
        val result = HashChainService.verifyChain(stored)
        assertFalse(result.valid)
        assertEquals(IntegrityFailureReason.CURRENT_HASH_MISMATCH, result.reason)
    }

    @Test
    fun corruptingAnEmptyShipmentReportsNothingChanged() = runBlocking {
        assertFalse(DebugTools.corruptLatestEvent(database, "SHIP-NONE"))
    }
}
