package com.coldchain.handshake.recording

import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.simulator.TemperatureSimulator

/** Keeps recording going once the screen that started it is gone: in the app, the foreground [RecordingService]. */
fun interface RecordingBackground {
    fun keepRunning()
}

/**
 * Starts and pauses this phone's recording of a shipment it holds. Recording runs in the app's process and the
 * [RecordingBackground] keeps that process working with the screen off; [RecordingLoop] sends what is recorded.
 *
 * The readings come from the simulator, the app's only data source until the logger is connected (Phase 4).
 */
class CourierRecorder(
    private val simulator: TemperatureSimulator,
    private val store: RecordingStore,
    private val background: RecordingBackground
) {
    /** Records [shipment] from now until custody moves, the person pauses it, or they sign out. */
    fun start(shipment: Shipment) {
        startHere(shipment)
        background.keepRunning()
    }

    /** Starts recording without asking for the background host: for that host itself, after a restart. */
    fun startHere(shipment: Shipment) {
        simulator.attachShipment(shipment)
        store.shipmentId = shipment.id
        simulator.startContinuousSimulation(intervalMs = TICK_MS, simulatedStepSeconds = 60L)
    }

    fun pause() {
        store.shipmentId = null
        simulator.stopContinuousSimulation()
    }

    /**
     * After the process died while recording, opening the app brings the shipment back; recording resumes only if
     * it was this shipment that was recording, and it is still on the move.
     */
    fun resumeIfItWasRecording(shipment: Shipment): Boolean {
        if (store.shipmentId != shipment.id) return false
        if (shipment.status !in ON_THE_MOVE) {
            store.shipmentId = null
            return false
        }
        start(shipment)
        return true
    }

    /**
     * When the app opens with a shipment remembered as recording but nothing recording (a reinstall, or a restart
     * with no saved screen state), hands it to the background recording, whose restore checks it is still on the
     * move and still this phone's. Called from the open app, so the service may start in the foreground.
     */
    fun resumeInBackgroundIfRemembered() {
        if (store.shipmentId != null && !simulator.isRunning.value) background.keepRunning()
    }

    /**
     * Asks the background host again while recording, so a recording already running picks up a permission allowed
     * since it started (location, from the Transit screen). Called from the open app.
     */
    fun refreshBackground() {
        if (store.shipmentId != null && simulator.isRunning.value) background.keepRunning()
    }

    /**
     * The app has opened. A remembered recording that isn't running is handed to the background; one already running
     * is asked again, because after a restart in the background it runs without location, which Android lets start
     * only while the app is open.
     */
    fun onAppOpened() {
        if (simulator.isRunning.value) refreshBackground() else resumeInBackgroundIfRemembered()
    }

    companion object {
        /** How often the simulator looks at the clock. It records at most one reading a minute. */
        const val TICK_MS = 1_500L

        /** A shipment quarantined on the road is still on the move, and its readings still matter. */
        val ON_THE_MOVE = setOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.QUARANTINED)
    }
}
