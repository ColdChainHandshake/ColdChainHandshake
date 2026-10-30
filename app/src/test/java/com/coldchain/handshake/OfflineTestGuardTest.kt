package com.coldchain.handshake

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ConnectException
import java.net.URL

/**
 * Default unit tests must never reach a real server, because `local.properties` can point
 * BuildConfig at a shared project. The guard lives in `app/build.gradle`, so no flag is needed.
 */
class OfflineTestGuardTest {

    @Test
    fun outboundHttpsIsBlockedInDefaultTestRuns() {
        assumeTrue(System.getenv("CCH_LIVE_SUPABASE_URL").isNullOrBlank())

        val connection = URL("https://example.com").openConnection().apply { connectTimeout = 3_000 }
        val failure = runCatching { connection.connect() }.exceptionOrNull()

        assertTrue("Expected the dead test proxy to refuse this, got: $failure", failure is ConnectException)
    }
}
