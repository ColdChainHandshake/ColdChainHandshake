# ColdChainHandshake — Component Boundaries & Responsibilities

This document defines the four component boundaries for the ColdChainHandshake MVP. All components rely strictly on the shared contract models without redefining them.

---

## Person 1: Data, Persistence & Offline Synchronization

### Responsibilities
- **Local Database**: Room entities and DAOs mapping directly to the shared models:
  - `Shipment`
  - `TemperatureEvent`
  - `Alert`
  - `Handover`
- **Remote Integration**: Supabase client connection and replication logic.
- **Offline Synchronization**:
  - Store-and-forward queue for unsynced records (`SyncStatus.PENDING`).
  - Network state observation via Android `ConnectivityManager` (`isOnline()`).
  - Implementation of `SyncService`.

### Reference Pattern
- Pattern from `reference/novumlogic/` (`SyncManager`, `BaseSyncableEntity`, `GenericDao`) re-implemented natively inside `:app` without touching or importing the reference repository.

---

## Person 2: Dispatch, QR, Transit & Simulator

### Responsibilities
- **Dispatch**:
  - UI and flow to configure new shipments (origin, destination, workerId, loggerId).
  - QR code payload generation representing the dispatch manifest (`qrCode`).
  - Implementation of `ShipmentRepository`.
- **Transit & Simulator**:
  - Temperature sensor simulator:
    - Normal operating range: 4°C–6°C.
    - Safe baseline boundary: 2°C–8°C inclusive.
  - Periodic emission of `TemperatureEvent` into `TelemetryRepository`.

### Reference Pattern
- QR generation pattern from `reference/nuekkis/` (`QRCodeUtil`) adapted natively inside `:app`.

---

## Person 3: Safety, Breach Detection & Alert Escalation

### Responsibilities
- **Breach Detection Engine**:
  - Evaluates temperature stream against official threshold:
    - Safe range: 2°C–8°C inclusive.
    - Breach condition: Temperature strictly $> 8^\circ\text{C}$ AND cumulative duration strictly $> 5\text{ minutes}$.
  - Triggers `AlertType.TEMPERATURE_BREACH`.
- **Alert Escalation Pipeline**:
  - Multi-tier alert escalation: `WORKER` $\rightarrow$ `SUPERVISOR` $\rightarrow$ `PHARMACIST`.
  - Operator acknowledgment flow (`acknowledged = true`).
  - Implementation of `AlertRepository`.

---

## Person 4: Hash Chain, Verification, Handover, Replacement & Chaos

### Responsibilities
- **Hash Chain**:
  - Computes cryptographic SHA-256 links on `TemperatureEvent`:
    - `currentHash = SHA-256(previousHash + eventData)`
  - Verifies unbroken hash continuity during inspection.
- **Handover**:
  - Handover verification screen and mutual signing:
    - `workerSigned`
    - `pharmacistSigned`
    - `integrityVerified`
    - `temperaturePassed`
    - `verdict` (`PASS` or `FAIL`)
  - Status progression: `ARRIVED` $\rightarrow$ `INSPECTED` $\rightarrow$ `ACCEPTED` or `QUARANTINED`.
  - Implementation of `HandoverRepository`.
- **Replacement**:
  - Automatically transitions failed or quarantined shipments to `REPLACEMENT_REQUESTED`.
- **Chaos Lab**:
  - Fault injection controls:
    - `HEAT_SPIKE` (9°C–11°C)
    - `LOGGER_DISCONNECT`
    - `NETWORK_FAILURE`
    - `CORRUPT_EVENT`
  - Implementation of `ChaosEngineService`.

### Reference Pattern
- QR scanning pattern with CameraX + ML Kit from `reference/nuekkis/` adapted natively inside `:app`.
