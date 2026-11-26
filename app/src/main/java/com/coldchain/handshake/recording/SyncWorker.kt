package com.coldchain.handshake.recording

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.repository.SyncService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sends what this phone recorded and renews any handover waiting for the other person, once there is a connection.
 * It covers the times nothing else sends: a send that failed while recording, a confirmation queued with no signal,
 * and anything left after the process died. A failure is retried with a growing delay.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncService,
    private val handover: ServerHandover
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sent = sync.syncPendingData()
        val renewed = handover.renewReleases()
        return if (sent.isSuccess && renewed.isSuccess) Result.success() else Result.retry()
    }
}

object SyncScheduler {

    const val WORK_NAME = "sync"
    const val BACKOFF_SECONDS = 30L

    /**
     * Books one sync for when there is a connection. It replaces one already booked, which may be waiting out a long
     * retry delay, so the new one runs as soon as the connection is there. Replacing one that is running is safe:
     * the uploader starts again from the server's head.
     */
    fun request(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        )
    }
}
