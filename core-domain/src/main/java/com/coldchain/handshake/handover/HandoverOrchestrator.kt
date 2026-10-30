package com.coldchain.handshake.handover

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.HandoverVerdict
import com.coldchain.handshake.models.ProductProfiles
import com.coldchain.handshake.models.ShakeTestResult
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.models.ShipmentStatus
import com.coldchain.handshake.models.VvmStage
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
import com.coldchain.handshake.safety.BreachDetector
import com.coldchain.handshake.safety.ShipmentLifecycle
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

/**
 * Result bundle returned by [HandoverOrchestrator.processHandover].
 *
 * @param handover The persisted official [Handover] record.
 * @param evaluation The transparent [HandoverEvaluation] details from [HandoverVerdictEngine].
 * @param updatedShipment The updated [Shipment] with its new lifecycle status.
 */
data class HandoverOrchestratorResult(
    val handover: Handover,
    val evaluation: HandoverEvaluation,
    val updatedShipment: Shipment
)

/**
 * Handover Orchestration Service.
 *
 * Connects the domain layer by coordinating:
 * 1. Fetching shipment metadata via [ShipmentRepository]
 * 2. Fetching telemetry trajectory via [TelemetryRepository]
 * 3. Cryptographic integrity verification via [HashChainService]
 * 4. Computing the temperature verdict itself via [BreachDetector], using the shipment's own
 *    product profile and the time of handover. No caller can supply it.
 * 5. Consuming dual confirmations ([workerSigned], [pharmacistSigned])
 * 6. Authoritative verdict evaluation via [HandoverVerdictEngine], including the receiver's findings
 * 7. Moving the shipment through arrival and inspection via [ShipmentLifecycle]
 * 8. Constructing and persisting the official [Handover] model via [HandoverRepository]
 * 9. Updating and persisting the resulting [Shipment] lifecycle status via [ShipmentRepository]
 */
class HandoverOrchestrator(
    private val shipmentRepository: ShipmentRepository,
    private val telemetryRepository: TelemetryRepository,
    private val handoverRepository: HandoverRepository
) {

    /**
     * Executes custody handover orchestration.
     *
     * @param shipmentId Identifier of the consignment being inspected.
     * @param workerSigned Courier / logistics worker digital confirmation.
     * @param pharmacistSigned Receiving pharmacist digital confirmation.
     * @param declaredVerdict The receiver's verdict, or null to record the computed one. It can
     *   be stricter than the computed verdict but not more lenient.
     * @param reason The receiver's explanation. A declared FAIL or UNKNOWN needs one unless the
     *   checks themselves found a problem.
     * @param vvmStage Vial monitor stage seen at inspection.
     * @param shakeTest Shake test result seen at inspection.
     * @param handoverId Optional deterministic ID for the handover record (defaults to random UUID).
     * @param timestamp Timestamp of custody handover event. Readings are expected up to this moment.
     * @return [Result] containing [HandoverOrchestratorResult] on success, or failure with diagnostic error.
     */
    suspend fun processHandover(
        shipmentId: String,
        workerSigned: Boolean,
        pharmacistSigned: Boolean,
        declaredVerdict: HandoverVerdict? = null,
        reason: String? = null,
        vvmStage: VvmStage = VvmStage.NOT_RECORDED,
        shakeTest: ShakeTestResult = ShakeTestResult.NOT_DONE,
        handoverId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): Result<HandoverOrchestratorResult> {
        // 1. Obtain the shipment
        val shipment = try {
            shipmentRepository.getShipment(shipmentId).firstOrNull()
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Failed to query shipment with id: $shipmentId", e))
        } ?: return Result.failure(IllegalArgumentException("Shipment not found with id: $shipmentId"))

        // 2. The shipment must be somewhere a handover can happen
        val pathToInspection = ShipmentLifecycle.pathToInspection(shipment.status)
            .getOrElse { return Result.failure(it) }

        // 3. Obtain temperature events for that shipment
        val events = try {
            telemetryRepository.getTemperatures(shipmentId).firstOrNull()
        } catch (e: Exception) {
            return Result.failure(
                IllegalStateException("Telemetry stream could not be obtained for shipment: $shipmentId", e)
            )
        } ?: return Result.failure(
            IllegalStateException("Telemetry stream could not be obtained for shipment: $shipmentId")
        )

        // 4. Application independently determines integrity and temperature from the actual events
        val integrityResult = HashChainService.verifyChain(events)
        val integrityPassed = integrityResult.valid
        val temperature = BreachDetector.evaluateTelemetry(
            events = events,
            profile = ProductProfiles.byId(shipment.productProfileId),
            windowStart = shipment.journeyStartedAt,
            windowEnd = timestamp
        )

        // 5. Authoritative evaluation via HandoverVerdictEngine
        val evaluation = HandoverVerdictEngine.evaluate(
            temperature = temperature.verdict,
            integrityPassed = integrityPassed,
            workerSigned = workerSigned,
            pharmacistSigned = pharmacistSigned,
            vvmStage = vvmStage,
            shakeTest = shakeTest
        )
        val decision = HandoverVerdictEngine.decide(evaluation, declaredVerdict, reason)
            .getOrElse { return Result.failure(it) }

        // 6. Check the outcome is reachable before anything is written
        val inspectedStatus = pathToInspection.lastOrNull() ?: shipment.status
        if (inspectedStatus != decision.targetStatus &&
            !ShipmentLifecycle.canTransition(inspectedStatus, decision.targetStatus)
        ) {
            return Result.failure(
                IllegalStateException("A $inspectedStatus shipment cannot become ${decision.targetStatus}.")
            )
        }

        // 7. Move through arrival and inspection
        for (status in pathToInspection) {
            val saveResult = shipmentRepository.saveShipment(shipment.copy(status = status))
            if (saveResult.isFailure) {
                return Result.failure(
                    saveResult.exceptionOrNull() ?: Exception("Failed to update Shipment status")
                )
            }
        }

        // 8. Construct official Handover model
        val handover = Handover(
            id = handoverId,
            shipmentId = shipmentId,
            workerSigned = workerSigned,
            pharmacistSigned = pharmacistSigned,
            integrityVerified = integrityPassed,
            temperaturePassed = temperature.temperaturePassed,
            verdict = decision.verdict,
            timestamp = timestamp,
            reason = decision.reason,
            vvmStage = vvmStage,
            shakeTest = shakeTest
        )

        // 9. Persist Handover record
        val saveHandoverResult = handoverRepository.saveHandover(handover)
        if (saveHandoverResult.isFailure) {
            return Result.failure(
                saveHandoverResult.exceptionOrNull() ?: Exception("Failed to persist Handover record")
            )
        }

        // 10. Update Shipment lifecycle status (ACCEPTED or QUARANTINED)
        val updatedShipment = shipment.copy(status = decision.targetStatus)
        if (decision.targetStatus != inspectedStatus) {
            val saveShipmentResult = shipmentRepository.saveShipment(updatedShipment)
            if (saveShipmentResult.isFailure) {
                return Result.failure(
                    saveShipmentResult.exceptionOrNull() ?: Exception("Failed to update Shipment status")
                )
            }
        }

        return Result.success(
            HandoverOrchestratorResult(
                handover = handover,
                evaluation = evaluation,
                updatedShipment = updatedShipment
            )
        )
    }

    /**
     * Records that a quarantined shipment needs replacing.
     */
    suspend fun requestReplacement(shipmentId: String): Result<Shipment> {
        val shipment = shipmentRepository.getShipment(shipmentId).firstOrNull()
            ?: return Result.failure(IllegalArgumentException("Shipment not found with id: $shipmentId"))

        return ShipmentLifecycle.transition(shipment, ShipmentStatus.REPLACEMENT_REQUESTED)
            .mapCatching { updated ->
                shipmentRepository.saveShipment(updated).getOrThrow()
                updated
            }
    }
}
