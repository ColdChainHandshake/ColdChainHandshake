package com.coldchain.handshake.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.safety.AlertEscalationRunner
import java.util.concurrent.TimeUnit

/**
 * Applies due escalations, then books itself for the next one. All state is in the stored alerts,
 * so it recovers from process death and reboots.
 */
class AlertEscalationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runner = AlertEscalationRunner(
            alertRepository = RepositoryProvider.getAlertRepository(applicationContext),
            alertNotifier = AndroidAlertNotifier(applicationContext)
        )

        return runCatching { runner.run() }.fold(
            onSuccess = { nextCheck ->
                nextCheck?.let { AlertEscalationScheduler.checkAt(applicationContext, it) }
                Result.success()
            },
            onFailure = { Result.retry() }
        )
    }
}

object AlertEscalationScheduler {

    const val WORK_NAME = "alert-escalation"

    /**
     * Checks straight away. A new alert uses this instead of booking its own time, which could
     * replace a pending check for an older alert that is due sooner. The worker works out the
     * soonest time from everything stored.
     */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AlertEscalationWorker>().build()
        )
    }

    /**
     * Books the next check. Called from the worker itself, so it appends: replacing would cancel the
     * run that is booking it.
     */
    fun checkAt(context: Context, atMillis: Long) {
        val delayMs = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AlertEscalationWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
        )
    }
}
