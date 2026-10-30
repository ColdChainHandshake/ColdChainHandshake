package com.coldchain.handshake.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.remote.SupabaseRemoteDataSource
import com.coldchain.handshake.data.remote.dto.RemoteTemperatureEventDto
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.AlertRepositoryImpl
import com.coldchain.handshake.repository.HandoverRepositoryImpl
import com.coldchain.handshake.repository.RepositoryProvider
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepositoryImpl
import com.coldchain.handshake.repository.TelemetryRepositoryImpl
import com.coldchain.handshake.ui.screens.HandoverScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Runs the real screen on a real database, with only the server replaced. The tall screen fits all of
 * it, which scrolls in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h6000dp")
class HandoverScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val now = System.currentTimeMillis()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = DatabaseProvider.createInMemoryDatabase(context)
    private val shipments = ShipmentRepositoryImpl(db.shipmentDao())
    private val telemetry = TelemetryRepositoryImpl(db.temperatureEventDao())
    private val handovers = HandoverRepositoryImpl(db.handoverDao())
    private val alerts = AlertRepositoryImpl(db.alertDao())

    private var showing by mutableStateOf(true)

    private enum class Server { HAS_NOTHING_MORE, UNREACHABLE, STILL_SENDING }

    private class FakeServer(private val behaviour: Server) : SupabaseRemoteDataSource() {
        override suspend fun getTemperatureEvents(shipmentId: String): Result<List<RemoteTemperatureEventDto>> =
            when (behaviour) {
                Server.HAS_NOTHING_MORE -> Result.success(emptyList())
                Server.UNREACHABLE -> Result.failure(IOException("No connection"))
                Server.STILL_SENDING -> awaitCancellation()
            }
    }

    private val shipment = Shipment(
        "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.ARRIVED, ProductProfiles.DEFAULT.id, now - 11 * minute
    )

    private fun readings(lastAt: Long = now - minute, count: Int = 10) = HashChainService.buildChain(
        (count - 1 downTo 0).map { back ->
            TemperatureEvent(
                "TE-$back", "SHIP-1", "LOG-1", lastAt - back * minute, 5.0, "", "", SyncStatus.PENDING
            )
        }
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        RepositoryProvider.initialize(shipments, telemetry, alerts, handovers)
    }

    @After
    fun tearDown() {
        // The screen goes first, or what it's still collecting would outlive the database.
        showing = false
        compose.waitForIdle()
        RepositoryProvider.reset()
        db.close()
    }

    private fun show(server: Server = Server.HAS_NOTHING_MORE, events: List<TemperatureEvent> = readings()) {
        runBlocking {
            shipments.adoptRemoteShipment(shipment).getOrThrow()
            telemetry.saveTemperatures(events).getOrThrow()
        }
        RepositoryProvider.syncCoordinator = ShipmentHistorySyncCoordinator(telemetry, FakeServer(server))
        RepositoryProvider.temperatureSimulator.attachShipmentForMonitoring(shipment)
        compose.setContent { if (showing) HandoverScreen() }
    }

    private fun await(text: String): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithText(text)
    }

    private fun choose(label: String) {
        compose.onNode(hasText(label) and isSelectable()).performClick()
    }

    private fun storedHandover() = runBlocking { handovers.getHandover("SHIP-1").first() }

    private fun awaitStored() {
        compose.waitUntil(10_000) { storedHandover() != null }
    }

    private fun storedStatus() = runBlocking { shipments.getShipment("SHIP-1").first()?.status }

    @Test
    fun aCleanJourneyRecordsTheReceiversInspection() {
        show()

        choose("Stage 2 (usable)")
        choose("Passed")
        await("Record PASS handover").performClick()

        awaitStored()
        val stored = storedHandover()
        assertEquals(HandoverVerdict.PASS, stored?.verdict)
        assertEquals(VvmStage.STAGE_2, stored?.vvmStage)
        assertEquals(ShakeTestResult.PASSED, stored?.shakeTest)
        assertEquals(ShipmentStatus.ACCEPTED, storedStatus())
    }

    @Test
    fun theReceiversOwnVerdictAndReasonAreRecorded() {
        show()

        await("Record PASS handover")
        choose("Fail: quarantine the shipment")
        compose.onNodeWithText("Reason").performTextInput("Seal broken")
        compose.onNodeWithText("Record FAIL handover").performClick()

        awaitStored()
        assertEquals(HandoverVerdict.FAIL, storedHandover()?.verdict)
        assertEquals("Seal broken", storedHandover()?.reason)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun whenTheHistoryCannotBeFetchedTheReceiverCanStillRecordWhatTheyFound() {
        show(Server.UNREACHABLE)

        await("Try Again")
        await("Record UNKNOWN handover").assertIsEnabled().performClick()

        awaitStored()
        assertEquals(HandoverVerdict.UNKNOWN, storedHandover()?.verdict)
        assertEquals(ShipmentStatus.QUARANTINED, storedStatus())
    }

    @Test
    fun whenThereAreNoReadingsTheReceiverCanStillRecordWhatTheyFound() {
        show(events = emptyList())

        await("Record UNKNOWN handover").assertIsEnabled()
    }

    @Test
    fun recordingWaitsWhileTheHistoryIsStillDownloading() {
        show(Server.STILL_SENDING)

        await("Waiting for Complete History...").assertIsNotEnabled()
    }

    @Test
    fun aLoggerThatStoppedLongBeforeHandoverIsNotOfferedAsAPass() {
        show(events = readings(lastAt = now - 30 * minute))

        await("Record UNKNOWN handover")

        assertTrue(compose.onAllNodes(hasText("Record PASS handover")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aRecordedHandoverIsShownAndCannotBeRecordedAgain() {
        show()
        await("Record PASS handover")
        runBlocking {
            RepositoryProvider.handoverOrchestrator.processHandover(
                shipmentId = "SHIP-1",
                workerSigned = true,
                pharmacistSigned = true,
                declaredVerdict = HandoverVerdict.UNKNOWN,
                reason = "Logger damaged"
            ).getOrThrow()
        }

        await("Recorded: UNKNOWN. Reason: Logger damaged")
        await("Handover Recorded").assertIsNotEnabled()
    }

    @Test
    fun recordingAHandoverStopsTheShipmentsLogger() {
        show()

        await("Record PASS handover").performClick()

        awaitStored()
        compose.waitUntil(10_000) { RepositoryProvider.temperatureSimulator.isMonitorOnly.value }
        assertEquals(ShipmentStatus.ACCEPTED, RepositoryProvider.temperatureSimulator.activeShipment.value?.status)
    }
}
