package com.coldchain.handshake.ui.navigation

import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.recording.CourierRecorder
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject

/**
 * Puts a shipment back on the simulator after the process was killed, from its saved ID. It comes back as the
 * custodian's (able to record) or as a watcher's: for a shipment on the server the server's custody decides; for a
 * local one, whether it was being watched. Recording resumes only if it was this shipment that was recording when
 * the process died ([CourierRecorder.resumeIfItWasRecording]).
 *
 * Nothing is attached if a shipment already is, or if this phone no longer holds the shipment (sign-out clears it).
 * Both the root of the app and the Transit screen call this; the check is repeated after the last suspension, so
 * the first one to finish wins and the other changes nothing.
 */
class ActiveShipmentRestorer @Inject constructor(
    private val simulator: TemperatureSimulator,
    private val shipments: ShipmentRepository,
    private val remote: ColdChainRemote,
    private val account: AccountRepository,
    private val recorder: CourierRecorder
) {
    suspend fun restore(shipmentId: String, watching: Boolean) {
        if (simulator.activeShipment.value != null) return
        val shipment = shipments.getShipment(shipmentId).firstOrNull() ?: return
        val user = (account.state.value as? AccountState.Ready)?.userId
        val mine = if (shipment.genesisHash != null && user != null) {
            remote.getCustody(shipmentId).getOrNull()?.custodianId == user
        } else {
            !watching
        }
        if (simulator.activeShipment.value != null) return
        if (!mine) simulator.attachShipmentForMonitoring(shipment)
        else if (!recorder.resumeIfItWasRecording(shipment)) simulator.attachShipment(shipment)
    }
}
