package com.coldchain.handshake.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.handover.PendingReleases
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.SyncService
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The background sync: it sends and renews once there is a connection, and asks to be retried when that fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val handovers = HandoverRepositoryImpl(db.handoverDao())
    private val server = FakeColdChainServer(currentUser = "courier").apply { seedShipment("SHIP-1") }
    private val pendingReleases = PendingReleases.inMemory()
    private val handover = ServerHandover(
        remote = server,
        orchestrator = HandoverOrchestrator(shipments, telemetry, handovers),
        shipments = shipments,
        handovers = handovers,
        history = ShipmentHistorySyncCoordinator(telemetry, server),
        uploadPending = { Result.success(Unit) },
        deviceId = { "courier-phone" },
        pendingReleases = pendingReleases
    )
    private var sendResult: Result<Unit> = Result.success(Unit)
    private var sends = 0
    private val sync = object : SyncService {
        override suspend fun syncPendingData(): Result<Unit> {
            sends++
            return sendResult
        }
        override fun isOnline() = true
        override fun getSyncStatus(): Flow<SyncStatus> = flowOf(SyncStatus.SYNCED)
    }
    private val workers = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
            if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters, sync, handover) else null
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(workers).build()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(workers).build().doWork()
    }

    @Test
    fun itSendsAndRenewsAWaitingHandover() {
        pendingReleases.put("SHIP-1", "receiver")

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, sends)
        assertEquals("the release is confirmed on the current head", "receiver", server.confirmations.single().details.toUserId)
    }

    @Test
    fun aFailedSendIsRetriedLater() {
        sendResult = Result.failure(IOException("No signal"))

        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun aRenewalThatCannotReachTheServerIsRetriedLater() {
        pendingReleases.put("SHIP-1", "receiver")
        server.offlineError = IOException("No signal")

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals("the waiting handover is kept", "receiver", pendingReleases.get("SHIP-1"))
    }

    @Test
    fun aRequestWaitsForAConnectionAndOnlyOneIsBooked() {
        SyncScheduler.request(context)
        SyncScheduler.request(context)

        val booked = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, booked.size)
        assertEquals(NetworkType.CONNECTED, booked.single().constraints.requiredNetworkType)
        assertTrue(sends == 0)
    }
}
