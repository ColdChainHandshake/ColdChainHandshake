package com.coldchain.handshake.handover

import com.coldchain.handshake.crypto.ServerChainHead
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.ColdChainRemote
import com.coldchain.handshake.repository.HandoverConfirmation
import com.coldchain.handshake.repository.HandoverOutcome
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.HandoverState
import com.coldchain.handshake.repository.RemoteRejection
import com.coldchain.handshake.repository.ShipmentHistorySyncCoordinator
import com.coldchain.handshake.repository.ShipmentRepository
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.ConcurrentHashMap

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
    private val clock: () -> Long = System::currentTimeMillis,
    private val pendingReleases: PendingReleases = PendingReleases.inMemory(),
    private val onQueuedOffline: () -> Unit = {}
) {

    /**
     * For the current custodian, who names who takes over. The server compares both people's confirmations
     * by head, so everything recorded here must reach it first.
     *
     * Recording doesn't stop here: the receiver's verdict covers readings up to their own confirmation, so the
     * courier keeps recording until custody moves, and [renewRelease] confirms again on each new head.
     */
    suspend fun release(shipmentId: String, toUserId: String): Result<HandoverOutcome> {
        uploadPending(shipmentId).getOrElse { return Result.failure(it) }

        val head = remote.getChainHead(shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("The server has no record of this shipment."))

        return confirm(HandoverConfirmation(shipmentId, 0, deviceId(), head.headHash, toUserId = toUserId))
            .onSuccess { outcome -> remember(shipmentId, toUserId, outcome, head.headHash) }
    }

    /** True while this phone's confirmation waits for the other person's: custody can then move at any moment. */
    fun isWaiting(shipmentId: String): Boolean = pendingReleases.get(shipmentId) != null

    /**
     * Sends what was recorded since, then confirms the same handover again on the server's new head, so a receiver
     * confirming now finds the custodian's confirmation on the history they checked.
     *
     * @return The server's answer, or null when nothing is waiting here or custody has already moved on, in which
     *   case the waiting handover is forgotten.
     */
    suspend fun renewRelease(shipmentId: String): Result<HandoverOutcome?> {
        val toUserId = pendingReleases.get(shipmentId) ?: return Result.success(null)
        // Only this phone, the custodian, adds to the head. With nothing recorded since its last confirmation, the head
        // hasn't moved and the server already has this confirmation on it, so only custody needs asking: one request,
        // not three (finding 11: on the dev project the courier confirmed again every 5 s, and nearly every call was
        // refused as already made).
        lastConfirmed[shipmentId]?.let { (head, outcome) ->
            if (history.newestHeldHash(shipmentId) == head) {
                val custody = remote.getCustody(shipmentId).getOrElse { return Result.failure(it) }
                if (custody?.custodianId == toUserId) {
                    forget(shipmentId)
                    return Result.success(null)
                }
                return Result.success(outcome)
            }
        }
        uploadPending(shipmentId).getOrElse { return Result.failure(it) }

        val head = remote.getChainHead(shipmentId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("The server has no record of this shipment."))

        val renewed = confirm(HandoverConfirmation(shipmentId, 0, deviceId(), head.headHash, toUserId = toUserId))
        val failure = renewed.exceptionOrNull()
            ?: return renewed.onSuccess { outcome -> remember(shipmentId, toUserId, outcome, head.headHash) }

        return when {
            failure is RemoteRejection && failure.code == RemoteRejection.ALREADY_CONFIRMED ->
                remote.getShipment(shipmentId).map { found ->
                    HandoverOutcome(HandoverState.WAITING, found?.status ?: ShipmentStatus.IN_TRANSIT)
                        .also { waiting -> lastConfirmed[shipmentId] = head.headHash to waiting }
                }
            custodyMovedOn(shipmentId) -> {
                forget(shipmentId)
                Result.success(null)
            }
            else -> Result.failure(failure)
        }
    }

    /** Renews every handover waiting on this phone; the first failure is returned once all were tried. */
    suspend fun renewReleases(): Result<Unit> {
        val failures = pendingReleases.shipmentIds().mapNotNull { renewRelease(it).exceptionOrNull() }
        return failures.firstOrNull()?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * The head this phone last confirmed a waiting handover on, and the server's answer, kept for this process only:
     * after a restart the first renewal asks the server again.
     */
    private val lastConfirmed = ConcurrentHashMap<String, Pair<String, HandoverOutcome>>()

    private fun remember(shipmentId: String, toUserId: String, outcome: HandoverOutcome, head: String) {
        if (outcome.state == HandoverState.WAITING) {
            pendingReleases.put(shipmentId, toUserId)
            lastConfirmed[shipmentId] = head to outcome
        } else {
            forget(shipmentId)
        }
    }

    private fun forget(shipmentId: String) {
        pendingReleases.remove(shipmentId)
        lastConfirmed.remove(shipmentId)
    }

    /**
     * The person named holds it now, or nobody can hand it over any more. When the server can't be asked, the
     * answer is no, so a dropped connection never forgets a handover still waiting.
     */
    private suspend fun custodyMovedOn(shipmentId: String): Boolean {
        val custody = remote.getCustody(shipmentId).getOrElse { return false } ?: return true
        val status = remote.getShipment(shipmentId).getOrElse { return false }?.status ?: return true
        val open = status == ShipmentStatus.IN_TRANSIT || status == ShipmentStatus.QUARANTINED
        return !open || pendingReleases.get(shipmentId) == custody.custodianId
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
        val first = receiveOnce(shipmentId, verdict, reason, vvmStage, shakeTest)
        val stale = (first.exceptionOrNull() as? RemoteRejection)?.code == RemoteRejection.STALE_HEAD
        // The courier keeps recording until custody moves, so a reading can land between fetching the history and
        // confirming. Fetch and check again once, so the verdict covers what the server now holds.
        return if (stale) receiveOnce(shipmentId, verdict, reason, vvmStage, shakeTest) else first
    }

    private suspend fun receiveOnce(
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
                .onSuccess { onQueuedOffline() }
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
