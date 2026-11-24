package com.coldchain.handshake.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val db = DatabaseProvider.getDatabase(context)
    private val shipmentRepository = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetryRepository = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: LocalSession

    @Before
    fun setUp() {
        val owner = context.getSharedPreferences(LocalSession.OWNER_PREFS, Context.MODE_PRIVATE)
        owner.edit().putString("user_id", "previous-person").commit()
        session = LocalSession(db, account, owner, forgetInMemory = {})
        session.watch(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        DatabaseProvider.closeForTesting()
    }

    private suspend fun leaveReadings(status: SyncStatus) {
        ShipmentWorkflow(server, shipmentRepository) { "phone" }.open("SHIP-1").getOrThrow()
        val readings = HashChainService.buildChain(
            (1..3).map { TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", 1_700_000_000_000L + it * 60_000L, 5.0, "", "", status) },
            genesis
        )
        telemetryRepository.saveTemperatures(readings).getOrThrow()
    }

    private fun signIn(user: String) {
        account.setState(AccountState.Ready(user, null, UserProfile(user, MemberRole.COURIER)))
    }

    @Test
    fun anotherPersonsUnsentRecordsAreKeptAndTheNewSessionRefused() = runBlocking {
        leaveReadings(SyncStatus.PENDING)

        signIn("someone-new")

        withTimeout(5_000) { while (!account.signedOut) delay(20) }
        assertNotNull(session.localOwnerProblem.value)
        assertEquals(null, session.confirmedOwner.value)
        assertEquals(3, telemetryRepository.getTemperatures("SHIP-1").first().size)
    }

    @Test
    fun anotherPersonsSentRecordsAreClearedForTheNextPerson() = runBlocking {
        leaveReadings(SyncStatus.SYNCED)

        signIn("someone-new")

        withTimeout(5_000) { session.confirmedOwner.first { it == "someone-new" } }

        assertTrue(shipmentRepository.getAllShipments().first().isEmpty())
        assertFalse(account.signedOut)
        assertTrue(telemetryRepository.getTemperatures("SHIP-1").first().isEmpty())
    }

    @Test
    fun theSamePersonSigningInAgainKeepsEverything() = runBlocking {
        leaveReadings(SyncStatus.PENDING)

        signIn("previous-person")
        withTimeout(5_000) { session.confirmedOwner.first { it == "previous-person" } }

        assertFalse(account.signedOut)
        assertEquals(3, telemetryRepository.getTemperatures("SHIP-1").first().size)
    }
}
