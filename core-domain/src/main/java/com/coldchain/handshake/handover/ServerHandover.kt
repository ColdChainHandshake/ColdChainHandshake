package com.coldchain.handshake.handover

import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.HandoverOutcome
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepository
import kotlinx.coroutines.flow.firstOrNull

/**
 * What became of the person's confirmation.
 */
sealed interface HandoverResult {
    /** The server has it. [outcome] says whether custody moved or the other person is still to confirm. */
    data class Confirmed(val outcome: HandoverOutcome, val handover: Handover?) : HandoverResult

    /** The server couldn't be reached. It's stored here and sent later, and custody hasn't moved. */
    data class QueuedOffline(val handover: Handover) : HandoverResult
}

/**
 * A handover in which each person confirms on their own phone, and only the server moves custody.
 *
 * Nothing is stored as accepted here until the server has agreed. With no connection a person can still
 * record UNKNOWN or FAIL, which only ever hold a shipment back, but never a PASS.
 */
class ServerHandover(
    private val remote: ColdChainRemote,
    private val orchestrator: HandoverOrchestrator,
    private val shipments: ShipmentRepository,
    private val handovers: HandoverRepository,
    private val history: ShipmentHistorySyncCoordinator,
    private val uploadPending: suspend (String) -> Result<Unit>,
    private val deviceId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * For the current custodian, who names who takes over. The server compares both people's confirmations
     * by head, so everything recorded here must reach it first.
     */
    suspend fun release(shipmentId: String, toUserId: String): Result<HandoverOutcome> {
        uploadPending(shipmentId).getOrElse { return Result.failure(it) }

        val head = remote.getChainHead(shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("The server has no record of this shipment."))

        return confirm(HandoverConfirmation(shipmentId, 0, deviceId(), head.headHash, toUserId = toUserId))
    }

    /**
     * For the person taking over. Their verdict can be stricter than the checks but never more lenient.
     */
    suspend fun receive(
        shipmentId: String,
        verdict: HandoverVerdict?,
        reason: String?,
        vvmStage: VvmStage,
        shakeTest: ShakeTestResult
    ): Result<HandoverResult> {
        val synced = history.hydrateHistory(shipmentId).isSuccess
        val head = if (synced) history.currentHead(shipmentId) else null
        val now = clock()

        val assessment = orchestrator.assess(
            shipmentId = shipmentId,
            workerSigned = true,
            pharmacistSigned = true,
            declaredVerdict = verdict,
            reason = reason,
            vvmStage = vvmStage,
            shakeTest = shakeTest,
            historySynced = synced && head != null,
            serverHead = head,
            timestamp = now
        ).getOrElse { return Result.failure(it) }

        val queueOffline: suspend () -> Result<HandoverResult> = {
            orchestrator.processHandover(
                shipmentId = shipmentId,
                workerSigned = true,
                pharmacistSigned = true,
                declaredVerdict = verdict,
                reason = assessment.decision.reason,
                vvmStage = vvmStage,
                shakeTest = shakeTest,
                historySynced = false,
                timestamp = now
            ).map { HandoverResult.QueuedOffline(it.handover) }
        }

        if (head == null) return queueOffline()

        val decision = assessment.decision
        val confirmed = confirm(
            HandoverConfirmation(
                shipmentId = shipmentId,
                leg = 0,
                deviceId = deviceId(),
                headHash = head.headHash,
                verdict = decision.verdict,
                reason = decision.reason,
                vvmStage = vvmStage,
                shakeTest = shakeTest
            )
        )

        val outcome = confirmed.getOrNull()
        if (outcome != null) {
            val handover = assessment.toHandover(shipmentId, now)
            return handovers.saveConfirmedHandover(handover).map { HandoverResult.Confirmed(outcome, handover) }
        }

        val failure = confirmed.exceptionOrNull()!!
        return when {
            failure is RemoteRejection && failure.code == RemoteRejection.ALREADY_CONFIRMED ->
                Result.success(alreadyConfirmed(shipmentId))
            failure is RemoteRejection -> Result.failure(failure)
            decision.verdict == HandoverVerdict.PASS -> Result.failure(
                IllegalStateException("The server couldn't be reached, so this PASS wasn't recorded.")
            )
            else -> queueOffline()
        }
    }

    /**
     * Follows what the other person did since, without needing a new confirmation.
     */
    suspend fun refresh(shipmentId: String): Result<Unit> {
        val found = remote.getShipment(shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalArgumentException("No shipment with that code."))
        return adopt(shipmentId, found.status)
    }

    private suspend fun confirm(confirmation: HandoverConfirmation): Result<HandoverOutcome> {
        val custody = remote.getCustody(confirmation.shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("Nobody has taken custody of this shipment yet."))

        return remote.confirmHandover(confirmation.copy(leg = custody.leg))
            .onSuccess { adopt(confirmation.shipmentId, it.status) }
    }

    private suspend fun adopt(shipmentId: String, status: com.coldchain.handshake.models.ShipmentStatus): Result<Unit> {
        val current = shipments.getShipment(shipmentId).firstOrNull() ?: return Result.success(Unit)
        if (current.status == status) return Result.success(Unit)
        return shipments.adoptRemoteShipment(current.copy(status = status))
    }

    private suspend fun alreadyConfirmed(shipmentId: String): HandoverResult {
        val status = remote.getShipment(shipmentId).getOrNull()?.status
            ?: shipments.getShipment(shipmentId).firstOrNull()?.status
            ?: throw IllegalStateException("No shipment with that code.")
        return HandoverResult.Confirmed(
            HandoverOutcome(com.coldchain.handshake.repository.HandoverState.WAITING, status),
            handovers.getHandover(shipmentId).firstOrNull()
        )
    }
}

/**
 * The head a person's confirmation is held against.
 */
internal fun ShipmentHistorySyncCoordinator.currentHead(shipmentId: String): ServerChainHead? =
    getServerHead(shipmentId).value
