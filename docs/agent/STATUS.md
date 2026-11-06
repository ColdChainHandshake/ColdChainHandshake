# Status: what is proven, and on what

Updated 6 Nov 2026, at commit `cfb2e85`. This is a prototype. Nothing here makes it fit for real vaccine or medicine
stock.

"Real system" means a hosted Supabase project, an emulator or phone, or a real database running in Docker.
"Fakes" means tests, fake servers and test vectors that Claude wrote. A fake can agree with the code and still be
wrong about the world.

## Proven on real systems

- **Database rules, on a real Postgres in local Docker** (Supabase's own Postgres 17 image, not a hosted project).
  `npx supabase db reset` then `npx supabase test db`: 233 of 233 checks pass, 6 Nov. `db advisors --local`: no
  issues. These checks cover: strangers locked out, readings can't be edited or deleted, a dropped, edited,
  re-ordered or back-dated reading is refused, only the custodian can upload, one phone can't confirm for both people.

## Proven only against our own fakes and vectors

- **Full unit and screen test run:** see the counts in `PROGRESS.md` under "Status at session start".
- **The app talking to the server:** checked against a fake server that copies the real one's refusal rules, and a
  contract test that matches every call against the SQL.
- **Sign-in by email code:** tested against a fake sign-in server only.
- **Safety rules:** heat, freeze, gaps, one alert per excursion, escalation timing. These are pure logic tests, and
  the timers ran in WorkManager's test harness, not in real time.
- **Screens:** driven by Robolectric, which runs Android on the PC's JVM. No screen has been seen on a phone.
- **Hash chain:** Python, Postgres and Kotlin agree on 18 shared test cases.
- **Logger protocol:** Kotlin agrees with a separate Python version on the test vectors. Both were written by Claude.
  Only one signature is checked against an outside source (RFC 6979).

## Not proven

- Anything on a hosted Supabase project. No migration has reached one.
- Anything on an emulator or phone: sign-in, the phone's signing key, notifications, real timers, and the handover
  with each person on their own phone.
- Recording and sync with the screen off, after the app is killed, or after a long time offline.
- A real logger. The virtual logger exists in code but the app doesn't use it yet; the app still uses the simulator.
- The GitHub Actions workflow has never run on GitHub.
- Gate 2 is waiting for a fresh review. Gate 3 needs the dev project and two devices.

## The 16 findings at `cfb2e85`

"Unit" means fixed and tested against fakes or in pure logic tests. "Docker" means proven on the local Docker
database. "Open" means not fixed yet.

| # | Finding, in short | State | Test that shows it |
|---|---|---|---|
| 1 | Anyone with the APK could read and write the cloud data | Docker (new schema). **The old demo project is still open** until the owner locks it | `0001_lockdown`, `0002_deny_by_default` pgTAP files |
| 2 | Deleting or rewriting the hash chain went unnoticed | Docker and unit | `0003_trust_core` (CC001 stale head, CC003 gap or re-order, CC004 edit); `RegressionTest.finding2_*` |
| 3 | Freezing was never a breach | Unit | `RegressionTest.finding3_freezingFailsAFreezeSensitiveProduct` |
| 4 | Two checkboxes on one phone stood in for two signatures | Server half Docker, app half unit. No run on two phones yet | `0003_trust_core` "one device cannot confirm for both people"; `ServerHandoverTest.custodyMovesOnlyOnceBothPeopleHaveConfirmed` |
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
