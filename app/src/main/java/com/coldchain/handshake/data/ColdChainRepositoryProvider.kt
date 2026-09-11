package com.coldchain.handshake.data

import com.coldchain.handshake.data.repository.LocalHandoverRepository
import com.coldchain.handshake.data.repository.LocalShipmentRepository
import com.coldchain.handshake.data.repository.LocalTelemetryRepository
import com.coldchain.handshake.repository.HandoverRepository
import com.coldchain.handshake.repository.ShipmentRepository
import com.coldchain.handshake.repository.TelemetryRepository

/**
 * Shared Application-Level Repository Provider.
 *
 * Ensures the entire application process shares the EXACT SAME repository and data source instances.
 * Person 2 (BLE logger/sync), Person 3 (thermal evaluator), Person 4 (integrity/handover orchestrator),
 * and all ViewModels access these single shared instances.
 */
object ColdChainRepositoryProvider {

    @Volatile
    private var telemetryRepoInstance: TelemetryRepository? = null

    @Volatile
    private var shipmentRepoInstance: ShipmentRepository? = null

    @Volatile
    private var handoverRepoInstance: HandoverRepository? = null

    /**
     * Shared TelemetryRepository instance.
     */
    val telemetryRepository: TelemetryRepository
        get() = telemetryRepoInstance ?: synchronized(this) {
            telemetryRepoInstance ?: LocalTelemetryRepository().also {
                telemetryRepoInstance = it
            }
        }

    /**
     * Shared ShipmentRepository instance.
     */
    val shipmentRepository: ShipmentRepository
        get() = shipmentRepoInstance ?: synchronized(this) {
            shipmentRepoInstance ?: LocalShipmentRepository().also {
                shipmentRepoInstance = it
            }
        }

    /**
     * Shared HandoverRepository instance.
     */
    val handoverRepository: HandoverRepository
        get() = handoverRepoInstance ?: synchronized(this) {
            handoverRepoInstance ?: LocalHandoverRepository().also {
                handoverRepoInstance = it
            }
        }

    /**
     * Explicitly set custom repositories (e.g. for specialized testing or future Room integration).
     */
    fun setRepositories(
        telemetry: TelemetryRepository? = null,
        shipment: ShipmentRepository? = null,
        handover: HandoverRepository? = null
    ) {
        synchronized(this) {
            if (telemetry != null) telemetryRepoInstance = telemetry
            if (shipment != null) shipmentRepoInstance = shipment
            if (handover != null) handoverRepoInstance = handover
        }
    }

    /**
     * Resets repository instances to fresh stores for isolated test runs.
     */
    fun resetForTesting() {
        synchronized(this) {
            telemetryRepoInstance = LocalTelemetryRepository()
            shipmentRepoInstance = LocalShipmentRepository()
            handoverRepoInstance = LocalHandoverRepository()
        }
    }
}

