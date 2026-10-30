package com.coldchain.handshake.safety

import com.coldchain.handshake.models.Alert
import com.coldchain.handshake.models.AlertType
import com.coldchain.handshake.models.EscalationLevel
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.TemperatureEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertLifecycleTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val shipment = Shipment("SHIP-1", "QR", "LOG-1", "A", "B", "W-1", ShipmentStatus.IN_TRANSIT)

    private val alerts = FakeAlertRepository()
    private val notifier = RecordingNotifier()
    private val engine = SafetyEngine(alerts, FakeShipmentRepository(), notifier)

    private class RecordingNotifier : AlertNotifier {
        val raised = mutableListOf<Alert>()
        val escalated = mutableListOf<Alert>()
        override fun onAlertRaised(alert: Alert) { raised.add(alert) }
        override fun onAlertEscalated(alert: Alert) { escalated.add(alert) }
    }

    /** Feeds the history to the engine one reading at a time, as live telemetry does. */
    private suspend fun replay(history: List<TemperatureEvent>) {
        history.indices.forEach { index ->
            engine.processTelemetryAndEnforceSafety(shipment, history.take(index + 1), t0 + index * minute)
        }
    }

    private fun alert(
        level: EscalationLevel = EscalationLevel.WORKER,
        createdAt: Long = t0,
        acknowledged: Boolean = false
    ) = Alert("A", "SHIP-1", AlertType.TEMPERATURE_BREACH, "m", createdAt, level, acknowledged)

    // One alert per excursion

    @Test
    fun oneExcursionRaisesOneAlertHoweverManyTicksFollow() = runBlocking {
        replay(hotThenSafe(t0, 8 * minute, 10.0) + steadyReadings(t0 + 9 * minute, 20 * minute, 5.0))

        assertEquals(1, alerts.savedAlerts.size)
        assertEquals(1, notifier.raised.size)
    }

    @Test
    fun theAlertIdIsDeterministic() = runBlocking {
        replay(hotThenSafe(t0, 8 * minute, 10.0))

        assertEquals("SHIP-1:TEMPERATURE_BREACH:$t0", alerts.savedAlerts.single().id)
    }

    @Test
    fun aLaterExcursionRaisesItsOwnAlert() = runBlocking {
        val second = t0 + 20 * minute
        replay(
            hotThenSafe(t0, 8 * minute, 10.0) +
                steadyReadings(t0 + 9 * minute, 10 * minute, 5.0) +
                hotThenSafe(second, 2 * minute, 10.0)
        )

        assertEquals(
            listOf("SHIP-1:TEMPERATURE_BREACH:$t0", "SHIP-1:TEMPERATURE_BREACH:$second"),
            alerts.savedAlerts.map { it.id }
        )
    }

    @Test
    fun anExcursionWithinTheBudgetRaisesNoAlert() = runBlocking {
        replay(hotThenSafe(t0, 3 * minute, 10.0))

        assertTrue(alerts.savedAlerts.isEmpty())
    }

    @Test
    fun freezingRaisesAFreezeAlertOnTheFirstFrozenReading() = runBlocking {
        replay(steadyReadings(t0, minute, 5.0) + steadyReadings(t0 + 2 * minute, 0, -3.0))

        val alert = alerts.savedAlerts.single()
        assertEquals(AlertType.FREEZE_BREACH, alert.type)
        assertEquals("SHIP-1:FREEZE_BREACH:${t0 + 2 * minute}", alert.id)
        assertEquals(t0 + 2 * minute, alert.excursionStart)
    }

    @Test
    fun aDataGapRaisesOneAlertThatDoesNotQuarantine() = runBlocking {
        val decision = engine.processTelemetryAndEnforceSafety(
            shipment,
            steadyReadings(t0, 0, 5.0) + steadyReadings(t0 + 60 * minute, 0, 5.0)
        )

        val alert = alerts.savedAlerts.single()
        assertEquals(AlertType.DATA_GAP, alert.type)
        assertEquals("SHIP-1:DATA_GAP:$t0", alert.id)
        assertFalse(decision.shouldQuarantine)
    }

    @Test
    fun laterTicksLeaveAnAcknowledgedAndEscalatedAlertAlone() = runBlocking {
        val history = hotThenSafe(t0, 8 * minute, 10.0)
        replay(history)
        val escalated = engine.escalateAlert(alerts.savedAlerts.single())
        val acknowledged = engine.acknowledgeAlert(escalated)

        replay(history + steadyReadings(t0 + 9 * minute, 10 * minute, 5.0))

        val stored = alerts.savedAlerts.single()
        assertTrue(stored.acknowledged)
        assertEquals(EscalationLevel.SUPERVISOR, stored.escalationLevel)
        assertEquals(acknowledged, stored)
        assertEquals(1, notifier.raised.size)
    }

    @Test
    fun aDisconnectAlertIdIsDeterministic() = runBlocking {
        val alert = engine.handleLoggerDisconnect("SHIP-1", "LOG-1", timestamp = t0)

        assertEquals("SHIP-1:LOGGER_DISCONNECT:$t0", alert.id)
    }

    // Escalation timing

    @Test
    fun anUnacknowledgedAlertEscalatesOnTheTimer() {
        val interval = AlertEscalationEngine.ESCALATION_INTERVAL_MS
        val level = { elapsed: Long ->
            AlertEscalationEngine.dueEscalations(listOf(alert()), t0 + elapsed).singleOrNull()?.escalationLevel
        }

        assertNull(level(interval - 1))
        assertEquals(EscalationLevel.SUPERVISOR, level(interval))
        assertEquals(EscalationLevel.SUPERVISOR, level(2 * interval - 1))
        assertEquals(EscalationLevel.PHARMACIST, level(2 * interval))
    }

    @Test
    fun aLateCheckGoesStraightToTheLevelThatIsDue() {
        val due = AlertEscalationEngine.dueEscalations(
            listOf(alert()),
            t0 + 10 * AlertEscalationEngine.ESCALATION_INTERVAL_MS
        )

        assertEquals(EscalationLevel.PHARMACIST, due.single().escalationLevel)
    }

    @Test
    fun anAcknowledgedAlertNeverEscalates() {
        val due = AlertEscalationEngine.dueEscalations(
            listOf(alert(acknowledged = true)),
            t0 + 10 * AlertEscalationEngine.ESCALATION_INTERVAL_MS
        )

        assertTrue(due.isEmpty())
    }

    @Test
    fun theTimerNeverLowersALevelSetByHand() {
        val interval = AlertEscalationEngine.ESCALATION_INTERVAL_MS
        val manual = alert(level = EscalationLevel.PHARMACIST)

        assertTrue(AlertEscalationEngine.dueEscalations(listOf(manual), t0 + interval).isEmpty())
    }

    @Test
    fun theNextCheckIsTheSoonestPendingEscalation() {
        val interval = AlertEscalationEngine.ESCALATION_INTERVAL_MS
        val older = alert(createdAt = t0)
        val newer = alert(createdAt = t0 + 10 * minute)

        assertEquals(t0 + interval, AlertEscalationEngine.nextEscalationAt(listOf(newer, older)))
    }

    @Test
    fun thereIsNoNextCheckOnceNothingCanEscalate() {
        val finished = alert(level = EscalationLevel.PHARMACIST)
        val acknowledged = alert(acknowledged = true)

        assertNull(AlertEscalationEngine.nextEscalationAt(listOf(finished, acknowledged)))
    }

    @Test
    fun theRunnerStoresEscalationsNotifiesAndReportsTheNextCheck() = runBlocking {
        val interval = AlertEscalationEngine.ESCALATION_INTERVAL_MS
        alerts.saveAlert(alert())
        val runner = AlertEscalationRunner(alerts, notifier) { t0 + interval }

        val nextCheck = runner.run()

        assertEquals(EscalationLevel.SUPERVISOR, alerts.savedAlerts.single().escalationLevel)
        assertEquals(EscalationLevel.SUPERVISOR, notifier.escalated.single().escalationLevel)
        assertEquals(t0 + 2 * interval, nextCheck)
    }

    @Test
    fun theRunnerDoesNotOverwriteAnAcknowledgementMadeWhileItRan() = runBlocking {
        val interval = AlertEscalationEngine.ESCALATION_INTERVAL_MS
        alerts.saveAlert(alert())
        alerts.onOpenAlertsRead = { runBlocking { alerts.saveAlert(alerts.savedAlerts.single().copy(acknowledged = true)) } }
        val runner = AlertEscalationRunner(alerts, notifier) { t0 + interval }

        runner.run()

        assertTrue(alerts.savedAlerts.single().acknowledged)
        assertEquals(EscalationLevel.WORKER, alerts.savedAlerts.single().escalationLevel)
        assertTrue(notifier.escalated.isEmpty())
    }
}
