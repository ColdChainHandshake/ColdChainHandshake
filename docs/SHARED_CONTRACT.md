# ColdChainHandshake — Official Shared Contract

This document defines the canonical MVP domain models, enumerations, and interface contracts shared across all four components of the ColdChainHandshake application.

---

## 1. Official Enums

### 1.1 `ShipmentStatus`
Defines the official lifecycle states of a shipment:
- `CREATED`: Consignment configured; logger assigned.
- `DISPATCHED`: Cargo sealed and dispatched from origin.
- `IN_TRANSIT`: Actively moving under thermal monitoring.
- `ARRIVED`: Arrived at destination hub or pharmacy.
- `INSPECTED`: Inspected by receiving personnel.
- `ACCEPTED`: Successfully accepted into recipient inventory.
- `QUARANTINED`: Placed in quarantine due to thermal breach or integrity failure.
- `REPLACEMENT_REQUESTED`: Replacement shipment triggered following quarantine.

### 1.2 `SyncStatus`
Official offline synchronization states:
- `PENDING`: Local record queued for synchronization.
- `SYNCED`: Record replicated to remote Supabase database.
- `FAILED`: Synchronization failed; pending retry.

### 1.3 `AlertType`
Categories of operational alerts:
- `TEMPERATURE_BREACH`: Temperature strictly > 8°C for cumulative duration > 5 minutes.
- `LOGGER_DISCONNECT`: Telemetry logger dropped or unreachable.
- `OPERATIONAL_WARNING`: General warning requiring worker attention.

### 1.4 `EscalationLevel`
Escalation tier hierarchy for alerts:
- `WORKER`: Initial alert routed to the active courier or handler.
- `SUPERVISOR`: Unresolved alert escalated to logistics supervisor.
- `PHARMACIST`: Critical excursion escalated to receiving pharmacist.

### 1.5 `HandoverVerdict`
Official custody handover outcome:
- `PASS`: Both signatures verified, integrity confirmed, temperature passed.
- `FAIL`: Handshake failed due to breach, missing signature, or corrupted hash.

### 1.6 `ChaosScenarioType`
Supported fault injection types:
- `HEAT_SPIKE`: Inject 9°C–11°C excursion to test breach detection.
- `LOGGER_DISCONNECT`: Simulate telemetry sensor disconnection.
- `NETWORK_FAILURE`: Simulate network outage to verify offline sync.
- `CORRUPT_EVENT`: Corrupt event payload or hash to test integrity verification failure.

---

## 2. Official Core Models

### 2.1 `Shipment`
```kotlin
data class Shipment(
    val id: String,
    val qrCode: String,
    val loggerId: String,
    val origin: String,
    val destination: String,
    val workerId: String,
    val status: ShipmentStatus = ShipmentStatus.CREATED
)
```

### 2.2 `TemperatureEvent`
```kotlin
data class TemperatureEvent(
    val id: String,
    val shipmentId: String,
    val loggerId: String,
    val timestamp: Long,
    val temperature: Double,
    val previousHash: String,
    val currentHash: String,
    val syncStatus: SyncStatus
)
```

### 2.3 `Alert`
```kotlin
data class Alert(
    val id: String,
    val shipmentId: String,
    val type: AlertType,
    val message: String,
    val timestamp: Long,
    val escalationLevel: EscalationLevel,
    val acknowledged: Boolean
)
```

### 2.4 `Handover`
```kotlin
data class Handover(
    val id: String,
    val shipmentId: String,
    val workerSigned: Boolean,
    val pharmacistSigned: Boolean,
    val integrityVerified: Boolean,
    val temperaturePassed: Boolean,
    val verdict: HandoverVerdict,
    val timestamp: Long
)
```

---

## 3. Official Business Rules

- **Safe Range**: 2°C–8°C inclusive.
- **Normal Simulator**: 4°C–6°C.
- **Heat Spike**: 9°C–11°C.
- **Temperature Breach**: Temperature strictly greater than 8°C AND cumulative duration strictly greater than 5 minutes.
- **Out of Scope**: No MKT, no predictive analytics, no AI, no GPS/maps, no real BLE hardware, no auth servers, no complex analytics dashboards.

---

## 4. Official Repository & Service Contracts

### 4.1 `ShipmentRepository`
```kotlin
interface ShipmentRepository {
    suspend fun saveShipment(shipment: Shipment): Result<Unit>
    fun getShipment(id: String): Flow<Shipment?>
    fun getAllShipments(): Flow<List<Shipment>>
}
```

### 4.2 `TelemetryRepository`
```kotlin
interface TelemetryRepository {
    suspend fun saveTemperature(event: TemperatureEvent): Result<Unit>
    fun getTemperatures(shipmentId: String): Flow<List<TemperatureEvent>>
}
```

### 4.3 `AlertRepository`
```kotlin
interface AlertRepository {
    suspend fun saveAlert(alert: Alert): Result<Unit>
    fun getActiveAlerts(shipmentId: String): Flow<List<Alert>>
}
```

### 4.4 `HandoverRepository`
```kotlin
interface HandoverRepository {
    suspend fun saveHandover(handover: Handover): Result<Unit>
    fun getHandover(shipmentId: String): Flow<Handover?>
}
```

### 4.5 `SyncService`
```kotlin
interface SyncService {
    suspend fun syncPendingData(): Result<Unit>
    fun isOnline(): Boolean
    fun getSyncStatus(): Flow<SyncStatus>
}
```

### 4.6 `ChaosEngineService`
```kotlin
interface ChaosEngineService {
    fun observeActiveScenarios(): Flow<List<ChaosEvent>>
    suspend fun injectScenario(scenarioType: ChaosScenarioType): Result<ChaosEvent>
    suspend fun resetScenario(scenarioType: ChaosScenarioType): Result<Unit>
    suspend fun resetAllScenarios(): Result<Unit>
}
```
