package com.coldchain.handshake.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAccountRepository(initial: AccountState = AccountState.SignedOut) : AccountRepository {

    private val _state = MutableStateFlow(initial)
    override val state = _state.asStateFlow()

    val codesRequestedFor = mutableListOf<String>()
    val verified = mutableListOf<Pair<String, String>>()
    var profileCompleted: UserProfile? = null
    var signedOut = false
    var deviceRegistrations = 0

    /** When set, every call fails this way. */
    var failure: Throwable? = null

    fun setState(state: AccountState) {
        _state.value = state
    }

    private inline fun attempt(block: () -> Unit): Result<Unit> {
        failure?.let { return Result.failure(it) }
        block()
        return Result.success(Unit)
    }

    override suspend fun requestCode(email: String) = attempt { codesRequestedFor += email }

    override suspend fun verifyCode(email: String, code: String) = attempt {
        verified += email to code
        _state.value = AccountState.NeedsProfile("user-1", email)
    }

    override suspend fun completeProfile(displayName: String, role: MemberRole) = attempt {
        val profile = UserProfile(displayName, role)
        profileCompleted = profile
        _state.value = AccountState.Ready("user-1", null, profile)
    }

    override suspend fun refresh() = attempt { }

    override suspend fun registerDevice() = attempt { deviceRegistrations++ }

    override suspend fun signOut() = attempt {
        signedOut = true
        _state.value = AccountState.SignedOut
    }
}
