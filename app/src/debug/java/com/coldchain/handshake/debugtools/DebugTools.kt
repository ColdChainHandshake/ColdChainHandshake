package com.coldchain.handshake.debugtools

import android.content.Context
import androidx.compose.runtime.Composable
import com.coldchain.handshake.data.local.ColdChainDatabase
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.dao.ChaosEventDao
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.entities.ChaosEventEntity
import com.coldchain.handshake.data.local.entities.toDomain
import com.coldchain.handshake.data.network.ChaosAwareNetworkMonitor
import com.coldchain.handshake.data.network.NetworkMonitor
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.repository.ChaosEngineService
import com.coldchain.handshake.repository.impl.InMemoryChaosEngineService
import com.coldchain.handshake.ui.screens.ChaosScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Fault injection for debug builds. `src/release` holds an object of the same name with none of
 * this in it, so release code cannot compile against these tools.
 */
object DebugTools {

    const val ENABLED = true

    fun createChaosService(): ChaosEngineService = InMemoryChaosEngineService()

    fun wrapNetworkMonitor(monitor: NetworkMonitor, chaosService: ChaosEngineService): NetworkMonitor =
        ChaosAwareNetworkMonitor(monitor, chaosService)

    @Composable
    fun ChaosLab() {
        ChaosScreen()
    }

    fun chaosEventDao(context: Context): ChaosEventDao =
        DatabaseProvider.getDatabase(context).chaosEventDao()

    /**
     * Changes a stored reading without updating its hash, to exercise tamper detection at handover.
     * It uses raw SQL so that no production DAO can do this.
     */
    suspend fun corruptLatestEvent(
        database: ColdChainDatabase,
        shipmentId: String,
        corruptedTemp: Double = 99.9
    ): Boolean = withContext(Dispatchers.IO) {
        val latest = database.temperatureEventDao().getTemperaturesDirect(shipmentId).lastOrNull()
            ?: return@withContext false
        database.openHelper.writableDatabase.execSQL(
            "UPDATE temperature_events SET temperature = ? WHERE id = ?",
            arrayOf<Any>(corruptedTemp, latest.id)
        )
        database.invalidationTracker.refreshVersionsAsync()
        true
    }

    /**
     * Rewrites every stored reading from [fromSeq] on to [temperature] and recomputes the hashes, so the chain on this
     * phone still adds up: the rewrite a holder of the readings could make. Only an independent record catches it, here
     * the shipment's logger in debug local mode (4.1). Raw SQL, so that no production DAO can do this.
     *
     * @return how many readings were rewritten.
     */
    suspend fun rewriteHistoryFrom(
        database: ColdChainDatabase,
        shipmentId: String,
        fromSeq: Long,
        temperature: Double
    ): Int = withContext(Dispatchers.IO) {
        val held = database.temperatureEventDao().getTemperaturesDirect(shipmentId).map { it.toDomain() }
        if (held.none { it.seq >= fromSeq }) return@withContext 0
        val rewritten = HashChainService.buildChain(
            held.map { if (it.seq >= fromSeq) it.copy(temperature = temperature) else it },
            held.first().previousHash
        ).filter { it.seq >= fromSeq }
        rewritten.forEach {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE temperature_events SET temperature = ?, previousHash = ?, currentHash = ? WHERE id = ?",
                arrayOf<Any>(it.temperature, it.previousHash, it.currentHash, it.id)
            )
        }
        database.invalidationTracker.refreshVersionsAsync()
        rewritten.size
    }

    suspend fun logChaosEvent(
        context: Context,
        shipmentId: String,
        scenarioType: String,
        message: String,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) {
        chaosEventDao(context).insertChaosEvent(
            ChaosEventEntity(
                id = UUID.randomUUID().toString(),
                shipmentId = shipmentId,
                scenarioType = scenarioType,
                timestamp = System.currentTimeMillis(),
                message = message,
                syncStatus = syncStatus
            )
        )
    }
}
