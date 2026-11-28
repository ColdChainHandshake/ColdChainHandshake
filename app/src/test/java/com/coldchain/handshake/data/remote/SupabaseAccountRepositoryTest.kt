package com.coldchain.handshake.data.remote

import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.UserProfile
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The real auth client against a fake HTTP server. `LiveSupabaseIntegrationTest` covers the real one.
 */
class SupabaseAccountRepositoryTest {

    private val session = """
        {"access_token":"token","refresh_token":"refresh","expires_in":3600,"token_type":"bearer",
         "user":{"id":"user-1","email":"ana@example.com","aud":"authenticated"}}
    """.trimIndent()

    /** Written by the mock engine's threads while tests read it. */
    private val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    private var profileRows = "[]"
    private var profileFails = false
    private var verifyFails = false
    private var logoutFails = false
    private var tokenFails = false

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        requests += path to body

        return when {
            path == "/auth/v1/otp" -> json("{}")
            path == "/auth/v1/verify" && verifyFails -> json(
                """{"code":403,"error_code":"otp_expired","msg":"Token has expired or is invalid"}""",
                HttpStatusCode.Forbidden
            )
            path == "/auth/v1/verify" -> json(session)
            path == "/auth/v1/logout" && logoutFails -> throw IOException("No connection")
            path == "/auth/v1/logout" -> json("{}")
            path == "/auth/v1/token" && tokenFails -> throw IOException("No connection")
            path == "/auth/v1/token" -> json(session)
            path == "/rest/v1/profiles" && profileFails -> throw IOException("No connection")
            path == "/rest/v1/profiles" -> json(profileRows)
            else -> json("null")
        }
    }

    private val client = createSupabaseClient("http://account.test", "key") {
        httpEngine = MockEngine { request -> answer(request) }
        install(Postgrest)
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            autoLoadFromStorage = false
            alwaysAutoRefresh = false
            enableLifecycleCallbacks = false
        }
    }

    private class MemoryProfileCache : ProfileCache {
        var stored: Pair<String, UserProfile>? = null
        override fun load(userId: String) = stored?.takeIf { it.first == userId }?.second
        override fun save(userId: String, profile: UserProfile) {
            stored = userId to profile
        }
        override fun clear() {
            stored = null
        }
    }

    private val signer = object : DeviceSigner {
        override val publicKey = "public-key"
        override fun sign(payload: ByteArray) = "signature"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = FakeColdChainServer()
    private val cache = MemoryProfileCache()
    private val repository = SupabaseAccountRepository(client, server, signer, { "device-1" }, cache, scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private companion object {
        /** supabase-kt 3.8.0 tries a read four times over about seven seconds before failing. */
        const val RETRIES_GIVE_UP_MS = 15_000L
        /** Well inside `RecordingLoop`'s 30 s wait for the account after a restart. */
        const val RESTORE_WAIT_MS = 10_000L
    }

    private suspend fun awaitState(matches: (AccountState) -> Boolean): AccountState =
        withTimeout(RETRIES_GIVE_UP_MS) { repository.state.first(matches) }

    private suspend fun signIn(): AccountState {
        repository.verifyCode("ana@example.com", "123456").getOrThrow()
        return awaitState { it !is AccountState.SignedOut && it !is AccountState.Loading }
    }

    @Test
    fun nobodySignedInSeesTheSignedOutState() = runBlocking {
        awaitState { it is AccountState.SignedOut }
        Unit
    }

    @Test
    fun askingForACodeCreatesTheAccountIfNeeded() = runBlocking {
        repository.requestCode(" ana@example.com ").getOrThrow()

        val body = requests.single { it.first == "/auth/v1/otp" }.second
        assertTrue(body, body.contains("\"email\":\"ana@example.com\""))
        assertTrue(body, body.contains("\"create_user\":true"))
    }

    @Test
    fun aWrongCodeIsAFailureAndStaysSignedOut() = runBlocking {
        verifyFails = true

        assertTrue(repository.verifyCode("ana@example.com", "000000").isFailure)

        assertEquals(AccountState.SignedOut, awaitState { it !is AccountState.Loading })
    }

    @Test
    fun aFirstSignInAsksWhoTheyAre() = runBlocking {
        assertEquals(AccountState.NeedsProfile("user-1", "ana@example.com"), signIn())
    }

    @Test
    fun aReturningPersonIsReadyAndTheirPhoneIsRegistered() = runBlocking {
        profileRows = """[{"display_name":"Ana","role":"courier"}]"""

        val state = signIn()

        assertEquals(UserProfile("Ana", MemberRole.COURIER), (state as AccountState.Ready).profile)
        withTimeout(5_000) { while (server.devices.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("public-key", server.devices["device-1"])
    }

    @Test
    fun completingAProfileMakesThemReady() = runBlocking {
        signIn()
        profileRows = """[{"display_name":"Ana","role":"receiver"}]"""

        repository.completeProfile(" Ana ", MemberRole.RECEIVER).getOrThrow()

        assertEquals(MemberRole.RECEIVER, (repository.state.value as AccountState.Ready).profile.role)
    }

    // The "About you" form calls this from its own scope, which leaves the screen the moment they are Ready. On the
    // emulator (27 Nov) a new courier's phone was registered only when the app was opened again.
    @Test
    fun completingAProfileRegistersThePhoneEvenWhenTheFormLeavesAtOnce() = runBlocking {
        val slowServer = object : ColdChainRemote by server {
            override suspend fun registerDevice(deviceId: String, publicKey: String): Result<Unit> {
                delay(200)
                return server.registerDevice(deviceId, publicKey)
            }
        }
        val newcomer = SupabaseAccountRepository(client, slowServer, signer, { "device-1" }, cache, scope)
        newcomer.verifyCode("ana@example.com", "123456").getOrThrow()
        withTimeout(RETRIES_GIVE_UP_MS) { newcomer.state.first { it is AccountState.NeedsProfile } }
        profileRows = """[{"display_name":"Ana","role":"courier"}]"""

        val form = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        form.launch { newcomer.completeProfile("Ana", MemberRole.COURIER) }
        withTimeout(RETRIES_GIVE_UP_MS) { newcomer.state.first { it is AccountState.Ready } }
        form.cancel()

        withTimeout(2_000) { while (server.devices.isEmpty()) delay(10) }
        assertEquals("public-key", server.devices["device-1"])
    }

    /**
     * The app's process started again, as after a kill, with a session stored on the phone whose access token is
     * [secondsLeft] from expiring out of an hour. The client is configured as `SupabaseClientProvider` builds the app's:
     * the library's defaults, with only the lifecycle callbacks off.
     */
    private fun restartedWithAStoredSession(secondsLeft: Long): SupabaseAccountRepository {
        val expiresAt = System.currentTimeMillis() / 1000 + secondsLeft
        // The client works the expiry out from expires_in when a session is decoded, so it is set afterwards.
        val stored = Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
            """{"access_token":"old","refresh_token":"refresh","expires_in":3600,"token_type":"bearer",
               "user":{"id":"user-1","email":"ana@example.com","aud":"authenticated"}}"""
        ).copy(expiresAt = kotlin.time.Instant.fromEpochSeconds(expiresAt))
        assertEquals("the stored token's expiry", expiresAt, stored.expiresAt.epochSeconds)
        val sessions = MemorySessionManager().also { runBlocking { it.saveSession(stored) } }
        val restarted = createSupabaseClient("http://account.test", "key") {
            httpEngine = MockEngine { request -> answer(request) }
            install(Postgrest)
            install(Auth) {
                sessionManager = sessions
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
            }
        }
        return SupabaseAccountRepository(restarted, server, signer, { "device-1" }, cache, scope, offlineRetryMs = 200L)
    }

    // Gate 5 review S1 (28 Nov): restarted with no signal and a token in the last fifth of its life, the client
    // can't load the session, and the account stayed Loading for as long as the signal was gone; recording after a
    // kill gave up after 30 s and the courier saw only the loading screen.
    @Test
    fun restartedOfflineWithATokenDueForRefreshTheCourierIsStillReady() = runBlocking {
        cache.save("user-1", UserProfile("Ana", MemberRole.COURIER))
        tokenFails = true
        profileFails = true

        val restarted = restartedWithAStoredSession(secondsLeft = 300)
        val seen = java.util.concurrent.CopyOnWriteArrayList<AccountState>()
        val watching = scope.launch { restarted.state.collect { seen += it } }

        // The client tries the refresh, and it fails for want of a signal: the case this test is about. On CI the
        // account was Ready before that request went out (28 Nov), so the test waits for it rather than racing it.
        withTimeout(RESTORE_WAIT_MS) { while (requests.none { it.first == "/auth/v1/token" }) delay(10) }
        delay(500)
        watching.cancel()

        assertTrue("never shown as signed out: $seen", seen.none { it is AccountState.SignedOut })
        assertEquals(
            AccountState.Ready("user-1", "ana@example.com", UserProfile("Ana", MemberRole.COURIER)),
            restarted.state.value
        )
    }

    // Gate 5 review, mutant 24: a refresh that fails isn't a sign-out, which would make a restart forget the recording.
    @Test
    fun restartedOfflineWithAnExpiredTokenIsNotSignedOut() = runBlocking {
        cache.save("user-1", UserProfile("Ana", MemberRole.COURIER))
        tokenFails = true
        profileFails = true

        val restarted = restartedWithAStoredSession(secondsLeft = -60)

        val state = withTimeout(RESTORE_WAIT_MS) { restarted.state.first { it !is AccountState.Loading } }
        assertTrue("$state", state is AccountState.Ready)
    }

    // Gate 5 re-check R1: shown as signed in from the stored session, a real sign-out still signs them out.
    @Test
    fun signingOutWhileRestartedOfflineSignsThemOut() = runBlocking {
        cache.save("user-1", UserProfile("Ana", MemberRole.COURIER))
        tokenFails = true
        logoutFails = true
        val restarted = restartedWithAStoredSession(secondsLeft = 300)
        withTimeout(RESTORE_WAIT_MS) { restarted.state.first { it is AccountState.Ready } }

        restarted.signOut().getOrThrow()

        withTimeout(RESTORE_WAIT_MS) { restarted.state.first { it is AccountState.SignedOut } }
        assertNull(cache.stored)
    }

    @Test
    fun restartedOfflineTheAccountCatchesUpOnceTheSignalReturns() = runBlocking {
        cache.save("user-1", UserProfile("Ana", MemberRole.COURIER))
        profileRows = """[{"display_name":"Ana","role":"courier"}]"""
        tokenFails = true
        val restarted = restartedWithAStoredSession(secondsLeft = 300)
        withTimeout(RESTORE_WAIT_MS) { restarted.state.first { it !is AccountState.Loading } }
        server.devices.clear()

        tokenFails = false

        // Nobody calls refresh: the repository tries again by itself. Signed in again for real, the phone is
        // registered with the server, which needs a working session.
        withTimeout(RETRIES_GIVE_UP_MS) { while (server.devices.isEmpty()) delay(50) }
        assertEquals("public-key", server.devices["device-1"])
    }

    @Test
    fun aCachedProfileLetsThemWorkStraightAwayWithNoSignal() = runBlocking {
        cache.save("user-1", UserProfile("Ana", MemberRole.COURIER))
        profileFails = true

        // Much less than the client's retries take: nobody should wait on a spinner in a cold store.
        withTimeout(2_000) { assertTrue(signIn() is AccountState.Ready) }
    }

    @Test
    fun aCachedProfileIsReplacedWhenTheServerAnswers() = runBlocking {
        cache.save("user-1", UserProfile("Old Name", MemberRole.COURIER))
        profileRows = """[{"display_name":"New Name","role":"courier"}]"""

        signIn()

        awaitState { (it as? AccountState.Ready)?.profile?.displayName == "New Name" }
        assertEquals("New Name", cache.stored?.second?.displayName)
    }

    @Test
    fun withNoCacheAndNoSignalTheyAreNotAskedToChooseARoleAgain() = runBlocking {
        profileFails = true

        assertTrue(signIn() is AccountState.Unreachable)
    }

    @Test
    fun comingBackOnlineMovesPastUnreachable() = runBlocking {
        profileFails = true
        signIn()
        profileFails = false
        profileRows = """[{"display_name":"Ana","role":"courier"}]"""

        repository.refresh().getOrThrow()

        assertTrue(repository.state.value is AccountState.Ready)
    }

    @Test
    fun aPhoneBelongingToAnotherAccountIsReported() = runBlocking {
        profileRows = """[{"display_name":"Ana","role":"courier"}]"""
        server.devices["device-1"] = "someone-elses-key"

        signIn()

        val state = awaitState { (it as? AccountState.Ready)?.deviceProblem != null }
        assertEquals("This phone is registered to another account.", (state as AccountState.Ready).deviceProblem)
    }

    @Test
    fun registeringAgainIsHarmless() = runBlocking {
        repository.registerDevice().getOrThrow()

        assertTrue(repository.registerDevice().isSuccess)
    }

    @Test
    fun aRefusedRegistrationKeepsItsCode() = runBlocking {
        server.devices["device-1"] = "someone-elses-key"

        val failure = repository.registerDevice().exceptionOrNull()

        assertEquals(RemoteRejection.DEVICE, (failure as RemoteRejection).code)
    }

    @Test
    fun signingOutForgetsThemOnThisPhone() = runBlocking {
        profileRows = """[{"display_name":"Ana","role":"courier"}]"""
        signIn()

        repository.signOut().getOrThrow()

        awaitState { it is AccountState.SignedOut }
        assertNull(cache.stored)
        assertNull(client.auth.currentSessionOrNull())
    }

    @Test
    fun signingOutWorksWithNoSignal() = runBlocking {
        profileRows = """[{"display_name":"Ana","role":"courier"}]"""
        signIn()
        logoutFails = true

        repository.signOut().getOrThrow()

        awaitState { it is AccountState.SignedOut }
        assertNull(client.auth.currentSessionOrNull())
    }
}
