# ❄️ Cold Chain Handshake

### Offline-First Pharmaceutical Cold-Chain Tracking, Safety & Custody Verification

> **Cold Chain Handshake** is a native Android prototype built to demonstrate a reliable end-to-end pharmaceutical cold-chain workflow where telemetry, safety decisions, device handover, and data integrity continue to function even when connectivity is unreliable.

---

## 🚨 The Problem

Pharmaceutical products such as vaccines, insulin, and other temperature-sensitive medicines depend on a controlled environment throughout transportation.

A single failure can create a chain of uncertainty:

- Was the shipment actually kept within the safe temperature range?
- What happened while the device was offline?
- Was the telemetry modified after it was recorded?
- Did the correct person receive the shipment?
- Can a receiving pharmacist independently verify what happened during the journey?
- What happens when the logger disconnects or the network disappears?

Traditional tracking systems often focus on connectivity and dashboards.

**Cold Chain Handshake focuses on trustworthy operational history.**

---

# 💡 Our Solution

Cold Chain Handshake creates a digital chain of custody around a shipment.

The application combines:

✅ Offline-first local persistence  
✅ Simulated temperature telemetry  
✅ Automatic synchronization  
✅ Temperature breach detection  
✅ Multi-level safety escalation  
✅ Quarantine handling  
✅ Cryptographic telemetry integrity  
✅ QR-based shipment pairing  
✅ Phone-to-phone shipment monitoring  
✅ GPS location snapshots  
✅ Historical destination inspection  
✅ Worker + pharmacist signatures  
✅ PASS / FAIL handover verdicts  
✅ Persistent shipment-scoped event logs  

The goal is simple:

> **A shipment should remain traceable, verifiable, and operational even when the network is not.**

---

# 🔄 End-to-End Workflow

```text
DISPATCH
   ↓
PAIR LOGGER
   ↓
START JOURNEY
   ↓
TEMPERATURE STREAM
   ↓
LOCAL BUFFERING
   ↓
AUTO SYNC
   ↓
BREACH DETECTION
   ↓
WORKER
   ↓
SUPERVISOR
   ↓
PHARMACIST
   ↓
HANDOVER
   ↓
PASS / FAIL
   ↓
HASH VERIFICATION
```

---

# ⚠️ Status

This is a prototype and is not fit for real vaccine or medicine stock.

The temperature readings are simulated, and the rebuild described in `docs/agent/MASTER_PROMPT.md` is in progress on
the `rebuild/trust-core` branch. Until it finishes, parts of this page describe the demo rather than guarantees: the
hash chain and the handover confirmations do not yet stop a determined courier from changing the record.

---

# 🛠️ Build and test

You need JDK 17, either Android Studio's `jbr` folder or [Temurin 17](https://adoptium.net/), and the Android SDK.
Docker is needed for the database tests only.

Set `JAVA_HOME` to the JDK and `ANDROID_HOME` to the SDK, then run these from Git Bash:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

From PowerShell, use `.\gradlew.bat` in place of `./gradlew`.

Default unit tests make no calls to real servers. The one exception is Robolectric, which downloads its Android
jars from Maven Central on first use.

Cloud sync doesn't work at the moment. The database now denies anonymous access, and sign-in arrives in Phase 3 of
the rebuild, so the app works on one device until then.

Database tests run against a local Postgres container:

```bash
npx supabase db start
npx supabase test db
```

Fault injection (the Chaos tab) exists only in debug builds.
