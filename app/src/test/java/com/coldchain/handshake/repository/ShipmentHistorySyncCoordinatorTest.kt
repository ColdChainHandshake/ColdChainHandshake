package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ShipmentHistorySyncCoordinatorTest {

    private val server = FakeColdChainServer(currentUser = "courier")
    private val genesis = server.seedShipment("SHIP-1")
    private val telemetry = InMemoryTelemetryRepository()
    private val coordinator = ShipmentHistorySyncCoordinator(telemetry, server)

    private val chain = HashChainService.buildChain(
        (1..5).map {
            TemperatureEvent("TE-$it", "SHIP-1", "LOG-1", 1_700_000_000_000L + it * 60_000L, 5.0, "", "", SyncStatus.PENDING)
        },
        genesis
    )

    private suspend fun stored() = telemetry.getTemperatures("SHIP-1").first().sortedBy { it.seq }

    private fun serverHas(readings: List<TemperatureEvent>) = runBlocking {
        server.appendReadings("SHIP-1", readings, genesis, "phone", "signature").getOrThrow()
    }

    // One coordinator lives as long as the process (5.1c). When the phone is cleared for the next person, what it
    // knew about the last person's shipments must go too, or their history would look complete before it is fetched.
    @Test
    fun clearingForgetsEveryShipmentsStateAndServerHead() = runBlocking {
        serverHas(chain)
        coordinator.hydrateHistory("SHIP-1").getOrThrow()

        coordinator.clear()

        assertEquals(HistorySyncState.IDLE, coordinator.getSyncState("SHIP-1").first())
        assertNull(coordinator.getServerHead("SHIP-1").value)
    }

    // The Handover screen fetches again every 3 s while shown. Before 26 Nov each fetch set the state back to syncing,
    // so the screen showed "waiting for the history" for a moment every 3 s and its Confirm button jumped under the
    // courier's finger on the S24. A history already complete stays complete while it is fetched again.
    @Test
    fun fetchingAgainKeepsAHistoryAlreadyCompleteReady() = runBlocking {
        serverHas(chain)
        coordinator.hydrateHistory("SHIP-1").getOrThrow()
        server.neverFinishes = true

        val again = launch(Dispatchers.Default) { coordinator.hydrateHistory("SHIP-1") }
        delay(200)

        assertEquals(HistorySyncState.HISTORY_READY, coordinator.getSyncState("SHIP-1").first())
        again.cancel()
    }

    @Test
    fun theFirstFetchShowsTheHistoryAsStillComing() = runBlocking {
        server.neverFinishes = true

        val first = launch(Dispatchers.Default) { coordinator.hydrateHistory("SHIP-1") }
        delay(200)

        assertEquals(HistorySyncState.HISTORY_SYNCING, coordinator.getSyncState("SHIP-1").first())
        first.cancel()
    }

    @Test
    fun theServersReadingsAndHeadComeDown() = runBlocking {
        serverHas(chain)

        coordinator.hydrateHistory("SHIP-1").getOrThrow()

        assertEquals(chain.map { it.currentHash }, stored().map { it.currentHash })
        assertEquals(server.heads.getValue("SHIP-1"), coordinator.getServerHead("SHIP-1").value)
        assertEquals(HistorySyncState.HISTORY_READY, coordinator.getSyncState("SHIP-1").first())
    }

    @Test
    fun onlyMissingReadingsAreFetched() = runBlocking {
        serverHas(chain)
        telemetry.saveTemperatures(chain.take(2)).getOrThrow()

        assertEquals(3, coordinator.hydrateHistory("SHIP-1").getOrThrow())
    }

    // Finding 11: a watcher asks again and again, and most times nothing is new. Then the head alone says so, and the
    // readings aren't asked for.
    @Test
    fun aFetchWithNothingNewAsksOnlyForTheHead() = runBlocking {
        serverHas(chain)
        coordinator.hydrateHistory("SHIP-1").getOrThrow()
        val readsBefore = server.readCalls

        assertEquals(0, coordinator.hydrateHistory("SHIP-1").getOrThrow())

        assertEquals(readsBefore, server.readCalls)
        assertEquals(HistorySyncState.HISTORY_READY, coordinator.getSyncState("SHIP-1").first())
    }

    @Test
    fun aHeadThatMovedOnStillFetchesTheNewReadings() = runBlocking {
        serverHas(chain.take(3))
        coordinator.hydrateHistory("SHIP-1").getOrThrow()
        server.currentUser = "courier"
        server.appendReadings("SHIP-1", chain.drop(3), chain[2].currentHash, "phone", "signature").getOrThrow()

        assertEquals(2, coordinator.hydrateHistory("SHIP-1").getOrThrow())
        assertEquals(5, stored().size)
    }

    @Test
    fun aGapHereIsFilled() = runBlocking {
        serverHas(chain)
        telemetry.saveTemperatures(chain.filter { it.seq != 2L }).getOrThrow()

        coordinator.hydrateHistory("SHIP-1").getOrThrow()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), stored().map { it.seq })
    }

    @Test
    fun aDifferentReadingHereIsNeverOverwritten() = runBlocking {
        serverHas(chain)
        val edited = chain[1].copy(id = "edited", temperature = 4.0)
        telemetry.saveTemperatures(listOf(chain[0], edited)).getOrThrow()

        coordinator.hydrateHistory("SHIP-1").getOrThrow()

        assertEquals(4.0, stored().single { it.seq == 2L }.temperature, 0.0)
    }

    @Test
    fun aFailedFetchLeavesTheHeadUnknown() = runBlocking {
        server.offlineError = IOException("No connection")

        assertTrue(coordinator.hydrateHistory("SHIP-1").isFailure)

        assertEquals(HistorySyncState.SYNC_ERROR, coordinator.getSyncState("SHIP-1").first())
        assertNull(coordinator.getServerHead("SHIP-1").value)
    }
}
