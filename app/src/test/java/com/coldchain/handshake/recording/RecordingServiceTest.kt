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
import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE as CONNECTED_DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION as LOCATION
import com.coldchain.handshake.data.local.ColdChainDatabase
import com.coldchain.handshake.di.LocationModule
import com.coldchain.handshake.di.ServerModule
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
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
@UninstallModules(ServerModule::class, LocationModule::class)
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
    @Inject lateinit var database: ColdChainDatabase
    @Inject lateinit var locations: LocationRecorder

    private val fakeLocations = FakeLocationSource()

    @BindValue @JvmField
    val boundLocationSource: LocationSource = fakeLocations

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
        // Destroying cancels each service's job but doesn't wait for one already finishing; on CI one finished after
        // the test had ended and failed a later test (28 Nov).
        runBlocking { withTimeout(5_000) { controllers.forEach { it.get().awaitStopped() } } }
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

    private fun allowLocation() = shadowOf(context as Application)
        .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    // MASTER 5.5 item 2 (finding 9's last part): the service records where the shipment is while it records it, so
    // location goes on with the screen off like the readings. Android 14 and later need the service's `location` type
    // for that, and the type needs the location permission.
    @Test
    fun withLocationAllowedItAlsoRunsAsALocationServiceAndAsksForFixes() {
        allowNearbyDevices()
        allowLocation()
        recorder.startHere(local)

        val service = started().get()

        assertEquals(CONNECTED_DEVICE or LOCATION, service.foregroundServiceType)
        assertTrue(fakeLocations.running)
        assertTrue("the screens are told location is recorded", locations.active.value)
    }

    @Test
    fun withoutLocationItRecordsAsBeforeAndAsksForNoFixes() {
        allowNearbyDevices()
        recorder.startHere(local)

        val service = started().get()

        assertEquals(CONNECTED_DEVICE, service.foregroundServiceType)
        assertFalse(fakeLocations.running)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    // Location is a while-in-use permission: Android 14 and later refuse a `location` service created while the app is
    // in the background (a restart after the process was killed), even with the permission granted. Losing location
    // must never cost the readings.
    @Test
    @Config(shadows = [RefusesTheLocationType::class])
    fun whenAndroidRefusesTheLocationTypeItKeepsRecordingWithoutIt() {
        allowNearbyDevices()
        allowLocation()
        recorder.startHere(local)

        val service = started().get()

        assertEquals(CONNECTED_DEVICE, service.foregroundServiceType)
        assertTrue(shadowOf(service).lastForegroundNotificationId != 0)
        assertFalse(fakeLocations.running)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(simulator.isRunning.value)
        // Location is allowed, but not recorded: the screens must not say it is.
        assertFalse(locations.active.value)
    }

    // A courier who refused location at Take Custody and allows it later, from the Transit screen, gets it on the
    // recording already running, without starting again.
    @Test
    fun allowingLocationLaterAddsItToTheRecordingAlreadyRunning() {
        allowNearbyDevices()
        recorder.startHere(local)
        val controller = started()
        assertFalse(fakeLocations.running)

        allowLocation()
        controller.get().onStartCommand(Intent(context, RecordingService::class.java), 0, 2)

        assertEquals(CONNECTED_DEVICE or LOCATION, controller.get().foregroundServiceType)
        assertTrue(fakeLocations.running)
    }

    @Test
    fun whenRecordingStopsTheFixesStopToo() {
        allowNearbyDevices()
        allowLocation()
        recorder.startHere(local)
        val service = started().get()

        recorder.pause()

        eventually("the service stops itself") { shadowOf(service).isStoppedBySelf }
        assertFalse(fakeLocations.running)
        assertFalse(locations.active.value)
    }

    @Test
    fun aFixWhileRecordingAShipmentOnTheServerIsQueued() {
        allowNearbyDevices()
        allowLocation()
        val onServer = local.copy(id = "SHIP-S", genesisHash = "a".repeat(64))
        runBlocking { shipments.adoptRemoteShipment(onServer).getOrThrow() }
        recorder.startHere(onServer)
        started()

        fakeLocations.deliver(LocationFix(9.98, 76.28, 12f, System.currentTimeMillis()))

        eventually("the fix is queued") { runBlocking { database.gpsPointDao().getPending().size == 1 } }
        assertEquals("SHIP-S", runBlocking { database.gpsPointDao().getPending().single().shipmentId })
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

/** Stands in for the fused location provider: says whether fixes were asked for, and hands over the ones a test gives. */
class FakeLocationSource : LocationSource {
    @Volatile var running = false
        private set
    private var onFix: ((LocationFix) -> Unit)? = null

    override fun start(onFix: (LocationFix) -> Unit) {
        this.onFix = onFix
        running = true
    }

    override fun stop() {
        onFix = null
        running = false
    }

    fun deliver(fix: LocationFix) {
        onFix?.invoke(fix)
    }
}

/** Android 14 and later on a background start: a `location` service is refused, anything else is allowed. */
@Implements(Service::class)
class RefusesTheLocationType : ShadowService() {
    @Implementation
    override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
        if (foregroundServiceType and LOCATION != 0) throw SecurityException("Starting FGS with type location requires permissions")
        super.startForeground(id, notification, foregroundServiceType)
    }
}
