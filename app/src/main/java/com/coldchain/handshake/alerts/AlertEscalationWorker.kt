package com.coldchain.handshake.alerts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coldchain.handshake.recording.RecordingStore
import com.coldchain.handshake.repository.AlertRepository
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.safety.AlertEscalationRunner
import com.coldchain.handshake.safety.LoggerSilenceCheck
import com.coldchain.handshake.safety.SafetyEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Looks for loggers that have gone quiet, applies due escalations, then books itself for the next
 * time either could matter. All state is stored, so it recovers from process death and reboots.
 */
@HiltWorker
class AlertEscalationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alerts: AlertRepository,
    private val shipments: ShipmentRepository,
    private val telemetry: TelemetryRepository,
    private val safetyEngine: SafetyEngine,
    private val recording: RecordingStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runner = AlertEscalationRunner(
            alertRepository = alerts,
            alertNotifier = AndroidAlertNotifier(applicationContext)
        )
        val silenceCheck = LoggerSilenceCheck(
            shipmentRepository = shipments,
            telemetryRepository = telemetry,
            safetyEngine = safetyEngine,
            // This phone records the shipment it remembers recording, and any kept on this phone alone. The rest it
            // only watches, holding what it has downloaded so far.
            recordsHere = { shipment -> shipment.genesisHash == null || shipment.id == recording.shipmentId }
        )

        return runCatching { listOfNotNull(silenceCheck.run(), runner.run()).minOrNull() }.fold(
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
