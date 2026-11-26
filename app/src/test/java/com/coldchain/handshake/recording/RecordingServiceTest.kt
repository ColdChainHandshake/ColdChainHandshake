package com.coldchain.handshake.recording

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.di.ServerModule
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.FakeColdChainServer
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.simulator.TemperatureSimulator
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The recording service as Hilt builds it: in the foreground while a shipment records, with a notification that
 * says the readings are simulated; stopping itself when recording stops; and stopping at once when Android refuses
 * it the foreground or restarts it with nothing to record. A shipment kept on this phone alone, so nothing is sent.
 */
@HiltAndroidTest
@UninstallModules(ServerModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class RecordingServiceTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @BindValue @JvmField
    val account: AccountRepository = AccountRepository.NONE

    @BindValue @JvmField
    val remote: ColdChainRemote = FakeColdChainServer(currentUser = "user-1")

    @Inject lateinit var recorder: CourierRecorder
    @Inject lateinit var simulator: TemperatureSimulator
    @Inject lateinit var shipments: ShipmentRepository
    @Inject lateinit var store: RecordingStore

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val local = Shipment(
        "SHIP-L", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT, ProductProfiles.DEFAULT.id,
        journeyStartedAt = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        hilt.inject()
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        runBlocking { shipments.adoptRemoteShipment(local).getOrThrow() }
    }

    /** Every service a test started; each is destroyed afterwards, so its loop can't run into the next test. */
    private val controllers = mutableListOf<ServiceController<RecordingService>>()

    @After
    fun tearDown() {
        simulator.stopContinuousSimulation()
        controllers.forEach { it.destroy() }
    }

    private fun allowNearbyDevices() =
        shadowOf(context as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    private fun started(intent: Intent? = Intent(context, RecordingService::class.java)): ServiceController<RecordingService> {
        val controller = Robolectric.buildService(RecordingService::class.java, intent).create()
        controllers += controller
        controller.get().onStartCommand(intent, 0, 1)
        return controller
    }

    private fun eventually(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition()) delay(20) }
        assertTrue(what, condition())
    }

    @Test
    fun whileAShipmentRecordsItRunsInTheForegroundAndSaysTheReadingsAreSimulated() {
        allowNearbyDevices()
        recorder.startHere(local)

        val service = started().get()

        val shadow = shadowOf(service)
        assertTrue(shadow.lastForegroundNotificationId != 0)
        val text = shadowOf(shadow.lastForegroundNotification).contentText.toString()
        assertTrue(text, text.contains("simulated"))
        assertFalse(shadow.isStoppedBySelf)
    }

    @Test
    fun whenRecordingStopsTheServiceStopsItself() {
        allowNearbyDevices()
        recorder.startHere(local)
        val service = started().get()

        recorder.pause()

        eventually("the service stops itself") { shadowOf(service).isStoppedBySelf }
        assertNull(store.shipmentId)
    }

    @Test
    fun withoutNearbyDevicesItRecordsOnlyWhileTheAppIsOpen() {
        recorder.startHere(local)

        val service = started().get()

        assertEquals(0, shadowOf(service).lastForegroundNotificationId)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun whenAndroidRefusesTheForegroundItStopsAtOnce() {
        allowNearbyDevices()
        recorder.startHere(local)
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        controllers += controller
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("refused"))

        controller.get().onStartCommand(Intent(context, RecordingService::class.java), 0, 1)

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun aRestartWithNothingRecordingStopsAtOnce() {
        allowNearbyDevices()

        val service = started(intent = null).get()

        eventually("the service stops itself") { shadowOf(service).isStoppedBySelf }
        assertFalse(simulator.isRunning.value)
    }

    @Test
    fun aRestartPicksUpTheShipmentThatWasRecording() {
        allowNearbyDevices()
        store.shipmentId = local.id

        started(intent = null)

        eventually("recording resumes") { simulator.isRunning.value }
        assertEquals(local.id, simulator.activeShipment.value?.id)
    }
}
