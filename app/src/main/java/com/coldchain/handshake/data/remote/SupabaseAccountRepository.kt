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
    private val scope: CoroutineScope
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
                    is SessionStatus.NotAuthenticated -> _state.value = AccountState.SignedOut
                    is SessionStatus.Initializing -> _state.value = AccountState.Loading
                    // Being unable to refresh, usually from having no signal, isn't being signed out.
                    is SessionStatus.RefreshFailure -> Unit
                }
            }
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

    override suspend fun refresh(): Result<Unit> = attempt { arrive() }

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

    @Serializable
    private data class ProfileRow(
        @kotlinx.serialization.SerialName("display_name") val displayName: String,
        val role: String
    )
}
