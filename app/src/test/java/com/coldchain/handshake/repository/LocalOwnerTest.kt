package com.coldchain.handshake.repository

import android.content.Context
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the next person to sign in on a phone may see of the last person's records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalOwnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = FakeAccountRepository()
    private val server = FakeColdChainServer(currentUser = "courier")
    private val genesis = server.seedShipment("SHIP-1")

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        context.getSharedPreferences("coldchain_local_owner", Context.MODE_PRIVATE).edit()
            .putString("user_id", "previous-person").commit()
        RepositoryProvider.accountRepo = account
        RepositoryProvider.remoteService = server
        RepositoryProvider.initialize(context)
    }

    @After
    fun tearDown() {
        RepositoryProvider.reset()
        DatabaseProvider.closeForTesting()
    }

    private suspend fun leaveReadings(status: SyncStatus) {
        ShipmentWorkflow(server, RepositoryProvider.shipmentRepository) { "phone" }.open("SHIP-1").getOrThrow()
        val readings = HashChainService.buildChain(
            (1..3).map { TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", 1_700_000_000_000L + it * 60_000L, 5.0, "", "", status) },
            genesis
        )
        RepositoryProvider.telemetryRepository.saveTemperatures(readings).getOrThrow()
    }

    private fun signIn(user: String) {
        account.setState(AccountState.Ready(user, null, UserProfile(user, MemberRole.COURIER)))
    }

    @Test
    fun anotherPersonsUnsentRecordsAreKeptAndTheNewSessionRefused() = runBlocking {
        leaveReadings(SyncStatus.PENDING)

        signIn("someone-new")

        withTimeout(5_000) { while (!account.signedOut) delay(20) }
        assertNotNull(RepositoryProvider.localOwnerProblem.value)
        assertEquals(null, RepositoryProvider.confirmedOwner.value)
        assertEquals(3, RepositoryProvider.telemetryRepository.getTemperatures("SHIP-1").first().size)
    }

    @Test
    fun anotherPersonsSentRecordsAreClearedForTheNextPerson() = runBlocking {
        leaveReadings(SyncStatus.SYNCED)

        signIn("someone-new")

        withTimeout(5_000) { RepositoryProvider.confirmedOwner.first { it == "someone-new" } }

        assertTrue(RepositoryProvider.shipmentRepository.getAllShipments().first().isEmpty())
        assertFalse(account.signedOut)
        assertTrue(RepositoryProvider.telemetryRepository.getTemperatures("SHIP-1").first().isEmpty())
    }

    @Test
    fun theSamePersonSigningInAgainKeepsEverything() = runBlocking {
        leaveReadings(SyncStatus.PENDING)

        signIn("previous-person")
        withTimeout(5_000) { RepositoryProvider.confirmedOwner.first { it == "previous-person" } }

        assertFalse(account.signedOut)
        assertEquals(3, RepositoryProvider.telemetryRepository.getTemperatures("SHIP-1").first().size)
    }
}
