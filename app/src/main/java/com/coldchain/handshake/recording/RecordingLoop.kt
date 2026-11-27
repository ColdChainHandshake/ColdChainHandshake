package com.coldchain.handshake.recording

import com.coldchain.handshake.handover.ServerHandover
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.AccountRepository
import com.coldchain.handshake.repository.AccountState
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.SyncService
import com.coldchain.handshake.simulator.TemperatureSimulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** Books a background sync, which runs once there is a connection. */
fun interface SyncRequests {
    fun request()
}

/**
 * What keeps a courier's recording useful with no screen open, hosted by the foreground [RecordingService]:
 * it sends the readings, renews a handover that waits for the other person (the server needs the custodian's
 * confirmation on the current head, and the head moves with every reading), and stops recording once custody has
 * moved to someone else. A send that fails books a background sync, which runs when the connection returns.
 *
 * A shipment kept on this phone alone (debug local mode) has nothing to send; the loop only lasts as long as its
 * recording does.
 */
class RecordingLoop(
    private val simulator: TemperatureSimulator,
    private val recorder: CourierRecorder,
    private val sync: SyncService,
    private val handover: ServerHandover,
    private val remote: ColdChainRemote,
    private val account: AccountRepository,
    private val shipments: ShipmentRepository,
    private val store: RecordingStore,
    private val syncRequests: SyncRequests,
    private val loopMs: Long = LOOP_MS,
    private val accountWaitMs: Long = ACCOUNT_WAIT_MS,
    private val custodyEveryRounds: Long = CUSTODY_EVERY
) {
    /** This phone is recording a shipment right now. */
    fun isRecording(): Boolean =
        simulator.isRunning.value && simulator.activeShipment.value != null && !simulator.isMonitorOnly.value

    /**
     * After the process was restarted: picks recording up again for the shipment that was recording, if it is
     * still on the move and this phone still holds it. Otherwise forgets it. With no answer from the server about
     * custody, this phone, which took custody, carries on.
     */
    suspend fun restore(): Boolean {
        if (isRecording()) return true
        val id = store.shipmentId ?: return false
        val shipment = shipments.getShipment(id).firstOrNull()
        if (shipment == null || shipment.status !in CourierRecorder.ON_THE_MOVE) return forget()

        if (shipment.genesisHash != null) {
            val settled = withTimeoutOrNull(accountWaitMs) { account.state.first { it !is AccountState.Loading } }
                ?: return false
            val me = (settled as? AccountState.Ready)?.userId ?: return forget()
            val custody = remote.getCustody(id).getOrNull()
            if (custody != null && custody.custodianId != me) return forget()
        }

        recorder.startHere(shipment)
        return true
    }

    /** Runs until recording stops: paused, handed over, or cleared at sign-out. [onTick] runs once per round. */
    suspend fun run(onTick: () -> Unit = {}) {
        var round = 0L
        while (isRecording()) {
            val shipment = simulator.activeShipment.value ?: break
            onTick()
            // Custody can move away only once this phone has confirmed a handover, and while one waits each renewal
            // asks. Otherwise it is read once a minute, as a safety net (finding 11).
            if (shipment.genesisHash != null && sendAndCheck(shipment, checkCustody = round % custodyEveryRounds == 0L)) {
                simulator.onHandedOver(shipment)
                break
            }
            round++
            delay(loopMs)
        }
        store.shipmentId = null
    }

    /** A background sync is booked for the current run of failed sends. */
    private var syncBooked = false

    /** Sends and renews; true once custody has moved to someone else. */
    private suspend fun sendAndCheck(shipment: Shipment, checkCustody: Boolean): Boolean {
        val me = (account.state.value as? AccountState.Ready)?.userId ?: return false

        val sent = sync.syncPendingData()
        val waiting = handover.isWaiting(shipment.id)
        val renewed = handover.renewRelease(shipment.id)
        val failed = sent.isFailure || renewed.isFailure
        if (failed && !syncBooked) syncRequests.request()
        syncBooked = failed
        if (renewed.getOrNull()?.state == HandoverState.TRANSFERRED) return true
        // A handover that was waiting and is now gone: the renewal found custody with the person named.
        if (waiting && renewed.isSuccess && renewed.getOrNull() == null) return true

        if (!checkCustody) return false
        val custody = remote.getCustody(shipment.id).getOrNull() ?: return false
        return custody.custodianId != me
    }

    private fun forget(): Boolean {
        store.shipmentId = null
        return false
    }

    companion object {
        const val LOOP_MS = 5_000L
        const val ACCOUNT_WAIT_MS = 30_000L
        /** Custody is read once a minute; while a handover waits, each renewal reads it as well. */
        private const val CUSTODY_EVERY = 12L
    }
}
