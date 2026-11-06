package com.coldchain.handshake.alerts

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.repository.impl.InMemoryTelemetryRepository
import com.coldchain.handshake.safety.AlertEscalationEngine
import com.coldchain.handshake.safety.FakeAlertRepository
import com.coldchain.handshake.safety.FakeShipmentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alerts = FakeAlertRepository()
    private val shipments = FakeShipmentRepository()
    private val telemetry = InMemoryTelemetryRepository()

    private fun alert(
        level: EscalationLevel = EscalationLevel.WORKER,
        createdAt: Long = System.currentTimeMillis()
    ) = Alert(
        id = "SHIP-1:TEMPERATURE_BREACH:1000",
        shipmentId = "SHIP-1",
        type = AlertType.TEMPERATURE_BREACH,
        message = "Thermal breach detected: reached 11.0°C (Elapsed: 08:00).",
        timestamp = createdAt,
        escalationLevel = level,
        acknowledged = false
    )

    private fun grantNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        RepositoryProvider.initialize(shipments, telemetry, alerts)
    }

    @After
    fun tearDown() {
        RepositoryProvider.reset()
    }

    @Test
    fun aRaisedAlertPostsANotificationOnTheAlertChannel() {
        grantNotifications()

        AndroidAlertNotifier(context).onAlertRaised(alert())

        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(AndroidAlertNotifier.CHANNEL_ID, notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(notification.channelId).importance)
        assertTrue(shadowOf(notification).contentText.contains("11.0°C"))
    }

    @Test
    fun anEscalationUpdatesTheSameNotification() {
        grantNotifications()
        val notifier = AndroidAlertNotifier(context)

        notifier.onAlertRaised(alert())
        notifier.onAlertEscalated(alert(level = EscalationLevel.SUPERVISOR))

        val notification = shadowOf(manager).allNotifications.single()
        assertTrue(shadowOf(notification).contentTitle.contains("Supervisor"))
    }

    @Test
    fun withoutPermissionNothingIsPostedAndNothingCrashes() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        AndroidAlertNotifier(context).onAlertRaised(alert())

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun aRaisedAlertSchedulesACheck() {
        grantNotifications()

        AndroidAlertNotifier(context).onAlertRaised(alert())

        // Test WorkManager runs undelayed work at once, so the check may already have finished.
        val checks = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AlertEscalationScheduler.WORK_NAME)
        assertEquals(1, checks.get().size)
    }

    @Test
    fun theWorkerEscalatesWhatIsDueNotifiesAndBooksTheNextCheck() = runBlocking {
        grantNotifications()
        val interval = AlertEscalationEngine.ESCALATION_INTERVAL_MS
        alerts.saveAlert(alert(createdAt = System.currentTimeMillis() - interval - 1_000L))

        val result = TestListenableWorkerBuilder<AlertEscalationWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(EscalationLevel.SUPERVISOR, alerts.savedAlerts.single().escalationLevel)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(1, pendingChecks())
    }

    @Test
    fun theWorkerBooksNothingOnceNothingCanEscalate() = runBlocking {
        alerts.saveAlert(alert(level = EscalationLevel.PHARMACIST))

        val result = TestListenableWorkerBuilder<AlertEscalationWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, pendingChecks())
    }

    @Test
    fun theWorkerRetriesIfStorageFails() = runBlocking {
        alerts.saveAlert(alert(createdAt = System.currentTimeMillis() - AlertEscalationEngine.ESCALATION_INTERVAL_MS))
        alerts.shouldFail = true

        val result = TestListenableWorkerBuilder<AlertEscalationWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun aCheckBooksTheNextOneForWhenTheAlertFallsDue() = runBlocking {
        alerts.saveAlert(alert())

        AlertEscalationScheduler.checkNow(context)

        val delay = awaitBookedDelay { it > 0 }
        assertTrue("booked $delay", delay in 14 * 60_000L..15 * 60_000L)
    }

    @Test
    fun checkingNowReplacesACheckBookedForLater() = runBlocking {
        alerts.saveAlert(alert())
        AlertEscalationScheduler.checkAt(context, System.currentTimeMillis() + 3_600_000L)

        AlertEscalationScheduler.checkNow(context)

        val delay = awaitBookedDelay { it in 1..15 * 60_000L }
        assertTrue("booked $delay", delay <= 15 * 60_000L)
    }

    @Test
    fun theManifestDeclaresTheNotificationPermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

        assertTrue(Manifest.permission.POST_NOTIFICATIONS in info.requestedPermissions.orEmpty())
    }

    @Test
    fun beforeAndroid13ANotificationNeedsNoRuntimePermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val actualSdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 32)
        try {
            AndroidAlertNotifier(context).onAlertRaised(alert())
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", actualSdk)
        }

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun theWorkerRaisesAnAlertForAQuietLoggerAndKeepsWatching() = runBlocking {
        val thirtyMinutesAgo = System.currentTimeMillis() - 30 * 60_000L
        shipments.saveShipment(
            Shipment(
                "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT,
                ProductProfiles.DEFAULT.id, thirtyMinutesAgo
            )
        )

        val result = TestListenableWorkerBuilder<AlertEscalationWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(AlertType.DATA_GAP, alerts.savedAlerts.single().type)
        assertEquals(1, pendingChecks())
    }

    @Test
    fun theNextCheckIsWhicheverComesFirst() = runBlocking {
        val now = System.currentTimeMillis()
        shipments.saveShipment(
            Shipment("SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id, now)
        )
        telemetry.saveTemperatures(
            listOf(TemperatureEvent("TE-1", "SHIP-1", "LOG-1", now, 5.0, "", "", SyncStatus.PENDING, 1L))
        ).getOrThrow()
        alerts.saveAlert(alert(createdAt = now))

        TestListenableWorkerBuilder<AlertEscalationWorker>(context).build().doWork()

        // The logger would go quiet in about three minutes, long before the alert's escalation is due.
        val delay = awaitBookedDelay { it > 0 }
        assertTrue("booked $delay", delay in 1..ProductProfiles.DEFAULT.maxSilenceMs + 1_000L)
    }

    @Test
    fun aCheckBookedWhileACheckIsRunningIsKept() = runBlocking {
        HoldingWorker.reset()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AlertEscalationScheduler.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HoldingWorker>().build()
        )
        withTimeout(5_000) { HoldingWorker.started.await() }
        val running = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AlertEscalationScheduler.WORK_NAME).get().map { it.state }
        assertEquals(listOf(WorkInfo.State.RUNNING), running)

        // As the escalation worker does: book the next check before its own run has finished.
        AlertEscalationScheduler.checkAt(context, System.currentTimeMillis() + 15 * 60_000L)
        HoldingWorker.release.complete(Unit)

        val delay = awaitBookedDelay { it > 0 }
        assertTrue("booked $delay", delay in 14 * 60_000L..15 * 60_000L)
    }

    /** Stays running until released, standing in for a check that books the next one mid-run. */
    class HoldingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            started.complete(Unit)
            release.await()
            return Result.success()
        }

        companion object {
            var started = CompletableDeferred<Unit>()
            var release = CompletableDeferred<Unit>()

            fun reset() {
                started = CompletableDeferred()
                release = CompletableDeferred()
            }
        }
    }

    /** The test executor runs undelayed work on another thread, so the booking arrives shortly after. */
    private fun awaitBookedDelay(accept: (Long) -> Boolean): Long {
        var delay = -1L
        repeat(200) {
            delay = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(AlertEscalationScheduler.WORK_NAME)
                .get()
                .firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                ?.initialDelayMillis ?: -1L
            if (accept(delay)) return delay
            Thread.sleep(20)
        }
        return delay
    }

    private fun pendingChecks(): Int =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AlertEscalationScheduler.WORK_NAME)
            .get()
            .count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
}
