package com.coldchain.handshake.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserProfile(
    val displayName: String,
    val role: MemberRole
)

sealed interface AccountState {
    data object Loading : AccountState
    data object SignedOut : AccountState

    /** Signed in, but they haven't said who they are yet. */
    data class NeedsProfile(val userId: String, val email: String?) : AccountState

    /** Signed in, with no way to find out who they are until the server can be reached. */
    data class Unreachable(val userId: String, val email: String?) : AccountState

    /**
     * @property deviceProblem Why this phone can't act for this person, if it can't.
     */
    data class Ready(
        val userId: String,
        val email: String?,
        val profile: UserProfile,
        val deviceProblem: String? = null
    ) : AccountState
}

interface AccountRepository {
    val state: StateFlow<AccountState>

    /** Emails a one-time code, creating the account the first time. */
    suspend fun requestCode(email: String): Result<Unit>
    suspend fun verifyCode(email: String, code: String): Result<Unit>
    suspend fun completeProfile(displayName: String, role: MemberRole): Result<Unit>

    /** Looks at the server again, after [AccountState.Unreachable]. */
    suspend fun refresh(): Result<Unit>

    /** Safe to repeat: the server keeps the first key registered for a device. */
    suspend fun registerDevice(): Result<Unit>

    /** Always ends the session on this phone, even with no connection. */
    suspend fun signOut(): Result<Unit>

    companion object {
        /** For code running with no account system, which is always signed out. */
        val NONE: AccountRepository = object : AccountRepository {
            private val refused = Result.failure<Unit>(IllegalStateException("Accounts aren't available."))

            override val state = MutableStateFlow<AccountState>(AccountState.SignedOut).asStateFlow()
            override suspend fun requestCode(email: String) = refused
            override suspend fun verifyCode(email: String, code: String) = refused
            override suspend fun completeProfile(displayName: String, role: MemberRole) = refused
            override suspend fun refresh() = refused
            override suspend fun registerDevice() = refused
            override suspend fun signOut() = Result.success(Unit)
        }
    }
}
