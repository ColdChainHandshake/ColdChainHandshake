package com.coldchain.handshake.repository

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake must refuse what `append_readings` refuses, or tests pass that the real server would fail. The first real
 * run of `LiveSupabaseIntegrationTest` found the fake had no clock rule (CC009).
 */
class FakeColdChainServerTest {

    private val minute = 60_000L
    private val createdAt = 1_700_000_000_000L

    private fun chain(genesis: String, vararg minutesAfterCreation: Long): List<TemperatureEvent> =
        HashChainService.buildChain(
            minutesAfterCreation.mapIndexed { i, m ->
                TemperatureEvent("R-$i", "SHIP-1", "LOG-1", createdAt + m * minute, 5.0, "", "", SyncStatus.PENDING)
            },
            genesis
        )

    private suspend fun FakeColdChainServer.append(readings: List<TemperatureEvent>, previousHead: String) =
        appendReadings("SHIP-1", readings, previousHead, "device", "signature")

    @Test
    fun aReadingMoreThanTheToleranceBeforeCreationIsRefusedAsTheServerDoes() = runBlocking {
        val server = FakeColdChainServer()
        val genesis = server.seedShipment("SHIP-1", createdAt = createdAt)

        val refused = server.append(chain(genesis, -6), genesis)

        assertEquals(RemoteRejection.CLOCK, (refused.exceptionOrNull() as RemoteRejection).code)
        assertTrue(server.append(chain(genesis, -4), genesis).isSuccess)
    }

    @Test
    fun aReadingNoLaterThanTheOneBeforeIsRefusedAsTheServerDoes() = runBlocking {
        val server = FakeColdChainServer()
        val genesis = server.seedShipment("SHIP-1", createdAt = createdAt)
        val first = chain(genesis, 1, 2)
        val head = server.append(first, genesis).getOrThrow()

        val sameTime = HashChainService.appendHash(
            TemperatureEvent("R-2", "SHIP-1", "LOG-1", createdAt + 2 * minute, 5.0, "", "", SyncStatus.PENDING, seq = 3),
            head.headHash
        )
        val refused = server.append(listOf(sameTime), head.headHash)

        assertEquals(RemoteRejection.CLOCK, (refused.exceptionOrNull() as RemoteRejection).code)
    }
}
