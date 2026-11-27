# Status: what is proven, and on what

Updated 27 Nov 2026. This is a prototype. Nothing here makes it fit for real vaccine or medicine stock. No physical
temperature logger has been used at any point (`HARDWARE = none`): every reading so far is simulated on a phone.

"Real system" means a hosted Supabase project, an emulator or phone, or a real database running in Docker.
"Fakes" means tests, fake servers and test vectors that Claude wrote. A fake can agree with the code and still be
wrong about the world: the first real run found a server rule the fake server didn't copy.

## Proven on real systems

- **The hosted dev project (24 Nov):** migrations `0000` to `0003` applied. Its schema is the one the local tests
  ran on: definitions, constraints, policies and the privilege matrix hash the same on both. Every deny-by-default
  rule from the tests, run as a query on hosted (including for newly created objects), finds nothing. Supabase's
  advisors show no errors. Through the real API, a signed-out caller is refused (`permission denied`).
- **Gate 3, a whole handover on the hosted dev project with two real devices at once (24 Nov):** the app built from
  `05b342e`. Email-code sign-in with real emails from the hosted project for all three people. The sender
  (emulator) created and dispatched `SHIP-3E4005`. The courier (the Galaxy S24) took custody and uploaded 6 signed
  readings over 5 minutes, then confirmed. The receiver (emulator) recorded PASS on the same history. The server
  shows two confirmations from two device IDs, custody moved to the receiver, status ACCEPTED. The readings are
  simulated on the courier's phone; no logger was involved.

- **Recording in the background on the S24, against the dev project (26 Nov):** the courier's phone (Galaxy S24,
  Android 17) ran the recording as a foreground service of type `connectedDevice` after the Nearby devices prompt.
  - With the screen off and the phone in deep Doze (`mDeviceIdleMode=true`), readings reached the server 1 to 5 s
    after they were made, and the courier's handover confirmation renewed itself on each new head. This needed a fix
    found on the phone: the Supabase library dropped the sign-in from memory whenever the app left the screen, so
    before the fix nothing was sent while the screen was off.
  - Before that fix, 28 readings recorded once a minute through 28 minutes of screen-off and deep Doze stayed on the
    phone and reached the server in order, contiguous, once the app came back to the screen.
  - Overnight, 26 to 27 Nov: 582 readings made in nearly 10 hours of screen-off deep Doze all reached the server,
    at most 8 s late (battery history and server times, PROGRESS 27 Nov).

- **The S24 again, 27 Nov, `SHIP-1D87CC`, the build with GPS in the recording service:** the service ran as
  `connectedDevice|location`; the first GPS points on the dev project reached it 1 to 2 s after each fix. `am kill`
  left it alone; a real `kill -9` was restarted by Android in 4 s, location included, with no gap in the readings.
  **74 minutes offline** (airplane mode, deep Doze reached): the 72 readings made offline, a temperature alert
  raised offline and a GPS point recorded offline all reached the server in order on reconnecting. GPS kept arriving
  every minute with the screen off and the network on (28 minutes; not in Doze). Room 4 → 5 kept all 797 readings of
  the previous shipment on the phone. The receiver (emulator) recorded FAIL; the shipment was QUARANTINED, custody
  moved, and the S24 stopped recording by itself. No coordinates are kept anywhere: they are the owner's location.
- **The signed release build under R8, and multi-leg custody on devices (27 Nov, `SHIP-2DE57A`):** the release
  APK (R8 full mode, resource shrinking, the upload key) found one missing keep rule on its first start on the S24
  (ML Kit's registrars), fixed and guarded by a build check. Then, on the dev project: courier A (S24, debug build)
  took custody and recorded 11 readings; courier B (emulator, **release build**) took over and recorded 6 more on the
  same chain; the receiver (S24, **release build**) recorded PASS. Custody START, TRANSFER, TRANSFER; ACCEPTED; each
  handover's two confirmations on the same head from two physical devices; each courier's phone stopped recording by
  itself when custody moved, and B's phone showed ACCEPTED. Sign-up, sign-in, uploads, GPS, the scanner and
  confirmations all ran under R8 with no class or serialization error in the logs.
- **Permissions on devices (27 Nov, `SHIP-CB7F34`):** a courier who refuses Nearby devices and location twice still
  takes custody, is offered the prompt again from Transit, then Settings, which opens. A courier taking over on
  Handover (the S24, permissions revoked) was asked for both before their confirmation was sent, and then recorded
  in the foreground with location.
- **Finding 11 on the dev project (edge logs):** a phone watching a shipment went from ~89 requests a minute to ~12
  while readings arrive and ~3.6 while nothing changes; the courier waiting for the receiver from ~48 to ~17, with
  one confirmation per new reading instead of one every 5 s.

The rest ran on the local Docker stack (Supabase's own Postgres 17, GoTrue, PostgREST and Kong images), not a
hosted project.

- **Database rules:** `npx supabase test db`, 238 of 238 checks pass. `db advisors --local`: no issues. They cover:
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
  - It signs up with passwords; email-code sign-in is proven through the app's screens instead (Gate 3, above).

On a phone and an emulator: a Galaxy S24 (SM-S921E, Android 17) and a Pixel 7 emulator (Android 16).

- **The phone's signing key (23 Nov):** `KeystoreDeviceSignerTest`, 3 of 3 on each. The key is made in the Android
  Keystore, stays the same between uses, and its signatures verify with the public key the server is given.
- **Screens on the S24 (23 and 24 Nov):** the app starts in 1.1 to 1.2 s with no stall. Sign-in shows clear of the
  status bar; a typed email survives rotation, going to the background, and the process being killed with the
  return from Recents. Debug local mode creates, dispatches and records. Killed while on Handover or Alerts, the app
  comes back on the same shipment with the inspection answers still selected (fixed on 24 Nov; before, it came back
  empty). No crash or ANR in the logs. Read from the layout dump; no phone screenshots kept, because the phone
  showed personal data.
- **Screens on the emulator (23 and 24 Nov):** the same sign-in checks; local mode records through rotation and a
  kill; the alert worker is built by Hilt's factory and runs. Screenshots in `docs/agent/evidence/`.
- **A whole handover through the app's own screens against the local Docker stack (23 Nov),** one emulator playing
  each person in turn after clearing its storage: email-code sign-in (codes from the local mail catcher), create
  and dispatch, take custody, signed readings, both confirmations from two device IDs, custody moved, ACCEPTED. A
  second run showed a receiver past the 3-minute window gets UNKNOWN, as it should.

## Proven only against our own fakes and vectors

- **Full unit and screen tests:** counts in `PROGRESS.md`.
- **Offline behaviour, retries and timing:** against a fake server that copies the real one's refusal rules.
- **Safety rules:** heat, freeze, gaps, one alert per excursion, escalation timing. These are pure logic tests; the
  timers ran in WorkManager's test harness, not in real time.
- **Screen behaviour in depth:** every state of the Dispatch, Transit, Handover and Alerts screens is driven by
  Robolectric on the PC's JVM; the devices above checked launch, rotation, process death and the handover flow.
- **Hash chain:** Python, Postgres and Kotlin agree on 18 shared test cases.
- **Logger protocol** (signed off by the owner on 26 Nov): Kotlin agrees with a separate Python version on the
  vectors. Both were written by Claude; one signature is checked against an outside source (RFC 6979).
- **Logger test key refused:** in unit tests for both builds, and neither built APK contains its private key.
- **Reading a logger over Bluetooth:** the protocol side runs against a fake Bluetooth server and the Python
  vectors, including lost and silent notifications, a forged header claiming a huge range, extra records and stray
  callbacks. The Android Bluetooth code compiles but has never talked to a logger, because there isn't one.
- **Checking the logger at handover:** in tests, a history that was edited, cut short, replayed, signed with the
  wrong or the test key, or doesn't match the readings fails the handover; one with holes or faults is UNKNOWN.
  Not on a screen yet.
- **Firmware library:** written by Claude on 26 Nov (`FIRMWARE_OWNER = claude`) against the existing tests; all 39
  host tests pass with MSYS2 g++, including the Bluetooth bytes, so the C++, the Python generator and the app's Kotlin
  agree on `logger_vectors.json` byte for byte. Signing uses mbedTLS 3.6.8. It has never run on a logger board.

## Not proven

- An alert's notification seen on a device's screen. The recording notification and the notifications prompt were
  seen on the S24 and the emulator, and on the S24 an alert raised offline escalated on WorkManager's real timer
  (27 Nov), but nobody checked the alert's own notification there.
- GPS in deep Doze. GPS with the screen off and the network on is proven (above).
- A real logger. None will be used (`HARDWARE = none`); the virtual logger exists in code but the app doesn't use
  it yet.

## Gates

`gate-0`, `gate-1` (24 Nov, on the dev project's own advisors and a fresh clone's build), `gate-2` and `gate-3`
(24 Nov, the two-device handover on the dev project) are tagged. Gate 5 (Phase 5) and Gate 4 (Phase 4 with the
firmware, closing as "met without hardware") are next.

## The 16 findings

"Unit" means fixed and tested against fakes or in pure logic tests. "Docker" means proven on the local Docker stack.
"Hosted" means proven on the dev project. "Open" means not fixed yet.

| # | Finding, in short | State | Test that shows it |
|---|---|---|---|
| 1 | Anyone with the APK could read and write the cloud data | Closed. Docker and hosted for the new schema. The old demo project is deleted: the owner found it is no longer in his Supabase account and its URL no longer resolves (26 Nov). Claude never contacted it, by rule | `0001_lockdown`, `0002_deny_by_default`; `LiveSupabaseIntegrationTest.someoneNotSignedInSeesNothing`, `aStrangerSeesNoneOfAnotherShipment`; the hosted deny-by-default queries (Gate 1) |
| 2 | Deleting or rewriting the hash chain went unnoticed | Docker and unit | `0003_trust_core` (stale head CC001, gap or re-order CC003, edit CC004); the live test's stale-head and edit checks; `RegressionTest.finding2_*` |
| 3 | Freezing was never a breach | Unit | `RegressionTest.finding3_freezingFailsAFreezeSensitiveProduct` |
| 4 | Two checkboxes on one phone stood in for two signatures | Closed. Docker, and hosted with two real devices (Gate 3: two device IDs, custody moved, ACCEPTED) | `0003_trust_core` "one device cannot confirm for both people"; the live test's borrowed-phone check; `ServerHandoverTest.custodyMovesOnlyOnceBothPeopleHaveConfirmed`; `SHIP-3E4005` on the dev project |
| 5 | No sensor, simulated readings | Open. Virtual logger written, not in the app; no hardware (`HARDWARE = none`) | `LoggerVectorsTest` covers the protocol only |
| 6 | Data gaps counted as safe | Unit | `RegressionTest.finding6_dataGapsAreUnknownNotSafe` |
| 7 | A new alert every tick, no escalation | Unit | `RegressionTest.finding7_oneExcursionCreatesOneAlert`; `AlertNotificationsTest.theWorkerEscalatesWhatIsDueNotifiesAndBooksTheNextCheck` |
| 8 | A FAIL handover couldn't be recorded; some statuses never set | Unit | `HandoverDecisionTest.aDeclaredFailureNeedsAReason`; `ShipmentLifecycleTest.everyPairOfStatusesIsEitherLegalOrRejected` |
| 9 | Recording, sync and GPS stop when the screen changes | Closed. Recording, sync and GPS run in the recording service; proven on the S24 against the dev project: screen off, deep Doze, a real kill, 74 minutes offline (26 and 27 Nov). GPS in deep Doze is not proven | `RecordingLoopTest`, `RecordingServiceTest`, `LocationRecorderTest`, `SyncServiceTest`, `SyncWorkerTest`, `SupabaseClientProviderTest.theSessionStaysWhenTheAppGoesToTheBackground`; `SHIP-49A74F`, `SHIP-1D87CC` on the dev project |
| 10 | An app update could wipe the database | Unit (real SQLite under Robolectric), and on the S24: version 4 → 5 kept all 797 readings (27 Nov) | `ColdChainDatabaseMigrationTest.aVersion2ChainKeepsItsReadingsThroughTheMigration` |
| 11 | The receiver re-downloads everything every 3 s | Closed. Only new readings, asked for only when the head moved; watchers back off from 3 s to 30 s; the courier re-confirms only after a new reading. Measured on the dev project (27 Nov) | `PollBackoffTest`, `ShipmentHistorySyncCoordinatorTest.aFetchWithNothingNewAsksOnlyForTheHead`, `TransitViewModelTest.whileWatchingAndNothingChangesTheServerIsAskedLessAndLess`, `RecordingLoopTest.whileWaitingWithNothingNewRecordedTheCourierDoesNotConfirmAgain` |
| 12 | The QR code was trusted text | Unit | `ShipmentWorkflowTest.aScannedCodeIsNeverTurnedIntoAShipment` |
| 13 | The handover trusted the caller's temperature result | Unit; the argument no longer exists | `HandoverOrchestratorTest.test16_temperatureFailedQuarantinesShipment` |
| 14 | Only one handover per shipment | Closed. On fakes and Docker, and on devices against the dev project: `SHIP-2DE57A` went courier → courier → receiver, the second courier recording from taking over (27 Nov) | `MultiLegHandoverTest`, `LiveSupabaseIntegrationTest.aShipmentGoesThroughTwoCouriersBeforeTheReceiver`, `HandoverViewModelTest.aCourierTakingOverFromAnotherCourierStartsRecordingOnTheirPhone` |
| 15 | No ViewModels, global state, demo code in production | Closed in code: a ViewModel per screen, Hilt builds everything, `RepositoryProvider` deleted, role-based tabs (24 Nov); demo code is debug-only. Process death checked on the S24 | `ReleaseHasNoDebugToolsTest`; `AppRootViewModelTest`, `DispatchViewModelTest`, `TransitViewModelTest`, `HandoverViewModelTest`, `AlertsViewModelTest`, `ScreenTabsTest` |
| 16 | Build pinned to one PC, a test hit a live server, old target SDK, no CI | Closed. A clean clone builds (Gate 1), and CI runs on GitHub: unit tests, lint, release build, database tests and the firmware tests (first green run 26 Nov) | `OfflineTestGuardTest`; the clean-clone build (`PROGRESS.md`, Gate 1); https://github.com/ColdChainHandshake/ColdChainHandshake/actions/runs/45842949385 |
