package com.coldchain.handshake.recording

import android.Manifest
import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.coldchain.handshake.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts [RecordingLoop] while this phone records a shipment, so recording and sending go on with the screen off and
 * the app in the background. A foreground service of type `connectedDevice`, because its job is the connection to
 * the shipment's logger; on Android 14 and later that type needs the Nearby devices permission (`BLUETOOTH_CONNECT`).
 * Without it the service still runs, but only while the app is open, and the Transit screen says so.
 *
 * With location allowed it runs with the `location` type as well and records where the shipment is
 * ([LocationRecorder], queued in Room and sent like the readings). If Android refuses that type, recording carries on
 * without location.
 *
 * While in the foreground it holds a partial wake lock, renewed every round with a timeout, because the simulator
 * makes each reading itself and a sleeping CPU would leave a gap. A real logger records on its own and the phone
 * back-fills, so that path won't need it.
 *
 * Restarted by the system after its process was killed (`START_STICKY`), it picks recording up again only for a
 * shipment this phone still holds. If Android refuses the restart in the foreground, nothing is recorded until
 * the app is opened again, and the gap shows in the readings.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var loop: RecordingLoop
    @Inject lateinit var locationSource: LocationSource
    @Inject lateinit var locations: LocationRecorder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false
    private var locating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Entering the foreground, or adding location to a recording already in it once location has been allowed.
        val addLocation = inForeground && !locating && foregroundTypes() and LOCATION_TYPE != 0
        if ((!inForeground || addLocation) && canRunInForeground(this)) {
            val types = enterForeground()
            // Started for the foreground but refused: stop now, or Android ends the app for not calling startForeground.
            if (types == null && !inForeground) return stopNow()
            inForeground = true
            if (types != null && types and LOCATION_TYPE != 0 && !locating) {
                locating = true
                locationSource.start { fix -> scope.launch { locations.record(fix) } }
                locations.setActive(true)
            }
        }
        // The system restarting the service (no intent) can't keep it alive outside the foreground, so record nothing.
        if (intent == null && !inForeground) return stopNow()
        if (job?.isActive != true) {
            job = scope.launch {
                if (loop.isRecording() || loop.restore()) loop.run(onTick = ::holdWakeLock)
                finish()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocating()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Waits for the recording job to end. Cancelling doesn't wait for a job already finishing, so a test that destroys
     * the service waits here before its environment goes (CI, 28 Nov: `finish()` ran after a test had ended).
     */
    @VisibleForTesting
    internal suspend fun awaitStopped() {
        job?.join()
    }

    private fun stopNow(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private fun stopLocating() {
        locationSource.stop()
        locating = false
        locations.setActive(false)
    }

    private fun finish() {
        stopLocating()
        releaseWakeLock()
        if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    /**
     * Enters the foreground with the location type as well when location is allowed, and without it if Android refuses
     * that: location is a while-in-use permission, so Android 14 and later refuse a `location` service created while
     * the app is in the background (a restart after the process was killed), even with the permission granted.
     *
     * @return the types the service runs with, or null if Android refused the foreground altogether.
     */
    private fun enterForeground(): Int? {
        val wanted = foregroundTypes()
        for (types in listOf(wanted, wanted and LOCATION_TYPE.inv()).distinct()) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(locating = types and LOCATION_TYPE != 0), types)
                return types
            } catch (e: Exception) {
                // A restart from the background (ForegroundServiceStartNotAllowedException) or a missing prerequisite
                // (SecurityException). Without the foreground at all, recording lasts only while the app is open.
                Log.w(TAG, "Could not record in the foreground with types $types: ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val located = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (located) LOCATION_TYPE else 0)
    }

    private fun notification(locating: Boolean) = NotificationCompat.Builder(this, ensureChannel())
        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
        .setContentTitle("Recording this shipment")
        .setContentText(
            if (locating) "Readings are simulated on this phone. They and the phone's location are sent when there is a connection."
            else "Readings are simulated on this phone and sent when there is a connection."
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun ensureChannel(): String {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        return CHANNEL_ID
    }

    private fun holdWakeLock() {
        if (!inForeground) return
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 2
        private const val WAKE_LOCK_TAG = "cch:recording"
        /**
         * A compile-time constant, inlined, so naming it is safe below Android 10; [foregroundTypes] returns no types
         * there before it is ever used.
         */
        @SuppressLint("InlinedApi")
        private const val LOCATION_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        /** Renewed every round of the loop; lapses on its own if the loop ever stalls. */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

        /** Android 14 and later run a `connectedDevice` service in the foreground only with Nearby devices allowed. */
        fun canRunInForeground(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** Starts the service from a screen, in the foreground when allowed. */
        fun keepRunning(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            if (canRunInForeground(context)) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }
    }
}
