# Status: what is proven, and on what

Updated 23 Nov 2026. This is a prototype. Nothing here makes it fit for real vaccine or medicine stock.

"Real system" means a hosted Supabase project, an emulator or phone, or a real database running in Docker.
"Fakes" means tests, fake servers and test vectors that Claude wrote. A fake can agree with the code and still be
wrong about the world: the first real run found a server rule the fake server didn't copy.

## Proven on real systems

All of these ran on the local Docker stack (Supabase's own Postgres 17, GoTrue, PostgREST and Kong images), not a
hosted project.

- **Database rules:** `npx supabase test db`, 233 of 233 checks pass. `db advisors --local`: no issues. They cover:
  - strangers are locked out;
  - readings can't be edited or deleted;
  - a dropped, edited, re-ordered or back-dated reading is refused;
  - only the custodian can upload;
  - one phone can't confirm for both people.
- **The app's own server client, uploader and checks, for one whole handover:** `LiveSupabaseIntegrationTest`, 3 of 3.
  - It signs up three accounts. The sender creates and dispatches a shipment, the courier takes custody and uploads
    signed readings, and the receiver downloads and verifies them. Custody moves only after both confirm.
  - Each shortcut is refused: a stale chain head, an edited reading, an upload by someone without custody, one phone
    confirming for someone else, the sender confirming, and the old custodian uploading afterwards.
  - Someone not signed in, and a stranger, see nothing.
  - It signs up with passwords, so email-code sign-in itself is still untested.

On a phone and an emulator (23 Nov), a Galaxy S24 (SM-S921E, Android 17) and a Pixel 7 emulator (Android 16):

- **The phone's signing key:** `KeystoreDeviceSignerTest`, 3 of 3 on each. The key is made in the Android Keystore,
  stays the same between uses, and its signatures verify with the public key the server is given.
- **Screens on the emulator:** sign-in; the typed email survives rotation, going to the background and the process
  being killed; debug local mode creates, dispatches and starts a shipment and records one reading a minute, through
  rotation. Screenshots in `docs/agent/evidence/`. This found the sign-in title drawn under the status bar (fixed).
- **On the phone so far:** the app starts in 1.1 s with no stall. The rest of the phone pass is still to do.

## Proven only against our own fakes and vectors

- **Full unit and screen tests:** counts in `PROGRESS.md`.
- **Offline behaviour, retries and timing:** against a fake server that copies the real one's refusal rules.
- **Email-code sign-in:** against a fake sign-in server only.
- **Safety rules:** heat, freeze, gaps, one alert per excursion, escalation timing. These are pure logic tests; the
  timers ran in WorkManager's test harness, not in real time.
- **Screens:** driven by Robolectric, which runs Android on the PC's JVM. No screen has been seen on a phone.
- **Hash chain:** Python, Postgres and Kotlin agree on 18 shared test cases.
- **Logger protocol:** Kotlin agrees with a separate Python version on the vectors. Both were written by Claude; one
  signature is checked against an outside source (RFC 6979).
- **Logger test key refused:** in unit tests for both builds, and neither built APK contains its private key.
- **Reading a logger over Bluetooth:** the protocol side runs against a fake Bluetooth server and the Python
  vectors, including lost and silent notifications. The Android Bluetooth code compiles but has never talked to a
  logger, because there isn't one (`HARDWARE = none`).
- **Checking the logger at handover:** in tests, a history that was edited, cut short, replayed, signed with the
  wrong or the test key, or doesn't match the readings fails the handover; one with holes or faults is UNKNOWN.
  Not on a screen yet.
- **Firmware:** interfaces and 38 tests build with g++; the code is the owner's to write, so 37 fail by design.

## Not proven

- Anything on a hosted Supabase project. No migration has reached one (the dev project is paused).
- On a device: email-code sign-in, notifications, real timers, and the handover with each person on their own
  phone (Gate 3).
- Recording and sync with the screen off, after the app is killed, or after a long time offline.
- A real logger. The virtual logger exists in code, but the app doesn't use it yet.
- The GitHub Actions workflow has never run on GitHub.

## Gates

`gate-0` and `gate-2` are tagged. `gate-1` waits for the owner's answer (`HANDOFF.md` Q2). `gate-3` needs the dev
project and devices.

## The 16 findings

"Unit" means fixed and tested against fakes or in pure logic tests. "Docker" means proven on the local Docker stack.
"Open" means not fixed yet.

| # | Finding, in short | State | Test that shows it |
|---|---|---|---|
| 1 | Anyone with the APK could read and write the cloud data | Docker. **The old demo project is still open** until the owner locks it | `0001_lockdown`, `0002_deny_by_default`; `LiveSupabaseIntegrationTest.someoneNotSignedInSeesNothing`, `aStrangerSeesNoneOfAnotherShipment` |
| 2 | Deleting or rewriting the hash chain went unnoticed | Docker and unit | `0003_trust_core` (stale head CC001, gap or re-order CC003, edit CC004); the live test's stale-head and edit checks; `RegressionTest.finding2_*` |
| 3 | Freezing was never a breach | Unit | `RegressionTest.finding3_freezingFailsAFreezeSensitiveProduct` |
| 4 | Two checkboxes on one phone stood in for two signatures | Server half Docker, including through the app's client; app screens unit. No run on two phones yet | `0003_trust_core` "one device cannot confirm for both people"; the live test's borrowed-phone check; `ServerHandoverTest.custodyMovesOnlyOnceBothPeopleHaveConfirmed` |
| 5 | No sensor, simulated readings | Open. Virtual logger written, not in the app (`HARDWARE = none`) | `LoggerVectorsTest` covers the protocol only |
| 6 | Data gaps counted as safe | Unit | `RegressionTest.finding6_dataGapsAreUnknownNotSafe` |
| 7 | A new alert every tick, no escalation | Unit | `RegressionTest.finding7_oneExcursionCreatesOneAlert`; `AlertNotificationsTest.theWorkerEscalatesWhatIsDueNotifiesAndBooksTheNextCheck` |
| 8 | A FAIL handover couldn't be recorded; some statuses never set | Unit | `HandoverDecisionTest.aDeclaredFailureNeedsAReason`; `ShipmentLifecycleTest.everyPairOfStatusesIsEitherLegalOrRejected` |
| 9 | Recording, sync and GPS stop when the screen changes | Open, Phase 5. Only alert checks run in the background (WorkManager) | none |
| 10 | An app update could wipe the database | Unit (real SQLite under Robolectric) | `ColdChainDatabaseMigrationTest.aVersion2ChainKeepsItsReadingsThroughTheMigration` |
| 11 | The receiver re-downloads everything every 3 s | Partly: downloads only new readings, still polls every 3 s. Phase 5 | none for the polling |
| 12 | The QR code was trusted text | Unit | `ShipmentWorkflowTest.aScannedCodeIsNeverTurnedIntoAShipment` |
| 13 | The handover trusted the caller's temperature result | Unit; the argument no longer exists | `HandoverOrchestratorTest.test16_temperatureFailedQuarantinesShipment` |
| 14 | Only one handover per shipment | Partly: the server supports several legs (Docker); the phone still refuses a second one. Phase 5 | `0003_trust_core`; `HandoverDecisionTest.aSecondHandoverForTheSameShipmentIsRefusedNotSilentlyLost` |
| 15 | No ViewModels, global state, demo code in production | Partly: demo code is debug-only; ViewModels are Phase 5 | `ReleaseHasNoDebugToolsTest` |
| 16 | Build pinned to one PC, a test hit a live server, old target SDK, no CI | Unit, except CI never ran on GitHub | `OfflineTestGuardTest`; a clone built with `JAVA_HOME` only (`PROGRESS.md`, Phase 1) |
