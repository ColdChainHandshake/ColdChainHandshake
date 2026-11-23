package com.coldchain.handshake.ui.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.ui.screens.roleLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The phone's check that its stored records belong to the person signing in, and signing out, which is refused
 * while anything still has to reach the server.
 */
interface SessionGate {
    /** The signed-in person this phone's records have been checked against. */
    val confirmedOwner: StateFlow<String?>

    /** Why the last person to sign in was signed straight out again, if they were. */
    val localOwnerProblem: StateFlow<String?>
    suspend fun signOut(): Result<Unit>
}

/** The screen the app shows at its top level. */
sealed interface RootScreen {
    data object SignIn : RootScreen
    data object Loading : RootScreen
    data object NeedsProfile : RootScreen
    data object Unreachable : RootScreen
    data class Main(val statusLine: String, val actionLabel: String, val localOnly: Boolean) : RootScreen
}

@HiltViewModel
class AppRootViewModel @Inject constructor(
    val account: AccountRepository,
    private val session: SessionGate,
    private val saved: SavedStateHandle
) : ViewModel() {

    // Saved, so local mode survives the process being killed in the background.
    private val localOnly = saved.getStateFlow(KEY_LOCAL_ONLY, false)
    private val signOutProblem = MutableStateFlow<String?>(null)

    val screen: StateFlow<RootScreen> =
        combine(account.state, localOnly, session.confirmedOwner, signOutProblem, ::screenFor)
            .stateIn(viewModelScope, SharingStarted.Eagerly, screenFor(account.state.value, localOnly.value, session.confirmedOwner.value, null))

    val ownerProblem: StateFlow<String?> = session.localOwnerProblem

    fun useWithoutAccount() {
        saved[KEY_LOCAL_ONLY] = true
    }

    fun leaveLocalMode() {
        saved[KEY_LOCAL_ONLY] = false
    }

    fun signOut() {
        viewModelScope.launch { signOutProblem.value = session.signOut().exceptionOrNull()?.message }
    }

    private companion object {
        const val KEY_LOCAL_ONLY = "localOnly"
        const val LOCAL_ONLY_LINE = "Local only. Nothing here reaches the server."

        fun screenFor(state: AccountState, localOnly: Boolean, confirmedOwner: String?, signOutProblem: String?): RootScreen =
            when {
                localOnly -> RootScreen.Main(LOCAL_ONLY_LINE, "Sign In", localOnly = true)
                // Until this phone's records are checked against the person signing in, nothing of them shows.
                state is AccountState.Ready && confirmedOwner != state.userId -> RootScreen.Loading
                state is AccountState.Ready -> RootScreen.Main(
                    statusLine = signOutProblem ?: state.deviceProblem ?: "${state.profile.displayName}, ${roleLabel(state.profile.role)}",
                    actionLabel = "Sign Out",
                    localOnly = false
                )
                state is AccountState.NeedsProfile -> RootScreen.NeedsProfile
                state is AccountState.Unreachable -> RootScreen.Unreachable
                state is AccountState.Loading -> RootScreen.Loading
                else -> RootScreen.SignIn
            }
    }
}
