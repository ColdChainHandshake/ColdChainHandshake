# Gate 5 review: `rebuild/trust-core` at `baf5a71`

Reviewer: a fresh agent that had not seen the work, one sitting, 28 Nov 2026. Task: try to prove Gate 5 is not met,
mutate the logic changed since `gate-3`, and review the security of `git diff gate-3..HEAD` (116 files, +7,434 / -1,695).
No Supabase project was contacted. No device or emulator was used, and no `adb` command was run. No key file,
`local.properties`, `.env` or anything under `D:\cch-keys` was read. Gradle ran only targeted test classes, one
command at a time, then `./gradlew --stop`. Library behaviour was read from supabase-kt 3.8.0's own bytecode
(`javap` on `auth-kt.aar` from the Gradle cache) and its docs through Context7 (`/supabase/supabase-kt/3.8.0`).

**Verdict: GATE 5 NOT MET, on "process death".** The app must survive process death without losing readings.
A process death during ordinary offline courier use stops recording, by code reading.

- After the restart, the stored session can't be loaded without a network once the access token is in the last 20 %
  of its life. The account then stays Loading.
- `RecordingLoop.restore` gives up after 30 s, and the service stops itself.
- No reading is made until the network returns, and meanwhile the app shows only its loading screen (S1, gate-blocking).
- This was never run on a device or in a test. The kill -9 on the S24 was online, and the 74-minute offline run had
  no kill. A mutant that makes `restore` carry on survives every test (mutant 14).

**What would overturn this:** on a device, a kill -9 after more than 50 minutes in airplane mode, followed by
recording resuming with no signal (readings appearing in the phone's Room database).

Every other part of the criterion is MET or MET WITH RESERVATION (section 1).

## 1. Gate 5 criterion and Phase 5 items 1 to 6

### Gate 5: "survives rotation, backgrounding, airplane mode for 30 minutes and process death without losing readings or alerts (instrumented or scripted checks); lint clean or justified"

| Part | Verdict | Evidence | Only against fakes, or not shown |
|---|---|---|---|
| Rotation | MET WITH RESERVATION | S24 at `87d130e` (24 Nov, the gate-3 commit): a typed email survives landscape and back (PROGRESS ~l.770). Emulator, 23 Nov: local mode keeps recording through rotation (PROGRESS ~l.894). `AlertEscalationColdStartTest.rotatingTheActivityDoesNotStartASecondSetOfBackgroundJobs`. ViewModel tests with `SavedStateHandle` | **No rotation on any build since `gate-3`.** Since then recording moved into `RecordingService`, and Handover gained `shownHistory` and polling. Nothing was rotated while a server shipment recorded. There is no instrumented or Robolectric `recreate()` test of a screen |
| Backgrounding | MET | S24, screen off and deep Doze: readings 50 to 58 arrived 1 to 5 s late (build `229cefc`, 26 Nov). Overnight: 582 readings in about 10 h, at most 8 s late (PROGRESS, 27 Nov, `select … max_delay_s 8`). GPS with the screen off (`f098dcc`) | The soak ran on `229cefc` and `f098dcc`. HEAD changed `RecordingLoop` again after that (`36d0bf0`, status refresh) and was not soaked. GPS in deep Doze is unproven, and STATUS says so |
| Airplane mode for 30 minutes | MET | `SHIP-1D87CC`, 74 min offline: the 72 readings, the offline TEMPERATURE_BREACH alert (escalated offline) and the offline GPS point all reached the server in order (PROGRESS, 27 Nov). `SyncServiceTest`, `SyncWorkerTest` | Shown only with the process alive throughout |
| Process death | **NOT MET when offline; MET online** | Online: `am kill` was survived, and a real `kill -9` was restarted in 4 s, location included, with no gap (`f098dcc`, 12:15:50). UI process death: back on the same shipment with its inspection answers, S24 local mode `SHIP-D77515` (after `1056658`). Tests: `RecordingLoopTest.afterARestart…` (5), `RecordingServiceTest.aRestartPicksUpTheShipmentThatWasRecording` | **Offline process death fails by code reading (S1)**, and nothing tests it (mutant 14 survived). The restart under test is always online with the account already Ready |
| Without losing alerts | MET WITH RESERVATION | The alert raised offline reached the server, already escalated. `AlertEscalationColdStartTest`, `AlertNotificationsTest` | STATUS: an alert's own notification has never been seen on a device. Timers ran in WorkManager's test harness |
| Instrumented or scripted | RESERVATION | The device runs were driven by hand over `adb` (with `tools/device/drive.sh` helpers), and PROGRESS records the evidence | The only instrumented test is `KeystoreDeviceSignerTest`. None of the gate's conditions has a re-runnable script or instrumented test |
| Lint clean or justified | MET | PROGRESS at `67b034e`: `lintDebug` 0 errors, 12 warnings. Each warning is justified in `audit_5_5.md` (UseKtx ×10, AGP version, `mipmap-anydpi-v26`). The justifications are reasonable | Not re-run in this review (targeted runs only, as instructed) |

### Phase 5 items (MASTER 5.5)

| Item | Verdict | Evidence and gaps |
|---|---|---|
| 1. ViewModels, Hilt, no `RepositoryProvider`, role-based navigation, no Phone A/B or hash jargon | MET WITH RESERVATION | `RepositoryProvider.kt` and `LegacyProviderModule.kt` are deleted (no reference left in `app/src` or `core-domain/src`). `di/AppModule.kt` is Hilt. `ScreenTabsTest`, `AppRootViewModelTest`. No "Phone A/B", "SHA" or "hash" in any screen string. **Left on screens:** "INTEGRITY VERIFICATION", "✓ READINGS VERIFIED … form one unbroken chain and match the server" and "✗ TAMPER DETECTED" (`ShipmentIntegrityEvaluator.kt:118-142`, `HandoverScreen.kt:567,797`). These are chain jargon, and "verified" over simulated readings can read as more than it is. "Phone A/B" survives only in code comments (`TransitScreen.kt:118,124,496`, `TemperatureSimulator.kt:97`) |
| 2. Foreground service `connectedDevice\|location`, WorkManager sync, GPS queued in Room, incremental download for watchers | MET WITH RESERVATION | Manifest `RecordingService` has `exported=false` and `connectedDevice\|location`. `SyncWorker` is unique work with a network constraint. `GpsPointEntity`/`GpsPointDao`, Room v5 migration. `PollBackoff` and `hydrateHistory`'s `upToDate`. On the S24: `0x18`, GPS reaching the server, about 3.6 requests a minute while idle. Reservations: S1 (restart offline), S7 (unsent GPS dropped at sign-out), and mutant 19 survived (`upToDate` without its hash compare) |
| 3. Multi-leg custody in the UI | MET | "Courier emails (commas between)". `HandoverViewModel.record` and `TransitViewModel.takeOverRecording` start recording on courier B. Devices: `SHIP-2DE57A` and `SHIP-CB7F34` went START, TRANSFER, TRANSFER, then ACCEPTED, each phone stopping by itself. `MultiLegHandoverTest`, the live multi-leg test on the local stack. Reservations: the take-over role guards are untested (mutants 20 and 23 survived), and the role is read from the profile, not the shipment (S6) |
| 4. Opaque QR | MET | `TransitViewModel.onCodeScanned:210-223` and `ShipmentWorkflow.open:74-95` look the code up as an ID, and the server's RLS decides. Nothing is parsed out of the text. `ShipmentWorkflowTest.aScannedCodeIsNeverTurnedIntoAShipment`. Mutant 18 was killed |
| 5. Android audits | MET WITH RESERVATION | `audit_5_5.md`: intent security, Play policy, R8, lint. I re-checked one claim by grep: no intent extras are read in `app/src/main` (confirmed). The permission, ANR and Compose audits are recorded in PROGRESS (`4793425`). Edge-to-edge and light/dark were checked on the emulator. The unexplained release-build ANR on the emulator (PROGRESS 27 Nov, 22:58) is recorded in PROGRESS but not in STATUS |
| 6. Release build: R8, signing from the environment, version scheme | MET | `app/build.gradle`: `minifyEnabled`/`shrinkResources`. Signing comes from the properties file that `CCH_RELEASE_KEYS` names, and without it the build is unsigned. `versionCode 500`/`0.5.0`. `checkReleaseKeepRules`. `ReleaseHasNoDebugToolsTest`. Release under R8 on both devices (`SHIP-2DE57A`). Note that `ReleaseHasNoDebugToolsTest` checks the unminified JVM classes, not the APK |

## 2. Mutation check (24 mutants, each reverted with Edit)

A mutant counts as killed only when an assertion fails in a test that targets the changed behaviour. One compile
error (the first attempt at mutant 14) is not counted; that mutant was rewritten. Mutants were batched only across
different files whose test classes could be told apart. In one batch, mutants 16 and 17 failed the same
`RecordingLoopTest` tests. Mutant 17 was then re-run without mutant 16 and was killed again; the other mutants in
that second batch (19, 20, 21) were in files that `RecordingLoopTest` doesn't use.

| # | File | Mutation | Result | Killed by |
|---|---|---|---|---|
| 1 | `core-domain/.../handover/TripWindow.kt:16` | drop `handedOverAt <= now` (trust a server time in the future) | killed | `TripWindowTest.aTimeFromTheServerAfterNowIsNotTrusted` |
| 2 | `TripWindow.kt:16` | `== RECEIVER` → `!= null` (end the window at a courier's handover too) | killed | `TripWindowTest.whileACourierHoldsItTheRecordRunsUpToNow` |
| 3 | `core-domain/.../repository/PollBackoff.kt:17` | no 30 s cap | killed | `PollBackoffTest.eachQuietRoundDoublesTheWaitUpToTheMaximum` |
| 4 | `PollBackoff.kt:17` | a change keeps the long wait | killed | `PollBackoffTest.aChangeBringsTheWaitBackToTheMinimum` |
| 5 | `core-domain/.../safety/LoggerSilenceCheck.kt:44` | `windowEnd` always `now` | killed | `LoggerSilenceCheckTest.aShipmentThisPhoneOnlyWatchesRaisesNoGapForReadingsNotYetDownloaded`, `…StillRaisesWhatItsDownloadedReadingsShow` |
| 6 | `core-domain/.../handover/HandoverOrchestrator.kt:193` | the receiver's verdict window ends at the newest reading instead of at the confirmation (the early-confirm attack) | killed | `HandoverWindowTest.aCourierWhoConfirmsEarlyAndStopsRecordingCannotGetAPass` ("a PASS can't be recorded over a gap") |
| 7 | `core-domain/.../handover/ServerHandover.kt:86` | `renewRelease`'s fast path never sees custody reach the named person | killed | `HandoverWindowTest.theCourierStopsRenewingOnceCustodyHasMoved`; `RecordingLoopTest.whenTheReceiverConfirmsWhileTheCourierWaitsRecordingStopsAtOnce`, `whenTheReceiverPassesWhileTheCourierWaits…` |
| 8 | `app/.../ui/components/TemperatureCard.kt:49` | `watcherNote` `<=` → `<` | killed | `TemperatureCardTest.aWatcherIsToldWhenNothingNewerHasReachedTheServer` |
| 9 | `app/.../ui/components/RecordingPermissions.kt:27` | Nearby devices asked from API 32, not 31 | **survived** | No test uses API 31 itself. Not safety logic |
| 10 | `ServerHandover.kt:150` (`custodyMovedOn`) | ignore "custody is with the person named" (`return !open`) | **survived** | Since finding 11's fast path (`1a4691c`), no test reaches this branch. PROGRESS 26 Nov says a mutant like this was killed; that was before the fast path (claim C4). In the app, `RecordingLoop`'s once-a-minute custody check still stops recording, so the effect is a delay of up to a minute plus failed renewals |
| 11 | `app/.../data/remote/SupabaseAccountRepository.kt:117` | `completeProfile` back to `onSuccess { arrive() }` | killed | `SupabaseAccountRepositoryTest.completingAProfileRegistersThePhoneEvenWhenTheFormLeavesAtOnce` |
| 12 | `app/.../repository/SyncServiceImpl.kt` (`syncLocations`) | a refused point is marked FAILED and retried | killed | `SyncServiceTest.aLocationTheServerRefusesIsSetAsideNotRetried` |
| 13 | `ServerHandover.kt:236` | a PASS with no connection is queued offline instead of refused | killed | `ServerHandoverTest.aPassIsNeverStoredBeforeTheServerAgrees` |
| 14 | `app/.../recording/RecordingLoop.kt:62` | when the account never settles, carry on recording instead of `return false` | **survived** | Nothing tests a restart whose account stays Loading. This is S1's code path |
| 15 | `app/.../ui/screens/HandoverViewModel.kt:151` | `shownHistory` `<` → `<=` | **survived** | A pair already in step is then shown without its head for 2 s each time (integrity "not yet compared"). Display only; no test checks that an in-step pair shows at once |
| 16 | `ServerHandover.kt:167` | drop the STALE_HEAD retry in `receive` | killed | `HandoverWindowTest.aReadingThatLandsWhileTheReceiverConfirmsIsCheckedAndConfirmedAgain` |
| 17 | `RecordingLoop.kt:108` | ignore "the waiting handover is gone" | killed (re-run without mutant 16) | `RecordingLoopTest.whenTheReceiverConfirmsWhileTheCourierWaitsRecordingStopsAtOnce`, `whenTheReceiverPassesWhileTheCourierWaits…` |
| 18 | `TransitViewModel.kt:213` (`onCodeScanned`) | a shipment held here is not asked of the server | killed | `TransitViewModelTest.openingAShipmentThisPhoneHoldsShowsTheServersStatusFromTheStart` |
| 19 | `core-domain/.../repository/ShipmentHistorySyncCoordinator.kt:91` | `upToDate` without the hash compare | **survived** | No test holds a same-length, different-hash history. Integrity is still checked separately by `ShipmentIntegrityEvaluator`, so probably no safety effect. A test gap |
| 20 | `HandoverViewModel.kt:318-319` | drop the COURIER role check before take-over recording | **survived** | `aReceiverTakingOverRecordsNothing` uses PASS (ACCEPTED, not on the move), so only the status guard is exercised. A receiver's FAIL makes the shipment QUARANTINED, which *is* in `ON_THE_MOVE`. Without the role check the receiver's phone would start recording. A test gap in safety-adjacent code |
| 21 | `app/.../recording/RecordingService.kt:123` | no `connectedDevice`-only fallback | killed | `RecordingServiceTest.whenAndroidRefusesTheLocationTypeItKeepsRecordingWithoutIt` (`expected:<16> but was:<0>`) |
| 22 | `LoggerSilenceCheck.kt:47` | a watched shipment books a silence check | killed | `LoggerSilenceCheckTest.aShipmentThisPhoneOnlyWatchesRaisesNoGapForReadingsNotYetDownloaded` |
| 23 | `TransitViewModel.kt:293` (`takeOverRecording`) | drop the COURIER role check | **survived** | `TransitViewModelTest` has no receiver whose watched shipment comes to them while still on the move |
| 24 | `SupabaseAccountRepository.kt:60` | `RefreshFailure` → SignedOut | **survived** | Nothing tests "unable to refresh is not signed out", although `RecordingLoop.restore` forgets the recording for a SignedOut account. Safety-relevant (it guards S1's neighbour) |

Totals: 24 mutants, 16 killed, 8 survived (9, 10, 14, 15, 19, 20, 23, 24). The survivors in safety logic are 10, 14,
20, 23 and 24. **Mutant 14 shows S1 is untested; mutant 24 guards the same restart path.**

Clean tree after the reverts. After each revert, `git diff --stat` listed only `docs/agent/HANDOFF.md`. At the end,
`git diff HEAD --stat -- core-domain app` is empty, and `git status --short` shows only ` M docs/agent/HANDOFF.md`
and `?? docs/agent/gate5_review.md`. **`HANDOFF.md` was not changed by this review.** It already differed from HEAD
when the first mutant was checked: its mtime is 28 Nov 00:01:46, and the diff rewrites "Updated on 27 Nov" to 28 Nov
and replaces section 0 with "State of your devices and accounts". Someone else wrote it before or during this
session, so it was left alone.

## 3. Security findings, `gate-3..HEAD`

Status: CONFIRMED means the code path was read end to end. PLAUSIBLE means it depends on runtime behaviour this review
did not execute.

### S1. Process death while offline stops recording until the network returns, and the app shows only "Loading" (Medium, CONFIRMED by reading)

- **Where:**
  - `app/.../data/remote/SupabaseAccountRepository.kt:48-62`: the state starts Loading; `Initializing` maps to Loading; `RefreshFailure` maps to `Unit`.
  - `app/.../recording/RecordingLoop.kt:60-63`: `withTimeoutOrNull(30 s) … ?: return false`.
  - `app/.../recording/RecordingService.kt:80-83`: `restore()` false leads to `finish()` and `stopSelf()`.
  - `app/.../ui/navigation/AppRootViewModel.kt:110`: Loading shows `RootScreen.Loading`.
- **Library behaviour, read from supabase-kt 3.8.0 bytecode (`AuthImpl.importSession`):**
  - With `autoRefresh = true`, a stored session whose `expiresAt − 0.2 × expiresIn` has passed is refreshed *before* `Authenticated` is set. The log line there is "Session is under the threshold date. Refreshing session...".
  - On a network failure, `updateStatusIfExpired` sets `RefreshFailure` only if the token is already expired. Otherwise the status stays `Initializing`.
  - The library retries, and "during that time, the session is obviously not usable" (MIGRATION.md, 3.8.0).
  - Either way the app's state stays Loading.
- **Scenario:**
  1. A courier records. The local `supabase/config.toml` has `jwt_expiry = 3600`; the dev project's setting wasn't checked.
  2. While online, supabase-kt refreshes the token at 80 % of its life (`delayBeforeExpiry`: issued + 0.8 × `expiresIn`, from the bytecode). So the token is at most 48 minutes old.
  3. They lose signal: a tunnel, a cold store, or airplane mode.
  4. While offline, Android kills the process (memory pressure, an OEM task killer, an update), or the person swipes the app away.
  5. The restart fails whenever the token's age plus the time offline has reached about 48 minutes of a 1-hour token. Within the gate's 30-minute window, that is whenever the token was more than 18 minutes old when signal was lost: about 60 % of the time if the token's age then is evenly spread.
  6. The restarted service waits 30 s, gives up and stops.
  7. Opening the app shows only the loading screen. `onAppOpened` restarts the service, which times out again, and the restorer waits for `RootScreen.Main`.
  8. No reading is made until signal returns. The receiver then gets UNKNOWN for a gap.
  9. It fails safe (no false PASS), but it loses readings, which the gate forbids. Medium, and gate-blocking.
- **Inconsistency:** DECISIONS (26 Nov) says that after a restart, with no answer about custody, the phone carries on. With no answer about the *account* it stops.
- **Not tested:** mutant 14 survived.
- **Fix:**
  - Keep the signed-in user's ID with `RecordingStore` (or use `ProfileCache`).
  - In `restore`, a timeout while a session is stored counts as "carry on recording locally", as with custody.
  - Map `Initializing` or `RefreshFailure` with a stored session to an offline Ready (from the profile cache), so the courier can still open the shipment.
  - Add `RecordingLoopTest.afterARestartOfflineWithTheAccountStillLoadingRecordingResumes`.
  - Add `SupabaseAccountRepositoryTest`: a refresh failure is not a sign-out (this kills mutant 24).
  - On a device: kill -9 after more than 50 minutes in airplane mode.

### S2. A courier with a modified app can confirm early, upload forward-dated readings and stop, and still get a PASS (Low, CONFIRMED by reading; predates gate-3)

- **Where:** `supabase/migrations/0002_trust_core.sql:576-588` and `core-domain/.../safety/BreachDetector.kt:61-74`.
  - `append_readings` accepts a reading dated in the future and only sets `clock_ahead`.
  - `ClockSkewDetector` shows AHEAD_OF_SERVER on Handover.
  - `BreachDetector` turns a reading into UNKNOWN only when it is dated more than 5 minutes after the window ends.
  - Nothing in the verdict uses `clock_ahead` or `received_at`.
- **Scenario:**
  1. After `release`, the courier uploads readings stamped one a minute up to the time they expect the receiver to confirm.
  2. Then the courier stops.
  3. The receiver, confirming inside that window, sees no gap. The readings are flagged on screen, but the verdict is PASS.
- **Why Low:** `HandoverWindowTest` proves the window fix only for the unmodified app. While readings are simulated on the courier's own phone (`HARDWARE = none`), the courier can fabricate any reading anyway. With a signed logger this route needs the logger's key.
- **Fix:** give no credit for time past a reading's `received_at + tolerance`, or make any AHEAD_OF_SERVER reading in the window give UNKNOWN. Add a test to `HandoverWindowTest`.

### S3. The Handover screen's verdict is computed with a frozen "now" (Low, CONFIRMED; predates gate-3, made more visible by finding 11)

- **Where:** `app/.../ui/screens/HandoverScreen.kt:140-146`. `breachEvaluation` is `remember`ed on (readings, shipment, custody, role), and `System.currentTimeMillis()` is read only when a key changes.
- **Scenario:**
  1. The courier's phone goes silent.
  2. No new reading or custody change arrives, and with `PollBackoff` and the `upToDate` path nothing re-emits.
  3. The screen keeps "FINAL VERDICT: PASS / Eligible for Acceptance" (`HandoverScreen.kt:789-795`) for longer than the product allows.
  4. The recorded verdict is recomputed at confirmation (`ServerHandover.receiveOnce:179`), so nothing wrong is stored. But the screen states a verdict that its current data doesn't support, and the receiver's PASS is then refused.
- **Fix:** add a ticking key, for example `produceState` every 15 to 30 s while a courier holds the shipment, or compute the verdict in the ViewModel with a clock.

### S4. Transit tells a watcher of a *finished* trip that "the courier's phone may be offline" (Low, CONFIRMED)

- **Where:** `app/.../ui/screens/TransitScreen.kt:818-826` and `TemperatureCard.watcherNote`. Neither looks at status or custody.
- **Scenario:** `SHIP-2DE57A` (ACCEPTED), opened on Transit two hours later, shows "No reading made after 21:27 had reached the server when this phone checked at 23:30 (123 min). The courier's phone may be offline; it sends what it recorded when it reconnects." This is the same class of error that `67b034e` fixed on Handover, left on Transit. The screen states a status that the data contradicts.
- **Fix:** no note once a receiver holds the shipment or its status isn't IN_TRANSIT/QUARANTINED with a courier custodian. Use `TripWindow`. Add a test to `TemperatureCardTest` or `TransitScreenTest`.

### S4b. "No alerts recorded" on a receiver's or watcher's phone counts only this phone's alerts (Low, CONFIRMED)

- **Where:** Handover's "TRIP ALERT HISTORY ✓ No alerts recorded during trip" (`HandoverScreen.kt`, the `tripAlerts`
  card) and Alerts' "NO ALERTS RECORDED FOR THIS SHIPMENT". Both read only this phone's Room alerts, and
  `ColdChainRemote` has no call that fetches alerts (its method list: `recordAlert`, `escalateAlert`, `acknowledgeAlert`, no read).
- **Scenario:** the courier's phone raises a TEMPERATURE_BREACH or DATA_GAP alert and escalates it to PHARMACIST.
  The receiver's Handover and Alerts screens still say no alerts were recorded. Heat and freeze are recomputed from
  the downloaded readings, so the verdict is right, but the alert record, its escalation and its acknowledgement
  are never shown. Since `91db6ea`, a watching phone also no longer raises tail-gap alerts of its own. The screen
  states "no alerts" without having the data.
- **Fix:** fetch the shipment's alerts from the server (members can read `alerts`), or say "No alerts raised on this phone".

### S5. `TripWindow` with a viewer clock behind the server's ends the window before the handover (Info, CONFIRMED)

- **Where:** `core-domain/.../handover/TripWindow.kt:16`. When `handedOverAt > now` (the phone's clock is behind the server), it returns the phone's `now`, which is earlier than the true handover.
- **Effect:** the silence between `now` and the handover isn't counted.
- **Why Info:** this is never more lenient than the old "now" (the KDoc's promise holds), and it is display-only. The verdict a receiver records doesn't use `TripWindow` (grep: only `HandoverScreen.kt:144`). The window can't be made *more* lenient than the recorded verdict through `server_time`: custody moves at or after the receiver's own confirmation, so the display window ends at or after the recorded verdict's window. Courier-side clock error moves readings' `logger_ts`, and the server flags it as `clock_ahead` (see S2).
- **Fix:** none needed. If wanted, compare in server time (the head's `received_at`) rather than the viewer's clock.

### S6. Take-over recording reads the *profile* role, while custody follows the *shipment* role, and the server doesn't tie them together (Low, CONFIRMED)

- **Where:** `HandoverViewModel.kt:318-319` and `TransitViewModel.kt:293` (`profile.role`), against `HandoverScreen.kt:178` (member role). `0002_trust_core.sql:394-421`: `add_shipment_member` checks that `p_role` is courier or receiver, but not that the person's profile role matches.
- **Scenario:**
  - A sender adds a receiver-profile account as the second courier. They take custody and their phone records nothing. That is fail-safe: a gap, then UNKNOWN.
  - A courier-profile account added as the receiver records FAIL. The status becomes QUARANTINED, which is in `ON_THE_MOVE`, so their phone starts recording a shipment that has arrived.
- **Not tested:** mutants 20 and 23 survived.
- **Fix:** decide on the shipment's member role (`getMembers`) in both places, and add a receiver-FAIL case to `HandoverViewModelTest` and a receiver case to `TransitViewModelTest`. Optionally make `add_shipment_member` require a matching profile role.

### S7. Unsent GPS points are not counted as unsent; sign-out or the next person's sign-in deletes them (Low, CONFIRMED)

- **Where:** `app/.../repository/LocalSession.kt:74-79`. `countUnsent` counts readings, alerts and handovers but not `gps_points`, and `forgetLocalData` runs `clearAllTables()`.
- **Fix:** add `gpsPointDao().getPending()` for server shipments to `countUnsent`, or say on the sign-out screen that location points are dropped.

### S8. Location privacy and bounds (Low; the SQL predates gate-3, the continuous upload is new)

- **Who can see it:** a fix every minute (`FusedLocationSource`, high accuracy) is readable by *every* member of the shipment, for ever (`gps_points_read`: `private.is_member`). That includes the sender, every courier of every leg and the receiver. The watcher shows it live (`TransitViewModel.kt:270-273`).
- **Retention:** synced rows stay on the phone until sign-out. Nothing on the server deletes them.
- **Bounds:** `append_gps_points` (`0002_trust_core.sql:804-819`) takes any number of points per call and any `lat`, `lon` or `device_ts`, with no range check. The custodian can flood or poison the table.
- **On devices:** PROGRESS notes the location was the owner's own. No coordinates were found in the evidence PNGs, both read in this review.
- **Fix:**
  - A retention rule (delete after the shipment ends plus N days).
  - Restrict reads to the sender and the current custodian.
  - Range checks (`lat` between -90 and 90, `lon` between -180 and 180, finite accuracy), and an array cap (for example 500).
  - Delete synced rows locally after the trip.

### S9. Firmware: one history request can build about 400 KB in RAM (Low, CONFIRMED on host code; never on a board)

- **Where:** `firmware/src/history.cpp:62-83` and `record_log.cpp:50-56`.
- **Scenario:** `max_records` is a peer-chosen `u16` (up to 65,535). `history_notifications` reads up to the whole log (8,192 records, about 200 KB as `Record`s) and builds every page (about 1,370 notifications, about 200 KB) before sending one. A connected central can exhaust a 256 KB-RAM MCU. Each `hash_at` also recomputes up to 8,192 SHA-256 blocks per call, two calls per request.
- **Parsing:** sound. Exact sizes are checked (`parse_history_request` needs 10 bytes, `decode` needs `kRecordSize`), `sign_batch` refuses out-of-range sequence numbers before computing `first_seq - 1`, and the public key is checked on the curve.
- **Fix:** cap `max_records` (for example at the app's 500) and stream pages from the log rather than materialising them. The protocol could state the cap.

### S10. Multi-leg: a receiver who isn't part of a courier-to-courier handover can still quarantine the shipment (Info; SQL predates gate-3, newly reachable with multi-leg)

- **Where:** `0002_trust_core.sql`, `confirm_handover`, the QUARANTINED update: `c.verdict <> 'PASS' and (c.role = 'receiver' or c.user_id = v_releasing.to_user)`.
- **Scenario:** while courier A hands to courier B, the receiver (a member) confirms FAIL on the current head and the shipment becomes QUARANTINED. This only ever holds a shipment back, and only a named member can do it.
- **Fix:** if unintended, require `c.user_id = v_releasing.to_user`.

### S11. A reading made between the courier's last renewal and custody moving can't reach the server (Info)

- **Scenario:** courier A records reading N+1 locally after renewing on head N. Courier B or the receiver confirms on N, and custody moves. A's N+1 is then refused (A is no longer custodian), while B carries the chain on from N. The record lacks up to one reading at each handover, about a minute of simulated temperature. The receiver's physical inspection covers that moment.
- **Fix:** none needed. Optionally say so in `THREAT_MODEL.md`.

### S12. `tools/device/drive.sh` `tap` passes `$xy` to the device shell unvalidated (Info)

- **Where:** `tools/device/drive.sh:27-28`. `$xy` comes from `find_element.py`, which prints the layout tool's `center` field, and reaches `adb shell input tap $xy` unquoted and without `plain`. Today that field is computed bounds, not app text, so it is not exploitable.
- **The comma change** (`plain`, line 41) is safe: a comma has no meaning to `sh`. `;`, backtick, `$(`, space and quotes are still refused.
- **Fix:** check `xy` against `^[0-9]+ [0-9]+$` before use.

### Checked with nothing found

- **Recording service:**
  - `exported=false`.
  - The `PendingIntent` is `FLAG_IMMUTABLE` and explicit.
  - The wake lock is partial, non-counted, renewed every 5 s round with a 10-minute timeout, taken only in the foreground, and released in `finish()` and `onDestroy()`.
  - A system restart with no foreground stops at once (`RecordingService.kt:66-78`). The fallback to `connectedDevice` alone is tested (mutant 21).
  - A restart resumes only the remembered shipment, and only if it is still on the move and the server doesn't say custody moved (`RecordingLoop.restore`).
- **Session change:** `enableLifecycleCallbacks = false` keeps the session and auto-refresh alive in the background, as intended. Sign-out still clears it (`signOut` LOCAL, else `clearSession`) and clears `RecordingStore`, `PendingReleases` and the history coordinator (`AppModule.kt:189-194`). The risk is on the load side (S1).
- **Window fix:**
  - A courier who confirms early and stops recording gets UNKNOWN from an unmodified receiver app (mutant 6 killed). A modified courier app is covered in S2.
  - `renewRelease` re-confirms only as custodian on the current head; the server moves custody only with both confirmations on the current head (SQL read).
  - A third party can't take over: the server takes only the `to_user` the custodian named, and that person must be a courier or receiver member.
- **Finding 11 polling:** `PollBackoff` is bounded between 3 and 30 s and resets on change (mutants 3 and 4 killed).
- **`onCodeScanned`:** the code is a lookup key passed as a bound PostgREST filter value and a Room parameter. Nothing is built from it.
- **Release build:**
  - R8 keeps supabase-kt and Ktor engines whole. This affects size only.
  - `checkReleaseKeepRules` fails the build if a registrar loses its constructor.
  - Signing reads `CCH_RELEASE_KEYS` only. The properties are read with `getProperty`, and no value is in the repository.
  - The release dex check in PROGRESS found `sb_secret_` only as a library constant.
- **Secrets in `git log -p gate-3..HEAD`:**
  - Added lines were searched (counts only, values not printed).
  - None of these appear: `sb_secret_…`, `sb_publishable_…` values, JWT pairs, PEM private keys, `ghp_`/`sbp_`/`AKIA` tokens.
  - No `.jks`, `.p12`, `.pem`, `.env`, `.properties` or `mapping.txt` was added.
  - `storePassword`/`keyPassword` appear only as `getProperty` reads.
  - Email addresses appear only as `@example.com` test fixtures and the `you+sender/courier/courier2/receiver@gmail.com` placeholders that DECISIONS (26 Nov) chose. Commit author emails are placeholders.
  - `D:\cch-keys` appears only as a path in the docs.
- **Evidence screenshots:** `docs/agent/evidence/20261127_light_*.png` show a display name ("Courier Two"), a shipment ID and simulated readings. There are no emails, coordinates or place names beyond the demo origin and destination.

## 4. Claims in STATUS.md or PROGRESS.md the evidence does not support

- **C1.** STATUS, finding 9 ("Closed … process death") and the "Proven on real systems" list say recording survives process death. It does online only. Offline process death was never run, and by code reading it stops recording (S1).
- **C2.** STATUS, "a typed email survives rotation" and "local mode records through rotation and a kill". True, but on builds up to `87d130e` (`gate-3`), before the recording service and the Handover changes. Nothing was rotated on any Gate 5 build, which Gate 5 names first.
- **C3.** STATUS, "Killed while on Handover or Alerts, the app comes back on the same shipment with the inspection answers still selected". Re-checked on the S24 after `1056658`, but in debug local mode only (`SHIP-D77515`), not with a server shipment or a signed-in account.
- **C4.** PROGRESS, 26 Nov: "A second mutant (custody-moved check removed) fails `theCourierStopsRenewingOnceCustodyHasMoved`". No longer true at HEAD. With finding 11's fast path (`1a4691c`), the custodian branch of `custodyMovedOn` is reached by no test (mutant 10 survived).
- **C5.** STATUS, finding 11: "the courier re-confirms only after a new reading". True, but the guard that ends renewals when custody reached someone else rests on the fast path plus the minute-long custody check, not on `custodyMovedOn` (C4).
- **C6 (an omission, not a contradiction).** STATUS's "no crash or ANR" is about the S24 on 23 and 24 Nov and is accurate for what it covers. But STATUS doesn't mention the ANR that the release build raised on the emulator on 27 Nov (22:58, "Waited 5001ms for FocusEvent"), which PROGRESS records as unexplained.
- **C7.** The gate wording asks for "instrumented or scripted checks". The device evidence is hand-driven `adb` sessions written up in PROGRESS. It is careful and specific, but it cannot be re-run as a script or test.
- **C8.** Screens: "✓ READINGS VERIFIED … match the server" and "TAMPER DETECTED" remain, against MASTER 5.5 item 1 ("remove … hash jargon") and its honesty rule. "Verified" over readings simulated on the courier's phone says more than is checked: only that the chain is unbroken and matches the server.
