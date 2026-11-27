package com.coldchain.handshake.repository

import android.content.SharedPreferences
import com.coldchain.handshake.data.local.ColdChainDatabase
import com.coldchain.handshake.ui.navigation.SessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whose records this phone holds. The app opens only for the person they belong to ([confirmedOwner]).
 *
 * Someone signing in after another person's session ended without a sign-out, for example when it expired, mustn't
 * see that person's records. If any of those still have to reach the server they're evidence, so the new session is
 * refused until their owner sends them; otherwise the phone is cleared for the new person. Signing out is refused
 * while anything recorded here still has to reach the server, and clears the phone when it succeeds.
 *
 * [forgetInMemory] drops what the app holds in memory for the last person (the simulator's shipment, the history
 * coordinator's state); the database is cleared here.
 */
class LocalSession(
    private val db: ColdChainDatabase,
    private val account: AccountRepository,
    private val ownerPrefs: SharedPreferences,
    private val forgetInMemory: () -> Unit
) : SessionGate {

    private val _localOwnerProblem = MutableStateFlow<String?>(null)
    override val localOwnerProblem: StateFlow<String?> = _localOwnerProblem.asStateFlow()

    private val _confirmedOwner = MutableStateFlow<String?>(null)
    override val confirmedOwner: StateFlow<String?> = _confirmedOwner.asStateFlow()

    /** Checks each person who signs in against the records' owner, for as long as [scope] lives. */
    fun watch(scope: CoroutineScope): Job = scope.launch {
        account.state.collect { state ->
            if (state !is AccountState.Ready) {
                _confirmedOwner.value = null
                return@collect
            }
            val previous = ownerPrefs.getString(KEY_OWNER, null)
            if (previous != null && previous != state.userId) {
                if (countUnsent() > 0) {
                    _confirmedOwner.value = null
                    _localOwnerProblem.value = "This phone holds another person's records that haven't " +
                        "reached the server. They need to sign in here and send them first."
                    account.signOut()
                    return@collect
                }
                forgetLocalData()
            }
            _localOwnerProblem.value = null
            ownerPrefs.edit().putString(KEY_OWNER, state.userId).apply()
            _confirmedOwner.value = state.userId
        }
    }

    override suspend fun signOut(): Result<Unit> {
        val unsent = withContext(Dispatchers.IO) { countUnsent() }
        if (unsent > 0) {
            return Result.failure(
                IllegalStateException("$unsent records haven't reached the server yet. Connect, let them send, then sign out.")
            )
        }
        forgetInMemory()
        return account.signOut().onSuccess { forgetLocalData() }
    }

    private suspend fun countUnsent(): Int {
        val onServer = db.shipmentDao().getAllShipmentsDirect().filter { it.genesisHash != null }.map { it.id }.toSet()
        return db.temperatureEventDao().getPendingEvents().count { it.shipmentId in onServer } +
            db.alertDao().getPendingAlerts().count { it.shipmentId in onServer } +
            db.handoverDao().getPendingHandovers().count { it.shipmentId in onServer } +
            // Location points are records too, and were deleted unsent before (Gate 5 review S7).
            db.gpsPointDao().getPending().count { it.shipmentId in onServer }
    }

    private suspend fun forgetLocalData() {
        forgetInMemory()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    companion object {
        const val OWNER_PREFS = "coldchain_local_owner"
        private const val KEY_OWNER = "user_id"
    }
}
