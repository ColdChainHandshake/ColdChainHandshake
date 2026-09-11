# ColdChainHandshake — End-to-End Demo Flow (MVP)

This document describes the user journey demonstrating the four components working together in accordance with the official MVP business rules.

---

## 1. Flow Diagram

```mermaid
sequenceDiagram
    autonumber
    actor Worker as Field Worker / Courier
    actor Pharmacist as Receiving Pharmacist
    participant App as ColdChainHandshake App
    participant Chaos as Chaos Lab

    Note over Worker, App: Step 1: Dispatch
    Worker->>App: Create Shipment (origin, destination, loggerId, workerId)
    App->>App: Generate Shipment QR Code & status = CREATED
    Worker->>App: Tap "Dispatch" -> status = DISPATCHED

    Note over Worker, App: Step 2: Transit & Telemetry
    App->>App: Simulator logs temperatures (4°C–6°C normal)
    App->>App: Compute SHA-256 hash chain per TemperatureEvent
    App->>App: Store events locally in Room; sync to Supabase when online

    Note over Chaos, App: Step 3: Heat Spike Injection
    Chaos->>App: Inject "Heat Spike" (9°C–11°C)
    App->>App: Cumulative duration > 5 minutes detected (>8°C)
    App->>App: Trigger Alert (TEMPERATURE_BREACH)
    App->>App: Escalate (WORKER -> SUPERVISOR -> PHARMACIST)

    Note over Worker, Pharmacist: Step 4: Arrival & Inspection
    Worker->>App: Arrive at destination -> status = ARRIVED
    Pharmacist->>App: Inspect Consignment -> status = INSPECTED
    App->>App: Verify hash chain integrity (previousHash / currentHash)
    App->>App: Evaluate thermal breach history

    alt Handover PASS (No Breach & Hash Valid)
        Worker->>App: Sign handover (workerSigned = true)
        Pharmacist->>App: Sign handover (pharmacistSigned = true)
        App->>App: Handover verdict = PASS -> status = ACCEPTED
    else Handover FAIL (Breach or Integrity Failure)
        Pharmacist->>App: Reject handover -> verdict = FAIL
        App->>App: status = QUARANTINED
        Pharmacist->>App: Tap "Request Replacement" -> status = REPLACEMENT_REQUESTED
    end
```

---

## 2. Interactive Step-by-Step Walkthrough

### Step 1: Dispatch Screen (`Dispatch` Tab)
1. **Open** the **Dispatch** screen.
2. **Enter Details**:
   - Origin: e.g., "Central Cold Hub"
   - Destination: e.g., "St. Jude Pharmacy"
   - Logger ID: e.g., "LOG-902"
   - Worker ID: e.g., "W-14"
3. **Generate & Dispatch**:
   - Tap **"Create Shipment"** $\rightarrow$ status becomes `CREATED`, QR code payload is generated.
   - Tap **"Dispatch"** $\rightarrow$ status becomes `DISPATCHED`.

### Step 2: Transit Screen (`Transit` Tab)
1. **Open** the **Transit** screen.
2. **Observe Normal Simulator**:
   - Real-time temperature readings in normal range: **4°C–6°C**.
   - Safe boundary visual indicators: **2°C–8°C**.
   - Offline sync indicator displays status: `SYNCED` or `PENDING`.

### Step 3: Chaos Lab (`Chaos` Tab)
1. **Open** the **Chaos** screen.
2. **Trigger Incident**:
   - Select **"Heat Spike (9°C–11°C)"**.
   - Tap **"Inject Scenario"**.
3. **Observe System Response**:
   - Temperature on Transit screen rises into the 9°C–11°C band.
   - Once cumulative excursion duration exceeds 5 minutes, an excursion breach is triggered.

### Step 4: Alerts Screen (`Alerts` Tab)
1. **Open** the **Alerts** screen.
2. **Review Alert**:
   - Type: `TEMPERATURE_BREACH`.
   - Message: Details excursion temperature and duration (>5 minutes >8°C).
   - Escalation Level: `WORKER` $\rightarrow$ escalates to `SUPERVISOR` / `PHARMACIST` if unacknowledged.
3. **Acknowledge**:
   - Worker acknowledges the alert.

### Step 5: Handover Screen (`Handover` Tab)
1. **Open** the **Handover** screen upon shipment arrival.
2. **Inspection & Verification**:
   - System checks hash-chain continuity (`integrityVerified`).
   - System checks thermal history (`temperaturePassed`).
3. **Verdict & Dual Signature**:
   - If clean: `workerSigned = true`, `pharmacistSigned = true` $\rightarrow$ verdict `PASS` $\rightarrow$ status becomes `ACCEPTED`.
   - If breached: verdict `FAIL` $\rightarrow$ status becomes `QUARANTINED`.
   - A single tap allows requesting an emergency replacement $\rightarrow$ status becomes `REPLACEMENT_REQUESTED`.
