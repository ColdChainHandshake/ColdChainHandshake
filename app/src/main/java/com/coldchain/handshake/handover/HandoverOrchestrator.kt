package com.coldchain.handshake.handover

import com.coldchain.handshake.crypto.HashChainService
import com.coldchain.handshake.models.Handover
import com.coldchain.handshake.models.Shipment
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository
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
 * 4. Consuming already-computed [temperaturePassed] from Person 3's breach engine
 * 5. Consuming dual confirmations ([workerSigned], [pharmacistSigned])
 * 6. Authoritative verdict evaluation via [HandoverVerdictEngine]
 * 7. Constructing and persisting the official [Handover] model via [HandoverRepository]
 * 8. Updating and persisting the resulting [Shipment] lifecycle status via [ShipmentRepository]
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
     * @param temperaturePassed Already-computed thermal safety verdict (owned by Person 3).
     * @param workerSigned Courier / logistics worker digital confirmation.
     * @param pharmacistSigned Receiving pharmacist digital confirmation.
     * @param handoverId Optional deterministic ID for the handover record (defaults to random UUID).
     * @param timestamp Timestamp of custody handover event.
     * @return [Result] containing [HandoverOrchestratorResult] on success, or failure with diagnostic error.
     */
    suspend fun processHandover(
        shipmentId: String,
        temperaturePassed: Boolean,
        workerSigned: Boolean,
        pharmacistSigned: Boolean,
        handoverId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): Result<HandoverOrchestratorResult> {
        // 1. Obtain the shipment
        val shipment = try {
            shipmentRepository.getShipment(shipmentId).firstOrNull()
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Failed to query shipment with id: $shipmentId", e))
        } ?: return Result.failure(IllegalArgumentException("Shipment not found with id: $shipmentId"))

        // 2. Obtain temperature events for that shipment
        val events = try {
            telemetryRepository.getTemperatures(shipmentId).firstOrNull()
        } catch (e: Exception) {
            return Result.failure(
                IllegalStateException("Telemetry stream could not be obtained for shipment: $shipmentId", e)
            )
        } ?: return Result.failure(
            IllegalStateException("Telemetry stream could not be obtained for shipment: $shipmentId")
        )

        // 3. Application independently determines integrity from the actual temperature event chain
        val integrityResult = HashChainService.verifyChain(events)
        val integrityPassed = integrityResult.valid

        // 4. Authoritative evaluation via HandoverVerdictEngine
        val evaluation = HandoverVerdictEngine.evaluate(
            temperaturePassed = temperaturePassed,
            integrityPassed = integrityPassed,
            workerSigned = workerSigned,
            pharmacistSigned = pharmacistSigned
        )

        // 5. Construct official Handover model matching shared contract exactly
        val handover = Handover(
            id = handoverId,
            shipmentId = shipmentId,
            workerSigned = workerSigned,
            pharmacistSigned = pharmacistSigned,
            integrityVerified = integrityPassed,
            temperaturePassed = temperaturePassed,
            verdict = evaluation.verdict,
            timestamp = timestamp
        )

        // 6. Persist Handover record
        val saveHandoverResult = handoverRepository.saveHandover(handover)
        if (saveHandoverResult.isFailure) {
            return Result.failure(
                saveHandoverResult.exceptionOrNull() ?: Exception("Failed to persist Handover record")
            )
        }

        // 7. Update Shipment lifecycle status using evaluation.targetStatus (ACCEPTED or QUARANTINED)
        val updatedShipment = shipment.copy(status = evaluation.targetStatus)
        val saveShipmentResult = shipmentRepository.saveShipment(updatedShipment)
        if (saveShipmentResult.isFailure) {
            return Result.failure(
                saveShipmentResult.exceptionOrNull() ?: Exception("Failed to update Shipment status")
            )
        }

        return Result.success(
            HandoverOrchestratorResult(
                handover = handover,
                evaluation = evaluation,
                updatedShipment = updatedShipment
            )
        )
    }
}
