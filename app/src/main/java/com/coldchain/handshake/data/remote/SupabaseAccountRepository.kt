package com.coldchain.handshake.data.remote

import com.coldchain.handshake.crypto.DeviceSigner
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.UserProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Lets a courier who signed in earlier keep working with no signal, when the profile can't be fetched.
 */
interface ProfileCache {
    fun load(userId: String): UserProfile?
    fun save(userId: String, profile: UserProfile)
    fun clear()
}

class SupabaseAccountRepository(
    private val client: SupabaseClient,
    private val remote: ColdChainRemote,
    private val signer: DeviceSigner,
    private val deviceId: () -> String,
    private val profileCache: ProfileCache,
    private val scope: CoroutineScope,
    private val offlineRetryMs: Long = OFFLINE_RETRY_MS
) : AccountRepository {

    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    override val state = _state.asStateFlow()

    init {
        scope.launch {
            // The client's lifecycle callbacks are off (SupabaseClientProvider), so nothing else loads the stored
            // session. Loading it before reading the status keeps the state Loading until then, never a brief
            // SignedOut, which a recording restarting after process death would take as a sign-out.
            runCatching { client.auth.loadFromStorage(autoRefresh = true) }
            client.auth.sessionStatus.collectLatest { status ->
                when (status) {
                    is SessionStatus.Authenticated -> arrive()
                    // Restarted with no signal, the client can't load a session whose token is due for refresh: it
                    // stays Initializing, or reports not signed in or a failed refresh (Gate 5 review S1; supabase-kt
                    // 3.8.0, seen in SupabaseAccountRepositoryTest). Signing out deletes the stored session and a
                    // missing signal doesn't, so a session still stored means "signed in, waiting for a signal".
                    else -> waitForSignal(status)
                }
            }
        }
    }

    /**
     * While the client can't load the session stored on this phone, the person is shown as signed in from it (with the
     * profile kept here, else Unreachable), and loading it is tried again now and then. Ends when the client's status
     * changes, which cancels this. With no session stored, they are signed out, or still loading while the client starts.
     */
    private suspend fun waitForSignal(status: SessionStatus) {
        val user = runCatching { client.auth.sessionManager.loadSessionOrNull() }.getOrNull()?.user
        if (user == null) {
            _state.value = if (status is SessionStatus.Initializing) AccountState.Loading else AccountState.SignedOut
            return
        }
        val cached = profileCache.load(user.id)
        _state.value = if (cached != null) AccountState.Ready(user.id, user.email, cached)
        else AccountState.Unreachable(user.id, user.email)
        while (true) {
            delay(offlineRetryMs)
            runCatching { client.auth.loadFromStorage(autoRefresh = true) }
        }
    }

    private suspend fun arrive() {
        val user = client.auth.currentUserOrNull()
        if (user == null) {
            _state.value = AccountState.SignedOut
            return
        }

        // The client retries a failed read for several seconds, which is too long to show a spinner.
        val cached = profileCache.load(user.id)
        if (cached != null) _state.value = AccountState.Ready(user.id, user.email, cached)

        val fetched = fetchProfile(user.id)
        val profile = fetched.getOrNull() ?: cached

        // Cached before it is shown, so whoever sees the new state finds the same profile in the cache.
        if (profile != null) profileCache.save(user.id, profile)

        _state.value = when {
            profile != null -> AccountState.Ready(user.id, user.email, profile)
            fetched.isSuccess -> AccountState.NeedsProfile(user.id, user.email)
            else -> AccountState.Unreachable(user.id, user.email)
        }

        if (profile != null) registerDevice()
    }

    private suspend fun fetchProfile(userId: String): Result<UserProfile?> = attempt {
        client.postgrest.from("profiles")
            .select(Columns.raw("display_name,role")) { filter { eq("user_id", userId) } }
            .decodeSingleOrNull<ProfileRow>()
            ?.let { UserProfile(it.displayName, MemberRole.valueOf(it.role.uppercase())) }
    }

    override suspend fun requestCode(email: String) = attempt {
        client.auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = true
        }
        Unit
    }

    override suspend fun verifyCode(email: String, code: String) = attempt {
        client.auth.verifyEmailOtp(OtpType.Email.EMAIL, email = email.trim(), token = code.trim())
        Unit
    }

    /**
     * The form that asks leaves the screen as soon as the person is Ready, cancelling its own coroutine, so what follows
     * (registering this phone) runs in the repository's scope and finishes anyway.
     */
    override suspend fun completeProfile(displayName: String, role: MemberRole): Result<Unit> =
        remote.completeProfile(displayName.trim(), role).onSuccess { scope.launch { arrive() }.join() }

    /** With a session stored but not loaded (no signal at the start), loading it again comes first. */
    override suspend fun refresh(): Result<Unit> = attempt {
        if (client.auth.currentUserOrNull() == null && client.auth.sessionManager.loadSessionOrNull() != null) {
            client.auth.loadFromStorage(autoRefresh = true)
        } else {
            arrive()
        }
    }

    override suspend fun registerDevice(): Result<Unit> {
        val result = attempt { signer.publicKey }
            .mapCatching { publicKey -> remote.registerDevice(deviceId(), publicKey).getOrThrow() }

        (result.exceptionOrNull() as? RemoteRejection)?.let {
            (_state.value as? AccountState.Ready)?.let { ready ->
                _state.value = ready.copy(deviceProblem = "This phone is registered to another account.")
            }
        }
        return result
    }

    override suspend fun signOut(): Result<Unit> = attempt {
        profileCache.clear()
        attempt { client.auth.signOut(SignOutScope.LOCAL) }
            .onFailure { client.auth.clearSession() }
    }

    /**
     * A dropped call can surface as a cancellation too, so only this coroutine's own cancellation
     * goes further. Anything else stopping here keeps the session watcher alive.
     */
    private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

    companion object {
        /** How often a session stored but not loaded is tried again while there is no signal. */
        const val OFFLINE_RETRY_MS = 30_000L
    }

    @Serializable
    private data class ProfileRow(
        @kotlinx.serialization.SerialName("display_name") val displayName: String,
        val role: String
    )
}
