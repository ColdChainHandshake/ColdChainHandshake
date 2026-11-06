package com.coldchain.handshake.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.data.local.DatabaseProvider
import com.coldchain.handshake.data.local.RoomTransactionRunner
import com.coldchain.handshake.handover.HandoverOrchestrator
import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.SyncStatus
import com.coldchain.handshake.models.TemperatureEvent
import com.coldchain.handshake.models.VvmStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val telemetry = TelemetryRepositoryImpl(database.temperatureEventDao())

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

        shipments.adoptRemoteShipment(shipment).getOrThrow()

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

    @Test
    fun anUnreadableTemperatureIsRefusedNotDropped() = runBlocking {
        val event = TemperatureEvent("TE-1", "SHIP-1", "LOG-1", 1_000L, Double.NaN, "", "", SyncStatus.PENDING, 1L)

        assertTrue(telemetry.saveTemperature(event).isFailure)
        assertTrue(telemetry.saveTemperatures(listOf(event)).isFailure)
    }

    private suspend fun readyForHandover(): HandoverOrchestrator {
        shipments.adoptRemoteShipment(
            Shipment(
                "SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.ARRIVED,
                ProductProfiles.DEFAULT.id, 1_700_000_000_000L
            )
        ).getOrThrow()
        telemetry.saveTemperatures(
            HashChainService.buildChain(
                (1..3).map {
                    TemperatureEvent(
                        "TE-$it", "SHIP-1", "LOG-1", 1_700_000_000_000L + it * 60_000L, 5.0, "", "", SyncStatus.PENDING
                    )
                }
            )
        ).getOrThrow()
        return HandoverOrchestrator(shipments, telemetry, handovers, RoomTransactionRunner(database))
    }

    private suspend fun handOver(orchestrator: HandoverOrchestrator, verdict: HandoverVerdict, reason: String) =
        orchestrator.processHandover(
            shipmentId = "SHIP-1",
            workerSigned = true,
            pharmacistSigned = true,
            declaredVerdict = verdict,
            reason = reason,
            timestamp = 1_700_000_000_000L + 4 * 60_000L
        )

    @Test
    fun aSecondHandoverIsRefusedAndTheFirstIsKept() = runBlocking {
        val orchestrator = readyForHandover()
        handOver(orchestrator, HandoverVerdict.UNKNOWN, "Logger damaged").getOrThrow()

        assertTrue(handOver(orchestrator, HandoverVerdict.FAIL, "Seal broken").isFailure)

        assertEquals("Logger damaged", handovers.getHandover("SHIP-1").first()?.reason)
    }

    @Test
    fun aHandoverRowWithTheSameShipmentCannotReplaceTheFirst() = runBlocking {
        val orchestrator = readyForHandover()
        val first = handOver(orchestrator, HandoverVerdict.UNKNOWN, "Logger damaged").getOrThrow().handover

        val result = handovers.saveHandover(first.copy(id = "another", reason = "Seal broken"))

        assertTrue(result.isFailure)
        assertEquals("Logger damaged", handovers.getHandover("SHIP-1").first()?.reason)
    }

    @Test
    fun theAppsOwnOrchestratorRecordsAHandoverInOneTransaction() = runBlocking {
        readyForHandover()
        // Refused only at the last step, after the record and the inspection are written, so they're
        // what the transaction has to undo.
        val refusing = object : ShipmentRepository by shipments {
            override suspend fun saveShipment(shipment: Shipment) =
                if (shipment.status == ShipmentStatus.QUARANTINED) {
                    Result.failure(IllegalStateException("A breach arrived first."))
                } else {
                    shipments.saveShipment(shipment)
                }
        }
        val orchestrator = RepositoryProvider.handoverOrchestratorFor(database, refusing, telemetry, handovers)

        assertTrue(handOver(orchestrator, HandoverVerdict.UNKNOWN, "Logger damaged").isFailure)

        assertNull(handovers.getHandover("SHIP-1").first())
        assertEquals(ShipmentStatus.ARRIVED, shipments.getShipment("SHIP-1").first()?.status)
    }

    @Test
    fun aHandoverThatCannotFinishLeavesNoRecordBehind() = runBlocking {
        readyForHandover()
        // Refused only at the last step, after the record and the inspection are written, so they're
        // what the transaction has to undo.
        val refusing = object : ShipmentRepository by shipments {
            override suspend fun saveShipment(shipment: Shipment) =
                if (shipment.status == ShipmentStatus.QUARANTINED) {
                    Result.failure(IllegalStateException("A breach arrived first."))
                } else {
                    shipments.saveShipment(shipment)
                }
        }
        val orchestrator = HandoverOrchestrator(refusing, telemetry, handovers, RoomTransactionRunner(database))

        assertTrue(handOver(orchestrator, HandoverVerdict.UNKNOWN, "Logger damaged").isFailure)

        assertNull(handovers.getHandover("SHIP-1").first())
        assertEquals(ShipmentStatus.ARRIVED, shipments.getShipment("SHIP-1").first()?.status)
    }
}
