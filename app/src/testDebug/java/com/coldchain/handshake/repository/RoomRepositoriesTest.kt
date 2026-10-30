package com.coldchain.handshake.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.VvmStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the repositories against real Room queries, because an in-memory fake can't show that
 * `INSERT OR IGNORE` and the conditional update behave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRepositoriesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = DatabaseProvider.createInMemoryDatabase(context)
    private val alerts = AlertRepositoryImpl(database.alertDao())
    private val shipments = ShipmentRepositoryImpl(database.shipmentDao())
    private val handovers = HandoverRepositoryImpl(database.handoverDao())

    private val alert = Alert(
        id = "SHIP-1:TEMPERATURE_BREACH:1000",
        shipmentId = "SHIP-1",
        type = AlertType.TEMPERATURE_BREACH,
        message = "Hot",
        timestamp = 1_000L,
        escalationLevel = EscalationLevel.WORKER,
        acknowledged = false,
        excursionStart = 1_000L
    )

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aNewAlertIsStoredOnceAndReportedAsNew() = runBlocking {
        assertTrue(alerts.saveAlertIfAbsent(alert).getOrThrow())
        assertFalse(alerts.saveAlertIfAbsent(alert).getOrThrow())

        assertEquals(1, alerts.getAllAlerts("SHIP-1").first().size)
    }

    @Test
    fun savingAnAlertAgainLeavesItsAcknowledgementAndLevelAlone() = runBlocking {
        val handled = alert.copy(
            escalationLevel = EscalationLevel.SUPERVISOR,
            acknowledged = true,
            acknowledgedAt = 2_000L
        )
        alerts.saveAlert(handled).getOrThrow()

        alerts.saveAlertIfAbsent(alert).getOrThrow()

        assertEquals(handled, alerts.getAllAlerts("SHIP-1").first().single())
    }

    @Test
    fun onlyAnUnacknowledgedAlertCanBeEscalated() = runBlocking {
        alerts.saveAlert(alert).getOrThrow()
        assertTrue(alerts.escalateIfUnacknowledged(alert.id, EscalationLevel.SUPERVISOR).getOrThrow())

        alerts.saveAlert(alert.copy(escalationLevel = EscalationLevel.SUPERVISOR, acknowledged = true)).getOrThrow()

        assertFalse(alerts.escalateIfUnacknowledged(alert.id, EscalationLevel.PHARMACIST).getOrThrow())
        assertEquals(EscalationLevel.SUPERVISOR, alerts.getAllAlerts("SHIP-1").first().single().escalationLevel)
    }

    @Test
    fun openAlertsSpanAllShipmentsAndSkipAcknowledgedOnes() = runBlocking {
        alerts.saveAlert(alert).getOrThrow()
        alerts.saveAlert(alert.copy(id = "other", shipmentId = "SHIP-2")).getOrThrow()
        alerts.saveAlert(alert.copy(id = "done", acknowledged = true)).getOrThrow()

        assertEquals(setOf(alert.id, "other"), alerts.getOpenAlerts().map { it.id }.toSet())
    }

    @Test
    fun aShipmentKeepsItsProfileAndJourneyStart() = runBlocking {
        val shipment = Shipment(
            "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT,
            ProductProfiles.FREEZE_TOLERANT.id, 1_000L
        )

        shipments.saveShipment(shipment).getOrThrow()

        assertEquals(shipment, shipments.getShipment("SHIP-1").first())
    }

    @Test
    fun aHandoverKeepsItsReasonAndFindings() = runBlocking {
        val handover = Handover(
            id = "H-1",
            shipmentId = "SHIP-1",
            workerSigned = true,
            pharmacistSigned = true,
            integrityVerified = true,
            temperaturePassed = false,
            verdict = HandoverVerdict.UNKNOWN,
            timestamp = 1_000L,
            reason = "Logger damaged",
            vvmStage = VvmStage.STAGE_2,
            shakeTest = ShakeTestResult.PASSED
        )

        handovers.saveHandover(handover).getOrThrow()

        assertEquals(handover, handovers.getHandover("SHIP-1").first())
    }
}
