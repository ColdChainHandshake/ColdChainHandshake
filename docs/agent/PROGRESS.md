# Progress

**Next action (27 Nov, ~16:05, the owner stopped the session to leave):** done today, all on `rebuild/trust-core`:
the honesty pass, the receiver's PASS on `SHIP-49A74F`, GPS in the recording service, finding 11, two bugs found on
the emulator (a "Tamper Detected" flash that moved the Handover form; false DATA_GAP alerts from a watching phone),
location coming back after a background restart, the S24 run of `SHIP-1D87CC` (kills, 74 minutes offline, an alert
raised offline, GPS, a FAIL handover, the S24 stopping by itself), 5.3 multi-leg (fakes and the local stack), 5.5
audit fixes (permissions, a lazy history, the product's own range). 5.6 started: the upload keystore exists in
`D:\cch-keys`; R8, signing and version `0.5.0` are in `app/build.gradle` (`da4e737`): the signed, minified APK
builds (25.4 MB) and `apksigner verify` passes; not yet run on a device (`DECISIONS.md`, 27 Nov). Still to do, in order:
1. **5.6:** `CCH_RELEASE_KEYS='D:\cch-keys\keystore.properties' ./gradlew assembleRelease`; fix any R8 missing-class
   or keep problem; `apksigner verify`; install the release APK on the emulator (start it with
   `-gpu swiftshader_indirect -no-snapshot`: the host renderer draws app windows black) and check it opens to
   sign-in with no crash. Signed-in flows under R8 need a sign-in code from the owner's inbox (HANDOFF).
2. **5.5, the rest:** intent security (only the launcher is exported; nothing reads intent extras), Play policy
   notes (foreground service types location and connectedDevice need Play declarations; location in the data
   safety form), on the emulator: the notifications prompt on Android 13+, accessibility labels and 48 dp targets,
   light and dark mode, screenshots to `docs/agent/evidence/`. The permission fixes of `4793425` to be checked on a
   device (deny twice, the button opens Settings).
3. **Gate 5:** a fresh reviewer (one sitting, at most 25 mutants, findings to a file) with MASTER's Gate 5 criteria,
   plus a manual security review of `gate-3..HEAD` (window fix, recording service, session change, firmware, GPS,
   finding 11, multi-leg), because the automatic commit reviews keep failing. Evidence for the criteria is in this
   file (S24: screen off, deep Doze overnight, kill -9, 74 minutes offline, process death). Tag `gate-5`.
4. Phase 4: 4.3 and 4.1 (`05b342e`-compatible), Gate 4 "met without hardware"; then Phase 6 and Gate 6.
Open findings: the courier's phone keeps a handed-over shipment as IN_TRANSIT locally; GPS in deep Doze unproven
(screen-off GPS with the network on is proven); a watcher's "nothing has reached the server for N minutes" belongs on
its screen (not built); 5.3 not yet on devices (needs a second courier account).
Memory rule (owner, 24 Nov, still in force): 16 GB RAM; never Gradle and the emulator at once; full proof in the
foreground, then `./gradlew --stop`, then the emulator; one emulator only. `PHONE = present`.
End of every session: push the branch, make a verified bundle on D:.

Status words: `done`, `doing`, `blocked` (with the reason), or blank for not started.

## Session of 27 Nov 2026, evening

### Status at session start (~20:20)

- **Git:** `rebuild/trust-core`, HEAD `2674949`, clean. Tags `gate-0` to `gate-3`. One stash (the honesty pass of
  26 Nov, applied and committed as `9b40ab2`; kept, never dropped)
- **Owner's message:** continue PROGRESS's next action through to Gate 6. Before tagging Gate 5: the signed release
  APK on a device under R8, signed in, with one handover step; the IN_TRANSIT finding fixed test-first; the
  watcher's note if small; STATUS, the Gates line and PROGRESS's phase checklists brought up to date; Gate 5's
  reviewer does the manual security review of `gate-3..HEAD`. Multi-leg on devices: yes, with
  `you+courier2@gmail.com` as the second courier. `PHONE = present`, the owner at the laptop at the start. The owner
  has backed up `D:\cch-keys`. Edit and Write only for files (no shell or Python edits)
- **Devices:** the S24 (`RZCY820AG0F`) connected. No emulator running. Free RAM 7.3 GB of 15.6 GB
- **Device plan (3 email codes):** courier2 signs up on the emulator's release app; the receiver signs in on the
  S24's release app; the sender signs in on the emulator's debug app after `pm clear`. Courier A stays on the S24's
  debug app. Release and debug are separate installs with their own device keys, and each handover pair
  (A → B on S24 → emulator, B → receiver on emulator → S24) crosses two physical devices

### 5.6 release build under R8 on devices: `done`

- [x] Signed `assembleRelease` (`CCH_RELEASE_KEYS` set): `apksigner verify -v` → Verifies, v2 scheme. The release dex
  holds the dev ref once, the demo ref 0 times; `sb_secret_` appears once as a bare prefix constant next to `sb_temp_`
  in the dex string table (a library's key-type check, no key after it); `app/build.gradle` refuses a secret key
- [x] **Found on the S24 at the first start of the release app:** `ComponentDiscovery: Could not instantiate
  com.google.mlkit…CommonComponentRegistrar` (and `BarcodeRegistrar`, `VisionCommonRegistrar`):
  `NoSuchMethodException …<init> []`. firebase-components 16.1.0 keeps the registrars, but R8's full mode drops
  their no-argument constructors (developer.android.com, "R8 full mode: retain the default constructor"). The QR
  scanner's ML Kit couldn't start. Fixed test-first (`55bc5c9`): `checkReleaseKeepRules` reads R8's `seeds.txt` and
  fails `assembleRelease` when a kept `*Registrar` lost its constructor; it failed naming all three, then the keep
  rule, then it passed and the warnings were gone on the S24 and the emulator (the camera scanner opened under R8)
- [x] **Signed in under R8:** courier2 signed up on the emulator's release app (code, profile "Courier Two", Courier);
  the receiver signed in on the S24's release app. No `NoSuchMethod`, `ClassNotFound`, `Serializ…` or `FATAL` in
  either app's log during the whole run. `mapping.txt` of `2674949` kept in the scratchpad for retracing
- [x] **Found: a new person's phone wasn't registered** until the app opened again (dev project's edge logs: courier2's
  `verify`, `complete_profile`, `profiles`, then no `register_device`; after a relaunch, device `1ead62c6` at
  15:15:25 UTC). The About-you form's scope is cancelled as the person becomes Ready. Not R8: the same code in debug.
  Fixed test-first (`4ec5177`): `SupabaseAccountRepositoryTest.completingAProfileRegistersThePhoneEvenWhenTheFormLeavesAtOnce`
  timed out first (no device), passes after

```text
CCH_RELEASE_KEYS=… ./gradlew checkReleaseKeepRules   before: R8 kept these without their no-argument constructor:
  …BarcodeRegistrar, …VisionCommonRegistrar, …CommonComponentRegistrar   BUILD FAILED
after the rule: seeds.txt …CommonComponentRegistrar: CommonComponentRegistrar() (and the other two)   BUILD SUCCESSFUL
```

### The IN_TRANSIT finding and the watcher's note: `done`

- [x] Before the fix, on the S24 (debug app's Room, read with `run-as`): `SHIP-49A74F` IN_TRANSIT, which the server
  has held ACCEPTED since 10:51 today
- [x] `36d0bf0`, test-first: `RecordingLoopTest.whenTheReceiverPassesWhileTheCourierWaitsThisPhoneHoldsTheShipmentAsAccepted`,
  `whenTheLoopsOwnCustodyReadFindsItMovedThisPhoneTakesTheServersStatus`, `afterARestartAShipmentHandedOverMeanwhileTakesTheServersStatus`
  failed first (IN_TRANSIT where the server had ACCEPTED or QUARANTINED); `handedToAnotherCourierTheShipmentStaysInTransitHere`
  guards the courier-to-courier leg. RecordingLoop refreshes the shipment from the server once custody moves
- [x] The watcher's note (`8a9630d`), test-first: while watching, Transit says "No reading made after HH:MM had
  reached the server when this phone checked at HH:MM (N min)…" past the product's silence limit, and "This phone
  last reached the server at HH:MM…" when it is the watcher that is cut off; a watcher's line says readings are
  simulated on the courier's phone. `TemperatureCardTest` +2, `TransitViewModelTest.whileWatchingTheScreenKnowsWhenItLastReachedTheServerAndWhenItCannot`
  failed first (unresolved), pass after

```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
  (CCH_RELEASE_KEYS set) BUILD SUCCESSFUL in 9m 22s | core-domain 127 failed 0 | debug 571 failed 0 | release 513 failed 0
  | lint 0 errors, 22 warnings | checkReleaseKeepRules passed        (at 8a9630d; then ./gradlew --stop)
```

### Multi-leg custody on devices, `SHIP-2DE57A`, partly under R8: `done` (21:10 to 21:29)

- Sender (emulator, debug app after `pm clear`) created it with "courier, courier2" in the couriers field and the
  receiver, and dispatched it. Server: members sender, Gate3 Courier, Courier Two, Gate3 Receiver
- [x] **Courier A** (S24, debug, device `88017876`) took custody at 21:11:18; service `connectedDevice|location`
  (`0x18`); readings 1 to 11 reached the server at most 6 s after they were made, GPS each minute
- [x] **Courier B** (emulator, **release under R8**, device `1ead62c6`) opened it by ID and watched: readings
  downloaded and checked, the courier's location shown. A named Courier Two and confirmed; B recorded PASS (stage
  1, shake test passed). Server: leg 2 TRANSFER to Courier Two at 15:51:40 UTC. **A's service stopped by itself**
- [x] **B recorded under R8:** readings 12 to 17, signed by `1ead62c6`, on the same chain, at most 4 s late
- [x] **The receiver** (S24, **release under R8**, device `ed74d252`) watched and recorded PASS after B confirmed;
  server: **ACCEPTED**, leg 3 TRANSFER to the receiver at 15:58:11 UTC; B's last confirmation and the receiver's PASS
  on the same head `548ee049` (seq 17), from two physical devices. B's recording service stopped by itself
- [x] **The IN_TRANSIT fix on a device, under R8:** B's phone, reopened on the shipment, showed `Status: ACCEPTED`
  straight away. A's phone holds it as IN_TRANSIT: the server's status when A handed over to B; A no longer follows
  it, and opening it brings it up to date
- **Found: B was never asked for Nearby devices or location.** Taking over on Handover doesn't ask (only Take
  Custody on Transit does), so B's service ran as a plain started service, recording only while the app was open,
  and Transit's banner offered "Open Settings" although nothing had been refused. Fixed test-first (`687e62a`):
  `HandoverScreenTest.aCourierTakingOverIsAskedForWhatRecordingNeedsBeforeConfirming` failed against the old wiring
  (nothing asked), passes after; `aReceiverTakingOverIsAskedForNothing`; `RecordingPermissionsTest` 4. Not yet
  seen on a device
- Rule slip, recorded: Gradle ran for 34 s (`checkReleaseKeepRules`) while the emulator was booting at 20:27; the
  emulator was stopped at once and restarted after the builds

### 5.5 on the emulator and the take-over prompt on the S24, `SHIP-CB7F34`: `done` (22:50 to 23:20)

Builds of `db0ce9b` on both devices. The sender (emulator, debug) created and dispatched `SHIP-CB7F34` (Courier
Two, Gate3 Courier, Gate3 Receiver).
- [x] **Deny twice → Settings** (emulator, release, Courier Two, no permissions): Take Custody asked for location,
  then Nearby devices; both refused; custody was taken anyway and recording ran as a plain service. The banner read
  "Allow Nearby devices" (new in `687e62a`; before it went straight to Settings). Tapped: both prompts again,
  refused again. The button then read "Open Settings" and opened the app's page in Settings (`SpaActivity`, App info)
- [x] **Found: Nearby devices allowed in Settings didn't reach the running recording.** With `pm grant … BLUETOOTH_CONNECT`
  (what the Settings switch does) and back in the app, the banner was gone but the service had no `isForeground`.
  Fixed test-first (`b17decb`): `TransitScreenTest.nearbyDevicesAllowedInSettingsMovesTheRunningRecordingIntoTheForeground`
  failed first; `comingBackWithNothingAllowedAsksNothingMore` passes both ways
- [x] **The take-over prompt on a real phone** (`687e62a`): the S24 debug app's Nearby devices and location revoked
  with `pm revoke`; Courier Two named Gate3 Courier and confirmed; on the S24, Handover, Record PASS asked for location
  ("While using the app") and Nearby devices (Allow) **before** anything was sent (server: 0 confirmations from
  Gate3 Courier while the prompt was up); then leg 2 TRANSFER to Gate3 Courier from `88017876`, and the S24's service
  in the foreground as `0x18`. Courier A then released to the receiver (S24, release), PASS: ACCEPTED, 3 legs, 14
  readings. Nothing recording anywhere afterwards
- [x] **48 dp targets and labels**, measured from the layout at 420 dpi (126 px) on Transit (empty, watching, top and
  lower), the scanner (with and without camera), Handover (every scroll position), Alerts (empty and with a
  shipment): every tappable element labelled; all at least 126 px except **the inspection's option rows, 85 px
  (~32 dp)**. Fixed test-first (`8e8d182`): `OptionSelectorTest.everyOptionIsAtLeastAFingerTall` failed first
  ("Check Again" measured 125 px: 48 dp after rounding)
- [x] **Light and dark mode:** the app passes `darkTheme = true` in both system modes, so it shows one dark scheme;
  the light scheme is unused (DECISIONS). Screenshots in `docs/agent/evidence/20261127_light_*.png` (no location on
  either, checked in the layout before saving)
- [x] **Found in that screenshot: an ended trip shown as UNKNOWN.** `SHIP-2DE57A` (PASS, ACCEPTED, 17 readings 60 s
  apart on the server) showed "UNKNOWN (gap in the readings)" on Courier Two's phone two hours later: the screen
  counted silence up to now. Fixed test-first (`67b034e`): the app reads when custody moved and `TripWindow` ends the
  check there once a receiver holds it. `TripWindowTest` 4, `HandoverScreenTest.aShipmentOpenedAfterTheReceiverTookItIsCheckedUpToTheHandover`
  (fails with the old window), `whileACourierHoldsItTheSilenceSinceTheLastReadingStillCounts`,
  `ColdChainRemoteContractTest.custodyIsReadWithWhenItMoved`. `authenticated` may select `server_time` on the dev
  project (`column_privileges`), so the new column read can't be refused
- **The release app raised an ANR on the emulator** at 22:58 ("Waited 5001ms for FocusEvent"), with the camera
  preview open under software rendering. At that moment the emulator's graphics composer used 91 % CPU, the phone and
  wellbeing system apps had ANRs of their own, and the host had 1.9 GB free. The trace needs root, so the cause is
  not proven; not seen on the S24. Afterwards the camera was refused on the emulator to keep the load down
```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
  (CCH_RELEASE_KEYS set, no emulator) BUILD SUCCESSFUL in 28m 30s | core-domain 131 failed 0 | debug 585 failed 0
  | release 520 failed 0 | lint 0 errors, 12 warnings | checkReleaseKeepRules passed      (at 67b034e; then --stop)
```

- **Observed, not fixed:** on Courier Two's Handover the form moved down 84 px between two layout reads 10 s apart
  while readings arrived, so one tap missed (nothing wrong was sent; the server shows the confirmation only after the
  retap). Recorded for Phase 6

### Courier A's final status, lint, audits: `done`

- [x] `db0ce9b`, test-first: opening a shipment this phone holds asks the server for its status first (no polling);
  `TransitViewModelTest.openingAShipmentThisPhoneHoldsShowsTheServersStatusFromTheStart` failed first (IN_TRANSIT),
  passes after; `withNoSignalAShipmentThisPhoneHoldsStillOpensAsThisPhoneHasIt` keeps the local copy offline
- [x] Lint 22 → 12 warnings (`f3636bc`), 0 errors; the 12 left are justified in `docs/agent/audit_5_5.md`, which also
  holds the intent-security check (the release APK's own manifest: only the launcher and system-guarded library
  components exported, no intent extras read), Play policy notes and the R8 summary

```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
  (CCH_RELEASE_KEYS set, no emulator) BUILD SUCCESSFUL in 21m 37s | core-domain 127 failed 0 | debug 579 failed 0
  | release 519 failed 0 | lint 0 errors, 12 warnings | checkReleaseKeepRules passed      (at db0ce9b; then --stop)
```

## Session of 27 Nov 2026

### Status at session start (10:28)

- **Git:** `rebuild/trust-core`, HEAD `fe43193`, clean. Tags `gate-0` to `gate-3`. One stash, "honesty pass,
  untested: screen texts (26 Nov)"
- **Owner's message:** apply the stash first, prove it, commit. Before Gate 5, add GPS in the recording service,
  finding 11's polling, the 30-minute airplane run, and a manual security review since `gate-3` by Gate 5's reviewer
  (`DECISIONS.md`, 27 Nov). Full proof in the foreground, then `./gradlew --stop`, then device work
- **Devices:** the S24 (`RZCY820AG0F`) awake, unlocked, on charge with Stay awake, the debug app of `229cefc`
  recording `SHIP-49A74F` in the background (`RecordingService isForeground=true types=0x00000010`). No emulator.
  Free RAM 6.8 GB of 15.6 GB
- **`SHIP-49A74F` on the dev project at 10:30:** IN_TRANSIT, 777 readings, 733 courier confirmations (one renewal
  per new head), the newest reading received 2.6 s after it was made. One gap over 90 s in the whole run: 125 s at
  seq 49, the reinstall of 26 Nov
- **The overnight run, from the S24's battery history** (`dumpsys batterystats --history`, reset 26 Nov 22:47:34,
  on battery from then until 27 Nov 10:26:39): screen off from 22:59:51; deep Doze (`device_idle=full`) from
  23:01:51, with maintenance windows at 00:01, 02:02 and 06:03 (Doze's intervals growing to 4 hours); screen woke at
  08:53:46. The 582 readings made in that window (seq 101 to 682) are all on the server, contiguous, at most 61 s
  apart, received 3.7 s after they were made on average and 8 s at most. Readings 73 to 777 (11.7 hours) all
  arrived within 8 s

```text
select ... from readings where shipment_id='SHIP-49A74F' and logger_ts between 23:01:51 and 08:53:46 IST
  count 582 | seq 101..682 | max_delay_s 8 | avg_delay_s 3.7 | max_gap_s 61
```

### The honesty pass stash: `done` (partly; the rest is listed below)

- [x] `stash@{0}` applied (`git stash apply`, the stash kept), full proof, committed `9b40ab2`
- [x] Found while checking it: the new "Last reading: …" line formatted the simulator's clock, which is set to the
  moment recording starts, so before any reading it named a time no reading was made. Fixed test-first (`ddfbb38`):
  `TemperatureCardTest.theLineUnderTheTemperatureNamesTheNewestStoredReadingOrSaysThereIsNone` failed first
  (unresolved `lastReadingLine`), then 5/5; release compiles
- [ ] Still to do (seen on the emulator today): "Room Local Database" on Transit's log, a hash prefix (`#6380742e`) on
  every reading on Transit and Handover, the sender's account UUID as "Worker: …" on Handover, wrapped labels
  (CONTINUE Step 4). "Checked every few seconds" and "while this screen is open" become false with finding 11 and
  GPS, and change in those commits

```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
  BUILD SUCCESSFUL in 11m 42s | core-domain 122 failed 0 | debug 524 failed 0 | release 467 failed 0 | lint 0 errors (22 warnings)
./gradlew :app:testDebugUnitTest --tests '*TemperatureCardTest*'   before: Unresolved reference 'lastReadingLine'
                                                                   after:  tests=5 failures=0 errors=0
```

### Device: the receiver's PASS on `SHIP-49A74F` (PROGRESS item 2, first half): `done`

Debug APK of `9b40ab2` (BuildConfig: dev ref 1, demo ref 0, `sb_secret_` 0) installed over the receiver's app on
the emulator with `adb install -r` (still signed in). The S24 kept the build of `229cefc`.

- [x] Transit, opened by ID: 792 readings downloaded, temperature and status from data
- [x] Handover: verdict PASS over 793 readings (4.5 to 5.5 °C, no time out of range); vial monitor stage 1, shake
  test passed, Pass, Record PASS handover at 10:51:04
- [x] **The server (MCP):** ACCEPTED at 10:51:06. The receiver's PASS (`STAGE_1`, `PASSED`) and the courier's renewal
  at 10:50:47 are on the same head `a662c58f` (seq 797), from two devices (`88017876` the S24, `1241f8d4` the
  emulator); custody TRANSFER to the receiver as leg 2. Afterwards no new confirmation (754) and no reading after
  seq 797 (last received 10:50:46)
- [x] **The S24 stopped by itself:** `RecordingService` gone from `dumpsys activity services` 40 s later; its
  notification only in `mArchive` (removed)
- [x] **The receiver's history is complete:** its Room database (copied with `run-as`) holds 797 readings, seq 1 to
  797, no gap, equal to the server; shipment ACCEPTED; handover PASS, SYNCED
- **Found, a layout jump on Handover:** twice a tap landed ~170 px below its target (once on the FAIL option, once in
  the Reason field), both within 2 s of a new reading reaching the server (10:47:40, 10:49:44). The app refused the
  accidental FAIL locally ("A FAIL handover needs a reason"), so nothing wrong was sent. To fix test-first
- **Found, request rates (dev project's edge logs, per user):** the receiver watching on Transit about 89 requests a
  minute (custody twice, GPS, readings, head and shipment every ~4 s); on Handover about 45 a minute; the courier's
  phone waiting in the background about 48 a minute, with 77 of its 87 `confirm_handover` calls in 10 minutes refused
  as already confirmed (CC008), because it confirms again every 5 s on an unchanged head. Finding 11's baseline

### Two bugs found on the emulator, fixed test-first: `done`

- [x] **"Tamper Detected" flashed on Handover** (`0e6e849`): the screen collected the readings and the server head
  separately and saw them out of step. `HandoverViewModelTest.whileReadingsArriveTheScreenNeverComparesTheNewHeadWithTheOldReadings`
  failed first: `the screen saw fewer readings than the head: [0 < 10, 11 < 12]`. `aDeviceStillShortOfTheServersReadingsIsShownTheMismatch`
  guards the other side; a mutant that never shows the real head fails it (`shown: [0/null, 10/10, 9/null]`)
- [x] **False DATA_GAP alerts from a watching phone** (`91db6ea`): `LoggerSilenceCheckTest.aShipmentThisPhoneOnlyWatchesRaisesNoGapForReadingsNotYetDownloaded`
  and `...StillRaisesWhatItsDownloadedReadingsShow` failed against a stub (`No readings for 720:00`; `[TEMPERATURE_BREACH, DATA_GAP]`);
  `AlertEscalationColdStartTest.theWorkerRaisesNoGapForAShipmentThisPhoneOnlyWatches` failed through the real worker
  before the wiring, `theWorkerRaisesAGapForTheShipmentThisPhoneRecords` passes both ways

```text
./gradlew :app:testDebugUnitTest --tests '*HandoverViewModelTest*' --tests '*HandoverScreenTest*'   11 + 14, 0 failed
./gradlew :app:testDebugUnitTest --tests '*AlertEscalationColdStartTest*' --tests '*AlertNotificationsTest*' --tests '*LoggerSilenceCheckTest*'
                                                                                                    4 + 14 + 13, 0 failed
```

### GPS in the recording service, finding 11, and the honesty leftovers: `done` in code

- [x] **GPS** (`e942e87`, `f098dcc`): `RecordingServiceTest` 12 (6 new: location type, no location, Android refusing the
  type, location allowed later, stopping, a fix queued; the refusal test fails against a mutant without the
  `connectedDevice` fallback, `expected:<16> but was:<0>`), `LocationRecorderTest` 4, `SyncServiceTest` +3 (queued
  offline then sent, refused set aside, local never sent), `ColdChainDatabaseMigrationTest` +1 and the v5 hash,
  `TransitViewModelTest` +4. Found by the advisor before the device run: after a background restart nothing gave
  location back and the card read the permission, not the service; fixed test-first (`onAppOpened`, `active`)
- [x] **Finding 11** (`1a4691c`): `PollBackoffTest` 5, `ShipmentHistorySyncCoordinatorTest.aFetchWithNothingNewAsksOnlyForTheHead`
  (failed first: 2 readings requests, expected 1), `TransitViewModelTest.whileWatchingAndNothingChangesTheServerIsAskedLessAndLess`
  (failed first: 80 requests in the third minute; now at most 6), `RecordingLoopTest.whileWaitingWithNothingNewRecordedTheCourierDoesNotConfirmAgain`
  (failed first: 14 confirmations, expected 1), `withNoHandoverWaitingCustodyIsReadOnlyNowAndThen` (failed first: 6),
  `whenTheReceiverConfirmsWhileTheCourierWaitsRecordingStopsAtOnce` (a mutant without the new path times out)
- [x] Honesty leftovers (`af29c6e`): readings by number, "On this phone", no account UUID as "Worker" on Transit or
  Handover; "Checked every 3 to 30 s"; a watcher's card no longer shows its own position as the courier's
- Test isolation fixed (`89d5009`): `HandoverViewModelTest` cancels its ViewModels before the next test sets Main

```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
  12:01  BUILD FAILED: debug 557, 2 failed (HandoverViewModelTest: "Dispatchers.Main is used concurrently with setting it")
  after 89d5009: BUILD SUCCESSFUL in 3m 39s | core-domain 127 failed 0 | debug 557 failed 0 | release 499 failed 0 | lint 0 errors (22 warnings)
```

### Device: `SHIP-1D87CC` on the S24 with the GPS build: `done` (12:13 to 15:33)

Debug APK of `f098dcc` (BuildConfig: dev ref 1, demo 0, `sb_secret_` 0). The phone's location is the owner's real
location: no coordinates, screenshots or place names are kept anywhere, only counts, accuracy and delays.

- [x] **Room 4 → 5 on a real phone** (finding 10): before the install the S24's database was version 4 with 797
  contiguous readings of `SHIP-49A74F`; after it, version 5, the same 797, an empty `gps_points`
- [x] **Take Custody asked for location** ("While using the app", precise). `dumpsys activity services`:
  `isForeground=true types=0x00000018` (`connectedDevice|location`). Reading 1 at 12:14:57
- [x] **The first GPS points on the dev project:** each fix reached the server 1 to 2 s after it was made, accuracy
  within 14 to 20 m, one a minute with the readings
- [x] **`am kill`**: pid 6824 before and after, still `0x18`
- [x] **A real kill** (`run-as … kill -9`, 12:15:50): Android restarted the service 4 s later, pid 8270, again in the
  foreground with `0x18`, location included (a restart by the system counts as started by a system component).
  Reading 2 at 12:15:58, 61 s after reading 1: no gap. GPS fix 2 at 12:15:58, 1.9 s to the server
- [x] **The courier's requests while recording, in the background** (12:16:30 to 12:21:30): 20 in 5 minutes, 4 a
  minute (readings, GPS, head, custody, one each). This morning, waiting for the receiver on the old build: ~48 a minute
- [x] **The courier confirmed the handover to the receiver while online** (12:23): "Confirmed. This phone keeps recording…"
- [x] **Finding 11's numbers on the dev project, after** (edge logs per user; before = this morning): the receiver
  watching on Transit while readings arrived, 45 requests in 3 min 50 s, ~12 a minute (before ~89); the same watcher
  while the courier was offline and nothing changed, 18 in 5 minutes, ~3.6 a minute (head and custody every ~33 s);
  the courier waiting after release, 52 in 3 minutes, ~17 a minute (before ~48), with 3 confirmations, one per new
  reading (before: 87 in 10 minutes, 77 refused as already made); the courier recording with nothing waiting, 4 a minute
- [x] **Offline 74 minutes** (airplane mode on by adb at 12:28:45, off at about 13:42, by hand on the phone: the
  owner). Heat spike on at 12:28:52 (reading 15 at 10.4 °C). Screen off and a simulated unplug at 12:29:35; deep
  Doze (`mState=IDLE`) by 12:36:30. The phone's own queue, read with `run-as`: 22 readings (8 unsent) at 12:36, and
  a TEMPERATURE_BREACH alert raised offline at 12:34:09, shipment QUARANTINED on the phone
- [x] **After reconnecting:** the 72 readings made offline (seq 15 to 86, 12:29:06 to 13:41:00) all reached the server
  at 13:42:50, in order; across the shipment's 186 readings no two are more than 61 s apart. The alert reached the
  server at 13:42:51, already escalated to PHARMACIST while offline. The one GPS point recorded offline (12:29)
  arrived at 13:42:50; no other fix was made offline (indoors, airplane mode)
- [x] **GPS with the screen off, network on:** from 14:01:58 to 14:30:44 (battery history) the screen was off and a
  fix reached the server every minute, ~1 s after it was made. Not in Doze then: GPS in deep Doze is unproven
- [x] **No false gap alert from the watching phone:** the receiver watched the courier's silence for 8 minutes past
  the 3-minute limit; no alert from the receiver's user on the server since 10:47 (today's fix `91db6ea` on a device)
- The owner used the phone during the run (power key at 12:41 and 13:40, airplane off ~13:42). The screen was on
  12:41 to 13:04; the rest of the offline run was screen-off. `dumpsys battery reset` at 15:23
- [x] **No "Tamper Detected" flash and no jumping form on Handover** (today's `0e6e849` on a device): the receiver's
  Handover sampled about every 2 s from 15:31:10 to 15:32:28, across reading 195 arriving at 15:31:36: the Record
  button stayed at (540, 2022) in every sample, and no "Tamper", "server holds", "this device has" or "syncing" text
- [x] **The receiver's FAIL** at 15:32:50: the screen offered only Fail ("FINAL VERDICT: FAIL"), reason from the checks
  ("Temperature went out of range"). Server: QUARANTINED; custody TRANSFER leg 2 to the receiver at 15:32:53; the
  receiver's FAIL and the courier's renewal (15:32:41) on the same head `1626d41d` (seq 196), devices `1241f8d4`
  (emulator) and `88017876` (S24)
- [x] **The S24 stopped by itself** (the renewal path on a device): `RecordingService` gone by 15:33:17, its
  notification removed; no reading after seq 196, no confirmation after, no GPS point after 15:32:24
- The emulator again drew every app window black after a cold boot (15:24) and raised the same "no focused window"
  ANR, this time with the window focused before any tap; the guest log showed `lowmemorykiller … device is not
  responding`. With `-gpu swiftshader_indirect` it drew and focused normally (`DECISIONS.md`)
- **The emulator, first try:** an ANR "Input dispatching timed out (Application does not have a focused window)" at
  12:16:47, after a cold boot and a fresh install, while the driver tapped before the window was focused. The ANR
  trace's main thread is idle in `Looper.pollOnce`, so not stuck in app code; the emulator then drew every app window
  black. Cold-booted again with `-no-snapshot`; the app then opened normally (focus checked before any tap)

### 5.3 Multi-leg custody: `done` (on fakes and the local stack; not yet on devices)

- [x] `MultiLegHandoverTest` (`0d0f844`), three phones on one fake server: courier A → courier B → receiver, custody
  leg by leg, the chain carried on by B, A refused afterwards. Passed as written: the domain and server already did it
- [x] The app side (`837aa9c`), test-first: `ShipmentWorkflowTest.severalCourierEmailsAreEachAddedAsACourier` (failed
  first: "No account has that email address", the whole field taken as one email);
  `HandoverViewModelTest.aCourierTakingOverFromAnotherCourierStartsRecordingOnTheirPhone` and
  `TransitViewModelTest.whileWatchingCustodyComingToMeAsACourierStartsRecordingHere` (both failed first against a
  stub: nothing recorded); `aReceiverTakingOverRecordsNothing`. The Dispatch field reads "Courier emails (commas between)"
- [x] `LiveSupabaseIntegrationTest.aShipmentGoesThroughTwoCouriersBeforeTheReceiver` (`a3d6ab6`) on the local stack
  (GoTrue, PostgREST, Kong, Postgres; CLI 3.3.0), after `db reset` and pgTAP

```text
npx supabase db reset && npx supabase test db                  Files=4, Tests=238 ... Result: PASS
CCH_LIVE_SUPABASE_URL=… CCH_LIVE_SUPABASE_KEY=… ./gradlew :app:testDebugUnitTest   live tests 4, failures 0
local database: the multi-leg shipment ACCEPTED, custody 1:START, 2:TRANSFER, 3:TRANSFER, 8 readings from 2 devices
```

## Session of 26 Nov 2026

### Status at session start

- **Git:** `rebuild/trust-core`, HEAD `8cbdf71`, clean. Tags `gate-0` to `gate-3`. `origin` is
  `github.com/ColdChainHandshake/ColdChainHandshake` (public); `gh` is signed in with push and admin rights; the
  branch has never been pushed (95 commits ahead of `origin/main`)
- **Owner's message:** new switches `FIRMWARE_OWNER = claude`, `PROTOCOL_Q4 = drafts`, `HARDWARE = none` (Gate 4
  "met without hardware"), `PUSH_POLICY = push-branch`, and a release keystore allowed under `D:\cch-keys`
  (`DECISIONS.md`). The demo project is deleted. `local.properties` now holds only `sdk.dir` and the dev project's
  URL and publishable key. Supabase MCP has write access to the dev project. The S24 is plugged in
- **Devices:** `adb devices -l` → `RZCY820AG0F device model:SM_S921E`. RAM 5.9 GB free of 15.6 GB. No emulator
- **Docker:** not running (started only for SQL work, never beside Gradle or the emulator)
- **Disk:** C: 64 GB free, D: 272 GB free
- **Toolchain for the firmware:** MSYS2 `g++` (`/c/msys64/ucrt64/bin`), CMake and CTest on PATH

### Doc hygiene and the owner's switches: `done`

- [x] STATUS, PROGRESS and HANDOFF brought in line with gates 0 to 3 and 5.1: phone screens and real email-code
  sign-in proven, finding 4 on two devices, finding 15's ViewModels done, Phase 3 items ticked with Gate 3's
  evidence, old "Next" lines marked superseded. STATUS finding 1: the demo project is deleted (the owner's check)
- [x] `PROTOCOL_Q4 = drafts`: `LOGGER_PROTOCOL.md` signed off, the minimum MTU rule (148, the phone asks for 517,
  the challenge needs 107) written in as a rule; "draft" removed from it, `firmware/WALKTHROUGH.md` and
  `firmware/include/cch/history.hpp`; HANDOFF Q4 closed
- [x] `local.properties` check, without reading the file: no `CCH_*` variables set, then `assembleDebug`

```text
env | grep -o '^CCH_[A-Z_]*'          (none)
./gradlew :app:assembleDebug           ok, app-debug.apk 20:15
app/build/generated/source/buildConfig/debug/.../BuildConfig.java (regenerated 20:14):
  yjvoxozaoiarwbusyqke 1 | ljqwaqesiszczixbclwg 0 | placeholder 0 | 127.0.0.1 0 | sb_publishable_ 1 | sb_secret_ 0 | eyJ 0
```

- [x] History scan before the first push (every commit reachable from the branch, values never printed): no
  `sb_secret_`, no JWT (`eyJ….eyJ….`), no PEM private key, no GitHub, AWS or Supabase access token, no
  `sb_publishable_` key. `service_role` appears only as the role's name (SQL grants, docs, the build's refusal
  check). The one private key is the logger test key in `logger_vectors.json`, derived from the published seed in
  `gen_logger_vectors.py`; the other 64-hex constants are P-256's `p`, `n`, `G` and the RFC 6979 example key. The
  only archives are the two Gradle wrapper jars (manifests and properties only). `local-snapshot-20261030` (which
  holds `.mcp.json`) is not an ancestor of the branch. **Found:** the owner's real sign-in addresses in
  `CONTINUE_PROMPT.md` (`816cf52`). Asked; the owner chose to push to the public origin anyway
- [x] First push of `rebuild/trust-core` to `origin` (explicit refspec, no tags), after checking every action tag in
  `ci.yml` exists (`checkout@v7`, `setup-java@v6`, `setup-android@v4`, `gradle/actions@v6`, `upload-artifact@v7`,
  `supabase/setup-cli@v3`). **The workflow's first run on GitHub passed**, both jobs:
  https://github.com/ColdChainHandshake/ColdChainHandshake/actions/runs/45842949385 (`e90726a`; android 7m14s:
  unit tests, lint, release build, Room schemas current; database 1m1s: pgTAP, advisors)

### CONTINUE Step 3 (5.2): background work and the handover window: `doing`

- [x] **The 3-minute window, fixed test-first** (`f6f4775`). The courier keeps recording after confirming;
  `ServerHandover.renewRelease` confirms the same release again on each new head (the server, unchanged, moves custody
  only when both confirmations are on the current head); the receiver's verdict still runs to their own
  confirmation. `HandoverWindowTest` (two phones, two databases, one fake server), 6 tests: against a stub
  `renewRelease` 4 of 6 failed (the 10-minute inspection got UNKNOWN; readings landing mid-confirmation never
  arrived). **The attack test** (`aCourierWhoConfirmsEarlyAndStopsRecordingCannotGetAPass`) passed against the stub
  as expected, so it was checked with a mutant: the verdict window ending at the last reading makes it fail ("a PASS
  can't be recorded over a gap"). A second mutant (custody-moved check removed) fails
  `theCourierStopsRenewingOnceCustodyHasMoved`. `FakeColdChainServer` was checked first: it moves custody only with
  both confirmations on the current head, like the SQL
- [x] The Handover screen fetches the history every 3 s while shown (`whileShownItKeepsDownloadingTheReadingsTheCourierSends`
  failed first, timing out at 11 readings), and tells a receiver whose confirmation new readings overtook to confirm
  again (`aReceiverWhoseConfirmationNewReadingsOvertookIsAskedToConfirmAgain`, failed first)
- [x] **Recording in the background** (`9599fbf`): `RecordingService` (foreground, `connectedDevice`, partial wake
  lock while foreground), `RecordingLoop` (send, renew, custody; restore after a restart), `CourierRecorder` and
  `RecordingStore` (resume after process death only for the shipment that was recording), `SyncWorker` (WorkManager,
  network constraint, backoff). Take Custody asks for Nearby devices. `RecordingLoopTest` 13 (8 of 12 failed against
  a stub loop; the 13th, status on resume, failed first), `RecordingServiceTest` 6 (Hilt, Robolectric),
  `SyncWorkerTest` 4. Decisions and test changes in `DECISIONS.md` (26 Nov)
- [ ] Open from MASTER 5.5 item 2: GPS in the service and queued in Room (`DECISIONS.md`)
- [ ] **Device proofs on the hosted dev project** (`doing`, 26 Nov from 21:10; the debug APK of `9599fbf`, dex: dev ref
  2, demo ref 0, placeholder 0). Courier: the owner's S24 (the app had been uninstalled; fresh install, courier code
  from the owner). Sender, then receiver: the emulator (`pm clear` between; codes from the owner). Two shipments
  created and dispatched by the sender: `SHIP-49A74F` (the long run) and `SHIP-1D87CC` (kill and alert tests)
  - Take Custody on the S24 asked for Nearby devices (allowed by tapping, not `pm grant`). `dumpsys activity services`:
    `RecordingService isForeground=true foregroundId=2 types=0x00000010` (`connectedDevice`). Custody 21:19:45
  - **Renewal against the real SQL:** the courier released at 21:23:43 on head `4265e38e` (n=5); after the next
    reading the phone confirmed again by itself at 21:24:44 on `87c3d90e` (n=6), the current head
  - **Found (fix written test-first after the run):** the Handover screen's Confirm button jumped by 96 px every 3 s,
    because each 3-second history fetch set the state back to "syncing" and showed a "waiting for the history"
    banner; two taps missed. `ShipmentHistorySyncCoordinatorTest.fetchingAgainKeepsAHistoryAlreadyCompleteReady`
    written; not run yet (no Gradle beside the emulator)
  - Screen-off phase: interrupted twice (the screen woke at 21:26:47 and 21:32:21, and the lock screen was unlocked
    by hand at 21:36:22); the owner agreed to leave the phone alone. Restarted with `dumpsys battery unplug` at
    21:39:10. Readings 7 to 17 arrived 1 to 7 s after they were made, except reading 7 (70 s, sent as the screen
    woke)
  - **Found, the real-device run's main finding:** from 21:39:10 (screen off) nothing reached the server, while the
    phone kept recording: `io_stats` showed the app writing 4 KB once a minute, the wake lock `cch:recording` was held
    and renewed every round (acquire count 162 → 222), the process was at procState FGS and not frozen, and the
    alert worker ran. The S24 went into deep Doze by itself (`mDeviceIdleMode=true`, state IDLE) about 2 minutes
    after the screen went off on simulated battery. The network was **not** the cause: `dumpsys netpolicy` for the
    app's UID gave `blocked=BATTERY_SAVER|DOZE|APP_BACKGROUND allowed=FOREGROUND… effective=NONE`, and the Doze
    firewall allowed it. The cause is supabase-kt's default: its TROUBLESHOOTING ("[Android] Retrieving data in the
    background does not work", 3.8.0, read through Context7) says the Auth plugin clears the session from memory when
    the app goes to the background; the 3.8.0 bytecode (`SetupPlatformKt`, `onStop`) calls
    `stopAutoRefreshForCurrentSession()` and sets the session status. The app then counted as signed out, and sync
    silently did nothing. Earlier on, reading 7 was sent only when the screen woke. Fixed test-first:
    `SupabaseClientProviderTest.theSessionStaysWhenTheAppGoesToTheBackground` (failed first),
    `enableLifecycleCallbacks = false`, and `SupabaseAccountRepository` loads the stored session once per process
    before reading the status (so no brief "signed out" that a restarted recording would act on). The 3-second
    banner fix is in the same build (`fetchingAgainKeepsAHistoryAlreadyCompleteReady` failed first:
    `expected:<HISTORY_READY> but was:<HISTORY_SYNCING>`)
  - The emulator was stopped at 21:47:25 for Gradle (memory rule); the S24 kept recording locally
  - **Readings recorded through screen-off and deep Doze reached the server in order:** seq 21 to 48, made once a
    minute from 21:39:48 to 22:07:06 (28 minutes, screen off, deep Doze from about 21:41), all received at 22:07:33,
    contiguous, the moment the old build came to the foreground
  - Fixed build installed at 22:07:49 (`229cefc`, debug APK dex: dev ref 2, demo 0, placeholder 0), owner unlocked the
    phone first. **Found:** recording did not resume by itself after the reinstall (no saved screen state, so the
    restorer didn't run); resumed by hand (Transit, the shipment's ID, Resume) at 22:09:12; reinstall gap 22:07:06 to
    22:09:12, 2 min 6 s, under the 3-minute limit. Fixed test-first afterwards (`resumeInBackgroundIfRemembered`)
  - **The fix proven on the S24:** screen off from 22:09:41 (simulated unplug, no wakes), deep Doze (`mDeviceIdleMode=
    true`, IDLE) by 22:16. Readings 50 to 58 reached the server 1 to 5 s after they were made, and the courier's
    confirmation renewed itself after each one (22:12:20, 22:13:18, 22:14:23, 22:15:21, …), all in Doze
  - **Airplane mode** on at 22:19:23 (`cmd connectivity airplane-mode enable`; `Active default network: none`). Last
    reading on the server before it: seq 58, made 22:18:21

```text
./gradlew :app:testDebugUnitTest --tests '*HandoverWindowTest*'    stub renewRelease: 6 tests completed, 4 failed
  mutant (window ends at the last reading):      1 failed  aCourierWhoConfirmsEarlyAndStopsRecordingCannotGetAPass
  mutant (no custody-moved check):               1 failed  theCourierStopsRenewingOnceCustodyHasMoved
./gradlew :app:testDebugUnitTest --tests '*HandoverViewModelTest*' before: 9 tests completed, 3 failed
./gradlew :app:testDebugUnitTest --tests '*RecordingLoopTest*'     stub loop: 12 tests completed, 8 failed
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
  first run: debug 517, 2 failed (a simulator tick racing RecordingLoopTest's db.close leaked into DispatchScreenTest;
             HandoverScreenTest's orchestrator had no transaction and read INSPECTED between writes). Both fixed
  after:     exit 0 in 140 s | core-domain 122 failed 0 | debug 517 failed 0 | release 460 failed 0 | lint 0 errors
```

## CONTINUE run, second session (24 Nov 2026, evening)

### Status at session start

- **Git:** `rebuild/trust-core`, HEAD `6fc0d77`, clean. Tags `gate-0`, `gate-2`
- **Owner's message:** memory pause over (16 GB, one heavy job at a time); the S24 connected, unlocked, Stay awake
  on (`PHONE = present`, `EMULATORS = 1`); Supabase MCP re-authorised with write access; custom SMTP (Gmail) set up
  on the dev project, the Magic link and Confirm signup templates carry `{{ .Token }}`, OTP length 6; automatic
  commit security reviews fail on SSL certificates in the plugin, so manual reviews stand in
- **Supabase MCP:** `select current_user` → `postgres`, `transaction_read_only` → `off`; `apply_migration` offered.
  `.mcp.json` URL has `project_ref=yjvoxozaoiarwbusyqke` (= `DEV_PROJECT`). Dev project empty: no tables, no
  migrations
- **Devices:** `adb devices -l` → `RZCY820AG0F device model:SM_S921E`. Free RAM 5.2 GB of 15.6 GB, no emulator

### Block 1 — Gate 1 and the dev project: `done`

- [x] Local check at HEAD first: `npx supabase db reset && npx supabase test db` → Files=4, Tests=238, PASS;
  `db advisors --local --fail-on info` → No issues found. `git diff 05b342e HEAD -- supabase/migrations` is empty,
  so the pinned Gate 3 build was rehearsed on exactly this schema
- [x] Applied `0000_legacy_schema`, `0001_lockdown` (back to back, so the legacy tables' open grants existed for
  seconds, on empty tables), `0002_trust_core` through MCP `apply_migration`, each the file's text
- [x] Hosted = local, proven by one fingerprint query run on both (md5 over function definitions in `public` and
  `private`, columns, constraints, indexes, policies, triggers, RLS flags, the anon/authenticated/PUBLIC privilege
  matrix on tables and functions, `private` schema usage, seed rows). All equal except one object Supabase creates
  with every hosted project, `public.rls_auto_enable()` (the `ensure_rls` event trigger's function); with it left
  out, functions and function grants match too
- [x] Advisors, first run: security WARN 1 (anon can execute SECURITY DEFINER `rls_auto_enable()`), WARN 14
  (authenticated can execute SECURITY DEFINER: our 13 RPCs plus `rls_auto_enable`), INFO 6 (`legacy_*` tables, RLS
  on and no policy); performance INFO 17 (unused indexes, empty database). No ERROR
- [x] Fixed test-first: `rls_auto_enable()` broke our own rule that anon and PUBLIC execute nothing in public.
  Red on hosted (the rule's query from `0002_deny_by_default.test.sql` returned `rls_auto_enable()` for anon,
  PUBLIC and, beyond the allow-list, authenticated) and red locally (the same function created on the local stack
  with the same grants). Fix: `0003_revoke_platform_function.sql` (`0c42310`), a guarded revoke, a no-op where the
  function doesn't exist. After it the `ensure_rls` trigger still works (a probe table in a rolled-back transaction
  came out with RLS on, and nothing was left behind)
- [x] Advisors after the fix: security WARN 13 (exactly the 13 RPCs on pgTAP's allow-list, the API by design) and
  INFO 6 (`legacy_*`, locked on purpose); performance INFO 17. No ERROR, no anon finding (`DECISIONS.md`)
- [x] Every rule of `0002_deny_by_default.test.sql` run as a query on hosted, in one transaction that first created
  a table, view, materialized view, function and sequence in public the way a migration does, then rolled back:
  14 checks (anon and PUBLIC table, column and function access; authenticated writes; both allow-lists in both
  directions; sequences; `create` on public; the realtime publication; role inheritance; `pg_default_acl`; the new
  objects' access), 0 violations each; 0 probe objects left afterwards
- [x] The real API, with the publishable key: `/auth/v1/settings` answers (email on, sign-up on); anon reading
  `product_profiles` → `42501 permission denied`; a wrong key → "Invalid API key"
- [x] `local.properties`: `blocked` then by the owner's deny rule `Edit(./local.properties)`, not worked around.
  The owner set it himself on 26 Nov; checked by a build that day (Session of 26 Nov)
- [x] Full proof and a clean clone (below). `gate-1` tagged

```text
local stand-in for rls_auto_enable() + npx supabase test db     Failed 1/35 (0001 #10), Failed 2/15 (0002 #6-7)
docker exec … psql < 0003_revoke_platform_function.sql
npx supabase test db                                             Files=4, Tests=238 ... Result: PASS
npx supabase db reset && npx supabase test db                    Applying migration 0003_…  Tests=238 ... PASS
npx supabase db advisors --local --fail-on info                  No issues found
MCP list_migrations: 20261124144756 0000_legacy_schema | 20261124144802 0001_lockdown |
                     20261124144939 0002_trust_core   | 20261124145228 0003_revoke_platform_function
fingerprint (local = hosted): columns 127 bb4bff5d… | constraints 63 d6d60f9a… | indexes 36 b5dfa4ac… |
  policies 12 1f160754… | triggers 1 4f5b8215… | rls 18 8e0ca350… | table_grants 12 f39cbf5f… |
  product_profiles 2 5edca2ec… | private_usage anon:false,authenticated:true,public:false |
  functions 24 db9e0661… and function_grants 15 21d63d1b… (hosted without rls_auto_enable)
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
BUILD SUCCESSFUL in 5m 35s
core-domain 122 failed 0 | debug 480 failed 0 | release 423 failed 0 | skipped 0
clean clone of 0c42310 in D:\cch-tools\clone-gate1, no local.properties, env -i with JAVA_HOME and ANDROID_HOME:
./gradlew assembleDebug testDebugUnitTest --no-daemon          BUILD SUCCESSFUL in 5m | debug 480 failed 0
```

Gate 1 criteria (MASTER 5.1): clean-clone build with `JAVA_HOME` (and the SDK) only, yes (above); default tests
offline and green, yes (`OfflineTestGuardTest` in the 480); pgTAP proves anon locked out, yes (238/238, and on
hosted the same rules' queries plus the REST refusal); advisors show no RLS errors, yes (no ERROR, no RLS finding
beyond the deliberate `legacy_*` deny-all); schema exported, yes (`app/schemas`, `ColdChainDatabaseMigrationTest`).
The Gate 1 reviewer ran on 30 Oct (`DECISIONS.md`, "Gate 1 review" lines); its findings were fixed then. What was
missing since was only the dev project, checked here mechanically against the same tests.

### Block 3 — Gate 3 on the hosted dev project: `done`

- [x] Worktree `D:\cch-tools\wt-gate3-05b342e` at `05b342e` (`git worktree add --detach`); `assembleDebug` with
  `CCH_APP_SUPABASE_URL`/`KEY` (dev URL, publishable key): BUILD SUCCESSFUL in 2m 44s. Its `BuildConfig`: dev ref 1,
  demo ref 0, `127.0.0.1` 0, `placeholder` 0, `sb_publishable_` 1, `sb_secret_` 0. `./gradlew --stop`, then the
  emulator
- [x] The S24's old debug app data cleared with the owner's yes (`pm clear`), the build installed; the emulator's app
  uninstalled and installed fresh
- [x] Hosted email-code sign-in through the app, supabase-kt with the `sb_publishable_` key: courier on the S24 (the
  owner typed the code there), receiver and sender on the emulator (codes from the owner). `profiles`: Gate3
  Courier/courier, Gate3 Receiver/receiver, Gate3 Sender/sender, each with a device
- [x] Sender created `SHIP-D9E438` with both people in the form (members: sender, courier, receiver) and dispatched it
  (server `DISPATCHED`)
- [x] Courier on the S24 opened it by ID through "Manual QR Payload" and took custody at 20:56:32 (server:
  `IN_TRANSIT`, `START` leg 1 to Gate3 Courier, first reading uploaded). Found: the owner's YouTube picture-in-picture
  window covered the Take Custody button; tapping the button's right part worked
- [x] Receiver signed in again on the cleared emulator and opened it on Transit: `IN_TRANSIT`, telemetry log 2
  readings at 20:58:37
- [ ] `SHIP-D9E438` can't reach PASS. At about 21:01 the S24 went to another app (Brave) and the `05b342e` build
  records only while Transit is on screen (no foreground service until 5.2), so the last reading is seq 5, received
  21:00:38. The gap is past the 3-minute limit, so this shipment can only end UNKNOWN. It is left IN_TRANSIT on the
  server. A real-world case for 5.2's foreground service
- [x] Then Claude Code stopped the emulator for low memory while the session waited for the owner. Restarted on
  his "go", with the phone left alone
- [x] **The run, `SHIP-3E4005`, PASS:** the sender (emulator, new code) created it with both people and dispatched it
  (21:51:58). The courier (S24, still signed in) opened it by ID and took custody at 21:53:12, and recorded 6
  readings. The receiver (emulator cleared, new code) watched them sync on Transit. The courier confirmed at
  21:59:04, 48 s after the last reading, naming the receiver; the screen said "Confirmed. Waiting for the other person". The receiver
  recorded PASS at 22:00:56, 2 min 40 s after the last reading, and the screen said "Custody has moved. The shipment
  is now ACCEPTED."
- [x] Server records (MCP): `shipments.status = ACCEPTED`; `handover_confirmations`: courier, no verdict, head
  `864c5b67`, device `f79114ed` at 21:59:04, and receiver PASS, head `864c5b67`, device `c49cdc4a` at 22:00:56: 2
  distinct devices, both on the server head; `custody_events`: leg 1 START → Gate3 Courier, leg 2 TRANSFER → Gate3
  Receiver; 6 readings. `f79114ed` is the courier's only device, and the courier only ever signed in on the S24
- [x] Security advisor afterwards: no ERROR; the 13 RPC WARNs and 6 `legacy_*` INFOs as accepted, plus a new auth
  WARN "leaked password protection disabled" (a project setting, and the app signs in with email codes, not
  passwords; owner's choice, HANDOFF). `gate-3` tagged
- Found on the way, for Phase 5 (the pinned build's screens):
  - The receiver's Handover screen stayed on "Waiting for Complete History" for more than 50 s with all 6 readings
    already on the phone. It went to PASS only after going back to Transit for a few seconds and returning. The
    history is marked complete by Transit's polling, not by having the readings
  - The `05b342e` build stops recording as soon as the courier's app leaves the screen (`SHIP-D9E438`). 5.2's
    foreground service is the fix
  - On the phone, a picture-in-picture window from another app can cover the Take Custody button; the button still
    works where it isn't covered
  - The emulator was used only with the phone; no two emulators at once, and none during a Gradle run

```text
MCP: shipments SHIP-3E4005 ACCEPTED | confirmations courier/-/864c5b67/f79114ed, receiver/PASS/864c5b67/c49cdc4a
     distinct_devices 2 | custody 1 START Gate3 Courier, 2 TRANSFER Gate3 Receiver | readings 6 | head 864c5b67
MCP get_advisors security: 0 ERROR | WARN 13 RPCs + 1 auth_leaked_password_protection | INFO 6 legacy_*
```

## CONTINUE run (from 24 Nov 2026)

### Status at session start (24 Nov)

- **Git:** `rebuild/trust-core`, HEAD `4a1116f`, clean except the new `docs/agent/CONTINUE_PROMPT.md`. Tags `gate-0`
  and `gate-2`, as the prompt says. The last 15 commits match its account (5.1b for AppRoot and Dispatch on top).
- **Dev project:** restored. `list_tables` answers (public schema empty), `list_migrations` answers (none),
  `get_project_url` is the dev URL. Still read-only in this session: `select current_user` gives
  `supabase_read_only_user`, `transaction_read_only = on`, and no `apply_migration` tool, though `.mcp.json` no
  longer says `read_only=true`. Block 1 `blocked`; owner asked at the top of HANDOFF to reconnect the MCP server
- **Email templates:** the owner says they can't be edited until he sets up custom SMTP, before the Gate 3 run.
  The sign-in flow stays as it is
- **Docker:** was stopped; started Docker Desktop, and the local stack's containers came back healthy
- **Disk:** C: 66 GB free, D: 272 GB free
- **Switches:** `PHONE = absent`, `EMULATORS = 2`, `FIRMWARE_OWNER = me`, `PROTOCOL_Q4 = owner`

### Step 1 — Housekeeping

- [x] 1.1 Dev project: `blocked` that morning (read-only MCP connection); done the same evening (second session,
  Block 1)
- [x] 1.2 Supabase CLI pinned at 3.3.0 in `package.json`, CI on the same version, `CLAUDE.md` and README say
  `npm ci` then `npx supabase`

```text
npm install && npx supabase --version            3.3.0   (a scoop 'supabase' on PATH is 2.122.0, hence 'npx')
npx supabase db reset && npx supabase test db    Files=4, Tests=233 ... Result: PASS
npx supabase db advisors --local --fail-on info  No issues found
```

- [x] 1.3 CC010 (manifest lock): one test covered it, through `logger_id` only. The trigger compares five columns
  (those the genesis hash commits to), so dropping any of the other four from it passed every test. Added one check
  per column and one that other columns still update (`0003_trust_core.test.sql`, plan 164 → 169)

```text
npx supabase test db                              Files=4, Tests=238 ... Result: PASS
mutants, the trigger rewritten in the local DB without one column each:
  without id | sender_id | product_profile_id | created_at:   Failed 1/169 subtests, Result: FAIL (each)
npx supabase db reset && npx supabase test db     Tests=238 ... Result: PASS
```

- [x] 1.4 Escalation worker on a cold start. **The worker already got its repositories**: its lazy
  `RepositoryProvider.getAlertRepository(context)` builds everything, and the Supabase client takes its settings from
  `BuildConfig`, not the activity. `theWorkerEscalatesAStoredAlertWhenNoActivityHasStarted` (a real Room database, no
  activity, App Startup run as a real process start does) passed as soon as it compiled, so it is a guard for later,
  not a failed-first test. **The defect found next to it:** `MainActivity.onCreate` called `initialize(context)` on
  every creation, rotation included, and each call built a second set (new scope, a second owner-check collector,
  a second `SyncServiceImpl`, a new history coordinator) while the first set's jobs kept running, so a retained
  ViewModel and the screens could hold different coordinators. `initialize(context)` now runs once per process (an
  `initialized` flag set by its last line, so a call that failed part-way is retried; `reset()` clears it). A test
  of that retry was tried and dropped: no input made the build fail part-way in Robolectric (a blank URL doesn't
  throw), and `RepositoryProvider` is deleted in 5.1c. `HiltWorker` in 5.1c is still the real fix
  - `AlertEscalationColdStartTest`: `anActivityOpeningAfterTheWorkerReusesWhatTheWorkerBuilt` and
    `rotatingTheActivityDoesNotStartASecondSetOfBackgroundJobs` failed first (`expected same:<AlertRepositoryImpl@…>
    was not:<AlertRepositoryImpl@…>`, `expected same:<CoroutineScope(…)>`); `afterAResetTheNextInitializeBuildsAFreshSet`
  - The guard exposed a test-isolation gap: `AppSmokeTest.someoneSignedOutCanOnlySeeSignIn` failed in 3 of 4 full
    runs (hung past 10 s; 0.3 s when it passes). A launch books an escalation check that ran on WorkManager's real
    threads and could finish after the test's `reset()`, building everything on that test's context for the next
    test to inherit; the old rebuild on every `onCreate` hid it. `AppSmokeTest` now uses a test WorkManager whose
    workers do nothing (it checks a check is booked; two other classes run the worker)

```text
./gradlew :app:testDebugUnitTest --tests '*AlertEscalationColdStartTest*'   first: 2 of 4 failed (above)
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease --rerun-tasks --continue
run 1: BUILD SUCCESSFUL in 3m 59s   run 2: BUILD SUCCESSFUL in 3m 50s
core-domain 118 failed 0 | debug 447 failed 0 | release 390 failed 0 | skipped 0     (both runs)
```

- [x] 1.5 Security review of `gate-2..HEAD` by one fresh subagent in one sitting (`docs/agent/security_review.md`):
  no path to a false PASS; high 0, medium 1, low 2, info 6. Fixed test-first: S1 (a forged batch header claiming
  `0..Long.MAX_VALUE` ran the phone out of memory before any signature check), S2 (more records than asked for),
  S3 (GATT callbacks finishing someone else's operation), S4 (the device's ID writing its own reason line), S5 (NaN
  shown within range), S6 (secret keys or quotes in BuildConfig values), S9 (`drive.sh` passing text to the device's
  shell). S7 to the owner (OTP expiry, HANDOFF), S8 accepted until 4.3 (`DECISIONS.md`). Triage table at the end of
  the review

```text
first runs:  BleLoggerLinkTest  aHeaderClaimingAHugeRange… / theVerifierRefusesAHugeClaimedRange…  java.lang.OutOfMemoryError
             aBatchWithMoreRecordsThanAskedForIsMalformed   AssertionError
             LoggerHandoverCheckTest.aLoggerIdCanNotWriteItsOwnReasonLines, TemperatureCardTest.aValueThatIsNotANumber…  AssertionError
             GattOperationSlotTest  4 of 7 failed against a stub; with the matching line removed (the old behaviour) 4 of 7 fail
build check: CCH_APP_SUPABASE_KEY=sb_secret_… / a service_role JWT / a value with a quote -> configuring ':app' fails
             ("is a secret key…", "contains a quote or a backslash."), value never printed; an anon JWT builds
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease --continue
BUILD SUCCESSFUL in 2m 50s
core-domain 122 failed 0 | debug 455 failed 0 | release 398 failed 0 | skipped 0
```

### Step 2 — Finish 5.1

- [x] `TransitViewModel`: take custody, open by code (nothing built from the code's text), notice custody moving
  away (recording stops, the handed-over dialog shows) or coming here while watching, the custodian's location
  upload and a watcher's location fetch, and sending readings while recording. Its polling runs only inside
  `runWhileVisible()`, which the screen runs from a `LaunchedEffect`, so it stops when the screen leaves, as before
  (a navigation entry's ViewModel outlives a tab switch). The shipment and the mode are in `SavedStateHandle`: after
  process death the shipment comes back attached as the custodian's or a watcher's, as the server says, and
  recording does **not** restart on its own; that waits for 5.2's foreground service (in a release build there is
  no Resume button, so it stays stopped until then). The location client and permission prompt stay in the screen
  until 5.2. New Hilt bindings in `LegacyProviderModule`: `ColdChainRemote`, `TelemetryRepository`, `SyncService`,
  `@DeviceId String`, `AlertChecks`. No visible text changed (the honesty pass is Step 4)
  - `TransitViewModelTest`, 12 tests: 10 of the first 11 failed against a stub. `nothingIsPolledOnceTheScreenIsAway`
    passed against the stub (it polls nothing), so it was checked with a mutant that starts the polling in `init`:
    the run then hangs on the endless loops, each test failing at `runTest`'s 60 s timeout. Stopped by hand
  - Emulator, debug local mode: create, dispatch, Start Journey; Transit records (`transit-vm-01-recording.png`);
    landscape and back keep the shipment and recording (`transit-vm-02`); `am kill` in the background, relaunch:
    Transit shows `SHIP-1CC9B9` with both readings and "Resume" (`transit-vm-03-after-kill.png`); Resume records a
    third reading. `logcat`: 0 FATAL, 0 ANR

```text
./gradlew :app:testDebugUnitTest --tests '*TransitViewModelTest*'      against the stub: 11 tests completed, 10 failed
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
BUILD SUCCESSFUL in 5m 7s
core-domain 122 failed 0 | debug 467 failed 0 | release 410 failed 0 | skipped 0
```

- [x] `HandoverViewModel`: every server read, release, refresh, record (server confirmation or, in local mode, the
  orchestrator, which works the verdict out again), request replacement and retry moved out of the screen; the VVM
  and shake-test answers in `SavedStateHandle`; server reads only inside `runWhileVisible()`. The pure display
  calculations (breach, integrity, the verdict shown) stay in the screen as functions of its state.
  `HandoverViewModelTest`, 5 tests, 5 failed against a stub first. `HandoverScreenTest` (14 tests, the behaviour
  net) passes with its assertions unchanged; it now builds the screen's ViewModel from what `RepositoryProvider`
  hands out, and its waits idle Robolectric's main looper, which `viewModelScope` resumes on (the same change
  `DispatchScreenTest` needed). Before that change 3 local-mode tests timed out: the record ran, but only after
  the wait gave up
- [x] `AlertsViewModel`: the shipment's alerts, the filter (in `SavedStateHandle`), acknowledge and escalate.
  `AlertsViewModelTest`, 5 tests. **Not failed first:** the stub was overwritten before the run. Checked instead
  with a mutant (the "active" filter letting everything through): 1 of 5 failed
- [x] **Full proof and emulator checks for Handover and Alerts: not run** at first (done later the same evening:
  the S24 device checks and the full proof below). At about 18:20 Claude Code stopped the
  emulator and the full proof run because the PC was critically low on memory (2.6 GB free of 15.6 GB; idle
  Gradle daemons held about 4 GB, since stopped). Its instruction is not to restart them without the owner, so
  only targeted foreground runs were made (below); `lintDebug`, `assembleRelease`, the release unit tests and the
  device checks wait for the owner (HANDOFF, top)

```text
./gradlew :app:testDebugUnitTest --tests '*HandoverViewModelTest*'   against the stub: 5 tests completed, 5 failed
./gradlew :app:testDebugUnitTest --tests '*AppSmokeTest*' --tests '*HandoverScreenTest*' --tests '*HandoverViewModelTest*'
    --tests '*AlertsViewModelTest*' --tests '*TransitViewModelTest*' --tests '*AlertNotificationsTest*' --no-daemon
BUILD SUCCESSFUL in 1m 11s      56 tests, 0 failed (AppSmokeTest opens every tab through Hilt)
```

- [x] Fix found by review before stopping: these ViewModels outlive a tab switch, and per-shipment state (a
  recorded result, the server's notice, custody and members, the courier's last location, the inspection answers)
  carried over to the next shipment opened, where the old `remember` state had reset. Now reset whenever the
  shipment changes, and the VVM and shake answers are saved with the shipment they were given for.
  `openingAnotherShipmentClearsWhatBelongedToTheLastOne`, `inspectionAnswersSavedForOneShipmentAreNotRestoredForAnother`
  (Handover) and `openingAnotherShipmentClearsTheLastOnesLocationAndCustody` (Transit) failed first; with the reset
  removed again, the first fails on shipment A's result showing under B. Same targeted classes afterwards: 45 tests, 0 failed
- [x] Device checks on the S24 (24 Nov, 22:05 to 22:20, HEAD `87d130e` debug build, owner's go; the phone's
  rotation settings saved first and restored after: `accelerometer_rotation=1 user_rotation=0`). Return after a kill
  is through Recents, as a user does: `monkey`'s launcher intent made Samsung start a *new* activity record with no
  saved state, so that first result says nothing about the app
  - Sign-in: cold start `TotalTime: 1190` ms; a typed email survives landscape and back, and `am kill` in the
    background with the return from Recents (redrawn from a cold process in 995 ms)
  - Local mode `SHIP-7976E2`: create, dispatch, Resume records (1 reading)
  - **Handover, killed while open: comes back "No Active Shipment"** (the defect predicted above). After opening
    Transit, which re-attaches the shipment, Handover shows it again with **Stage 2 and Passed still selected**: the
    inspection answers survive the kill (`SavedStateHandle`). Recording doesn't restart by itself ("Resume"), by
    design until 5.2
  - **Alerts, killed while open: comes back "No Active Shipment"**, the same defect. After Transit, Alerts shows
    `SHIP-7976E2` again. Whether the ACKED filter survived can't be read from the layout dump (the chips expose no
    selected state), so it stays proven at the ViewModel level only
  - `logcat`: 0 FATAL EXCEPTION, 0 ANR in the app
- [x] **Fixed test-first:** the saved shipment is restored where every screen goes through it. `AppRootViewModel`
  saves the simulator's shipment and whether it is watched, and puts it back through `ActiveShipmentRestorer` once
  the main app opens (local mode, or after the owner check); the restorer is Transit's old restore, moved, which
  Transit now also calls. `AppRootViewModelTest`: `aShipmentOpenOnAnyScreenComesBackAfterTheProcessIsKilled`,
  `aShipmentBeingWatchedComesBackOnlyWatched`, `nothingComesBackUntilThePhoneHasCheckedWhoIsSignedIn` failed
  against the stub; `aShipmentThisPhoneNoLongerHoldsDoesNotComeBack` passed against it (a guard). Mutant: without
  the wait for the main app, `nothingComesBack…` fails. Transit's 13 tests unchanged apart from the constructor
  - S24, local mode `SHIP-D77515`: killed on Handover, back from Recents straight onto `SHIP-D77515` with Stage 3
    and Failed (freeze damage) still selected; killed on Alerts, back on `SHIP-D77515`; recording waits ("Resume");
    logcat 0 FATAL, 0 ANR

```text
./gradlew :app:testDebugUnitTest --tests '*AppRootViewModelTest*' --tests '*TransitViewModelTest*'
    against the stub: 23 tests completed, 3 failed
    mutant (no wait for RootScreen.Main): 10 tests completed, 1 failed (nothingComesBack…)
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
BUILD SUCCESSFUL in 4m 19s
core-domain 122 failed 0 | debug 484 failed 0 | release 427 failed 0 | skipped 0
```
- [x] **5.1c plan (24 Nov, 22:45; carried out below).** Inventory of `RepositoryProvider` in tests (members used):
  - Through Hilt: `AppSmokeTest` (6, `ActivityScenario`: `initialize`, `accountRepo`, `safetyEngine`, `reset`);
    `AlertEscalationColdStartTest` (4: `initialize`, `getAlertRepository`, `alertRepository`, `syncScope`, `reset`;
    its rule: one set per process, worker and activity share it)
  - Built by the test: `HandoverScreenTest` (14, `createComposeRule`; simulator, orchestrator, server handover,
    history, account, remote, device ID); `LocalOwnerTest` (3, the session gate); `AlertNotificationsTest` (14,
    `initialize`/`reset`); `SyncServiceTest` (17, `syncScope`/`reset` cleanup); `RoomRepositoriesTest` (11,
    `handoverOrchestratorFor`); debug `ChaosScreen` uses `chaosEngineService`
  - Steps, each with a full proof, `--stop` and a commit: (1) the session gate as its own class, `LocalOwnerTest` on
    it; (2) per-process jobs (owner check, chaos watcher, sync scope) behind one start-once object; (3) `AppModule`
    with `@Singleton` providers, the Hilt-path tests converted in the same commit, `LegacyProviderModule` gone,
    chaos provided from debug and release; (4) `HiltWorker`, `Configuration.Provider`, only WorkManager's entry
    removed from `InitializationProvider` (Supabase Auth needs androidx.startup); (5) delete `RepositoryProvider`,
    proof `grep -rn RepositoryProvider app/` empty; (6) role-based navigation, test-first. Converted tests keep their
    assertions (diff the `assert` lines), recorded in DECISIONS. Emulator check after (4) and (5)
- [x] 5.1c steps 1 to 5 done: `LocalSession` (`6a521f6`); then `AppModule`, `ServerModule`, `AppStartup`,
  `HiltWorker` with `Configuration.Provider`, the manifest entry, the debug chaos lab's entry point, tests converted,
  `RepositoryProvider` and `LegacyProviderModule` deleted (commit below). `ShipmentHistorySyncCoordinator.clear()`
  failed first (`clearingForgetsEveryShipmentsStateAndServerHead`, AssertionError), then passed. Test setup changes
  and the three retired tests are in DECISIONS; the assertion diff shows only provider mechanics removed
- [x] Emulator check of the Hilt wiring (HEAD `dccf20c` debug, cch_pixel_api36, Android 16): cold launch
  `TotalTime: 10118` ms to sign-in (10.3 s before the change, first launch of an unoptimised debug build); logcat
  `Worker result SUCCESS … class=com.coldchain.handshake.alerts.AlertEscalationWorker` (built by Hilt's factory, no
  "Could not create Worker"); local mode `SHIP-51622E` records; killed on Handover, back from Recents on
  `SHIP-51622E` with Stage 2 still checked; 0 FATAL, 0 ANR
- [x] 5.1c step 6, role-based navigation: `Screen.tabsFor(role)`. A courier or receiver has no Dispatch tab and opens
  on Transit (the server refuses them a shipment anyway); a sender opens on Dispatch and keeps Handover (replacement
  requests live there); debug local mode keeps every tab. `ScreenTabsTest` failed first on the courier/receiver case
  (the other two pass against the stub, which returned every tab). `AppSmokeTest`: the courier launch now checks its
  own tabs and that Dispatch is absent; a sender launch opens every sender tab (new). Screens a courier can still
  reach from inside (Transit's "Go to Dispatch Hub" button) are for the honesty pass in Step 4
- [x] **5.1 done.** Corrected on the way: my new `rotatingTheActivity…` test asserted every startup job stays
  active, which fails in release, where the empty chaos lab's watcher ends at once; it now asserts the owner check
  stays active, which is the rule

```text
./gradlew :app:testDebugUnitTest --tests '*ScreenTabsTest*'     against the stub: 3 tests completed, 1 failed
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
core-domain 122 failed 0 | debug 486 failed 0 | release 429 failed 1 (rotatingTheActivity…, line 110, above)
after the fix: ./gradlew :app:testReleaseUnitTest   release 429 failed 0
```

```text
grep -rn RepositoryProvider app/ --include=*.kt          (no code references; comments only, if any)
./gradlew assembleDebug assembleRelease                   BUILD SUCCESSFUL (Hilt validated the graph)
./gradlew :app:testDebugUnitTest --tests '*ShipmentHistorySyncCoordinatorTest*'   against an empty clear(): 6 tests, 1 failed
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
BUILD SUCCESSFUL in 3m 50s
core-domain 122 failed 0 | debug 482 failed 0 | release 425 failed 0   (484 + 1 new - 3 retired = 482)
```

- How to resume without another stop: run the full proof in the **foreground** (3 to 5 minutes), then
  `./gradlew --stop`, and only then start the emulator. Never end a turn with a heavy job running in the background
  (still the rule)
- Superseded: "Next: the full proof and the device checks above, then 5.1c". Both were done on 24 Nov (above)

## FINISH run, Block S: setup (23 Nov 2026)

- [x] S1 User env vars, checked by reading them back with `[Environment]::GetEnvironmentVariable(..., 'User')`:
  `JAVA_HOME=D:\cch-tools\jdk-17.0.20.1+1`, `ANDROID_HOME=C:\Users\LENOVO\AppData\Local\Android\Sdk`,
  `ANDROID_AVD_HOME=D:\Android\avd`, `GRADLE_USER_HOME=D:\cch-tools\gradle-home` (see `DECISIONS.md`)
- [x] S2 Emulator: done, proof below. Installed `cmdline-tools/latest`, `emulator` and `system-images/android-36/google_apis/x86_64`
  with `android sdk install`. The first try failed on `platform-tools`, because the running adb held `adb.exe`; the
  installed platform-tools 37.0.1 is kept
- [x] S3 Phone: `adb devices -l` lists `RZCY820AG0F device model:SM_S921E`. `getprop`: SM-S921E (Galaxy S24),
  Android 17, API 37
- [x] S4 Dev project: done 24 Nov (CONTINUE run, second session, Block 1). On 23 Nov it was `blocked`. `get_project_url` answers; `list_tables` says "Database is possibly not accessible";
  `get_publishable_keys` says "Project is paused. Restore it and try again". `.mcp.json` still has `read_only=true`
  and there is no `apply_migration` tool. Owner asked, top of `HANDOFF.md`
- [x] S5 Disk: C: 73 GB free (78% used), D: 283 GB free
- [x] S6 `local.properties`: the dev URL and a placeholder key appended. `./gradlew assembleDebug` then grep of the
  debug `BuildConfig.java`: demo ref 0, dev ref 1, placeholder key 1; the APK's dex: demo ref 0, dev ref 2

S2 done: `avdmanager create avd -n cch_pixel_api36 -k 'system-images;android-36;google_apis;x86_64' -d pixel_7`
put the AVD in `D:\Android\avd`; `emulator -accel-check`: "WHPX(10.0.26200) is installed and usable";
`adb devices` lists `emulator-5554 device`, Android 16 (API 36), `sdk_gphone64_x86_64`.

## FINISH run, Block 2: on a real device

- [x] B2.1 `KeystoreDeviceSignerTest`, its first run on real hardware:
  `ANDROID_SERIAL=RZCY820AG0F ./gradlew connectedDebugAndroidTest`: "Running tests on devices: SM-S921E - 17",
  `tests="3" failures="0" errors="0"`. On the emulator: "cch_pixel_api36(AVD) - 16", `tests="3" failures="0"`
- [x] B2.2 Smoke pass on the emulator: `done`. On the phone: done 24 Nov (see the end of this block); on 23 Nov it
  was `blocked`, the phone is locked (`isKeyguardShowing=true`);
  owner asked to unlock it. Screenshots in `docs/agent/evidence/emu-*.png`:
  - first launch after install: one ANR, "No response to onStartJob". The ANR trace shows the main thread in
    Compose's first layout pass (4.3 s of CPU on a fresh, unoptimised debug install with a software GPU), while
    WorkManager's job waited for it. Not seen on any later launch; cold start 10.3 s, "Skipped 143 frames". To
    compare on the phone before treating it as an app fault
  - the sign-in screen's title drew under the status bar (portrait and landscape). Fixed: `AccountColumn` pads
    for `WindowInsets.safeDrawing` (`emu-09`)
  - the typed email survives rotation, going to the background and back, and `am kill` while in the background
    (`rememberSaveable`)
  - debug local mode: create, dispatch, start journey, and the Transit screen records one reading per real
    minute (by design since the wall clock was wired in; the "+60s/tick" label is stale), and keeps recording
    through rotation
  - noted for Phase 5: the local-mode QR still shows the old `CCH:SHIP:...` text labelled "Authoritative QR
    Identifier"; the Dispatch "Scan / Identify QR" label wraps mid-word

```text
./gradlew testDebugUnitTest      (after the insets fix)
BUILD SUCCESSFUL in 4m 36s
debug tests 423 failures 0 errors 0 skipped 0
```

Phone, so far (23 Nov 20:43, after the owner unlocked it): cold start `TotalTime: 1109` ms, no skipped frames, no
ANR, so the emulator's first-launch ANR came from the emulator. The sign-in title clears the status bar. The
rest of the phone pass waits: the owner was using the app on the phone, and its screen showed personal data, so
no phone screenshot was kept.

Phone pass finished on 24 Nov (CONTINUE run, Step 2 device checks): sign-in visible, rotation, background and
return, and process death with the return from Recents, read through the layout dump rather than screenshots. B2.2
`done`.

## FINISH run, Block 3 rehearsal on the local stack (23 Nov, while the dev project is paused)

Not Gate 3: the local Docker stack, not the hosted dev project, and one emulator standing in for each person by
clearing its storage between roles (`pm clear`, which also gives a new device ID and key). It shows the screens and
the server agree before the owner's real run.

- Local stack re-checked at HEAD with Supabase CLI 3.3.0 and Postgres 17.12 (both newer than the last run):
  `npx supabase db reset && npx supabase test db`: "Files=4, Tests=233 ... Result: PASS";
  `db advisors --local --fail-on info`: "No issues found"; `LiveSupabaseIntegrationTest` 3 of 3
  (`someoneNotSignedInSeesNothing`, `aShipmentGoesFromSenderToReceiverAndEveryShortcutIsRefused`,
  `aStrangerSeesNoneOfAnotherShipment`). `./gradlew assembleRelease`: BUILD SUCCESSFUL
- **Email-code sign-in on a device, against a real auth server, for the first time:** courier, receiver and sender
  each signed up with a 6-digit code read from the local mail catcher. The local emails first carried only a link;
  `supabase/config.toml` now gives them the code (`{{ .Token }}`), as the hosted project's dashboard must too
- The sender created `SHIP-9E1C93` with both people in one step and dispatched it; the courier opened it by ID,
  took custody and uploaded 6 signed readings over 5 minutes; the courier confirmed naming the receiver; the
  receiver opened it on a "new phone" and recorded UNKNOWN with a reason. Server records:

```text
handover_confirmations: courier|-|-|7a42d6c0   receiver|UNKNOWN|Rehearsal: courier phone stopped recording|fde37766
shipments.status: QUARANTINED
custody_events: START|1|Case   TRANSFER|2|Rita
```

- The receiver's screen showed UNKNOWN ("gap in the readings") and offered no PASS, because more than 3 minutes
  had passed since the courier's last reading: confirming stops the courier's recording (`HandoverScreen` calls
  `stopContinuousSimulation`), and the receiver's verdict runs to the receiver's own "now"
- **PASS rehearsed** with a fast switch on the one emulator, `SHIP-744113`, at commit `05b342e`'s app code: the
  courier confirmed 20 s after the third reading; the receiver signed in, opened the shipment on Transit, let it
  sync, and recorded PASS 2 min 11 s after that reading. The screen said "Custody has moved. The shipment is now
  ACCEPTED." (`rehearsal-05-receiver-pass.png`). Server records:

```text
handover_confirmations: courier|-|5c9d923c   receiver|PASS|97ffaea7
shipments.status: ACCEPTED
custody_events: START|1|Case   TRANSFER|2|Rita
```

- A first PASS attempt (`SHIP-7D445E`) ran out of time: the receiver went straight to Handover, which waited on
  "Waiting for Complete History" until the Transit screen had downloaded the readings. Found: the Handover screen
  doesn't fetch the history itself. HANDOFF's steps now say to stay on Transit first. Phase 5's download work fixes it
- Screens differed from HANDOFF's old steps: the member emails are in the creation form; the scanner's text box is
  "Manual QR Payload" with "Apply Code"; a notifications prompt comes first. HANDOFF's Gate 3 steps rewritten
- Found and fixed: the Transit screen showed "5.0°C, WITHIN PRODUCT RANGE" before any reading existed (a value
  nobody measured), and in monitor mode could show the simulator's temperature from another shipment. Now
  `TemperatureCard` shows "--, NO READING YET" and takes its range from the shipment's product
  (`TemperatureCardTest`, 3 tests, failed first)
- Noted for Phase 5: "Worker ID" shows the sender's account UUID; jargon such as "Supabase Polling ~3s",
  "Phone A/B" and "Simulated Time (+60s/tick)" is still on screen
- Before the owner's run, the debug APK must be rebuilt without `CCH_APP_SUPABASE_*`, so it points at the dev project

## FINISH run, Block 4: Phase 4, app side (against the draft protocol)

Order changed so the Gate 3 run still matches HANDOFF's steps (`DECISIONS.md`, 23 Nov): items 4 and 2 first,
items 1 and 3 after `gate-3`.

- [x] 4.4 BLE adapter. `LoggerGatt` (codec) and `BleLoggerLink` (a `LoggerLink` over a `GattConnection`) in
  `:core-domain`; `AndroidGattConnection` (Android's `BluetoothGatt`) in `:app`, compiled and linted, not run
  (`HARDWARE = none`). Tests: `LoggerGattVectorsTest` against new Python GATT vectors, and `BleLoggerLinkTest`
  against `FakeLoggerGatt`, a `VirtualLogger` behind the byte layout: every MTU from 148 to 517, MTU negotiation,
  an MTU too small, nothing new, a lost page, a silent logger, the challenge, a wrong protocol version, and
  `LoggerIngestor` back-filling over Bluetooth and resuming after a failed read. Writing the fake found that the
  challenge's 104-byte answer needs a larger MTU too. Firmware: `Step4Vectors` added (39 tests, 38 failing by design)
- [x] 4.2 (domain half) The logger check at handover: `LoggerHandoverCheck` reads every record since custody
  started and a head over a fresh nonce, verifies against the key on record and compares with the readings.
  `LoggerHandoverCheckTest`, 19 cases: an honest journey passes; edited, removed and invented readings, another
  logger's key, another logger's ID, a history edited in transit, a replayed head, a history cut short of the head
  and the test key all FAIL; no key, an unreadable logger, overwritten records, a sensor fault and records not yet
  in the shipment are UNKNOWN; no logger reachable is NOT_CHECKED. `HandoverVerdictEngine` and
  `HandoverOrchestrator` take the outcome and can only lower the verdict (`LoggerCheckVerdictTest`, two orchestrator
  tests). Two mutants applied by hand (a hole treated as a late tail; overwritten readings compared anyway): each
  failed its test, then reverted. The screen wiring waits for item 1
- [ ] 4.1 Logger readings in the app. `gate-3` is tagged, so this runs after Gate 5 (CONTINUE Step 5). Carry forward: `LoggerHandoverCheck` returns NOT_CHECKED
  whenever no link is passed. The screen wiring must pass UNKNOWN instead when the shipment has a real (hardware)
  logger enrolled that this phone can't reach, and needs a test for it; only a virtual logger on another phone may
  stay NOT_CHECKED
- [ ] 4.3 Server: logger key and start sequence on the shipment. After Gate 5 (CONTINUE Step 5), backward
  compatible with the `05b342e` app
- [ ] Gate 4: from 26 Nov `FIRMWARE_OWNER = claude` and `HARDWARE = none`: closes as "met without hardware" once
  the firmware host tests pass and items 4.1 and 4.3 are done (superseding "waiting for the owner's firmware")

```text
./gradlew :core-domain:test --tests '*.ble.*'     against stubs: 19 tests completed, 18 failed
                                                   implemented: core-domain 94 failed 0
ctest --preset host-tests                          3% tests passed, 38 tests failed out of 39
./gradlew :core-domain:test --tests '*LoggerCheckVerdictTest*'   first: compile errors, no 'loggerCheck' parameter
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug --continue
BUILD SUCCESSFUL in 1m 51s
core-domain 118 failed 0 | debug 425 failed 0 | release 368 failed 0 | skipped 0
```

Also fixed on the way: `SupabaseAccountRepositoryTest.aCachedProfileIsReplacedWhenTheServerAnswers` failed once
in a full run (`expected:<[New] Name> but was:<[Old] Name>`): the account state turned Ready before the profile was
cached. The cache is now written first.

## Status at session start (6 Nov 2026, 22:00, new session after a usage limit)

Checked by commands run in this session. `docs/agent/STATUS.md` says what is proven and on what.

- **Git:** branch `rebuild/trust-core`, clean, HEAD `cfb2e85`. All 11 commits the last session named exist.
  `d1698b9` adds `LiveSupabaseIntegrationTest`; `2045c09` only changes docs. Tags: `gate-0` only. `gate-1` was held
  back on purpose until the dev project is reachable; the owner is asked in `HANDOFF.md` Q2. No stashes. No remote
  holds this branch, so a bundle was made: `D:\cch-backup-20261106-2158.bundle`, `git bundle verify` says "is okay".
- **Deny rules:** `.claude/settings.json` is in place with them.
- **Docker:** works again. `docker version` answers (Docker Desktop 4.45.0, engine 28.3.3); the local Postgres
  container is healthy. GoTrue and pg_prove images are present; PostgREST and Kong are not yet.
- **Supabase MCP:** connected to dev project `yjvoxozaoiarwbusyqke`, `read_only=true`, no `apply_migration` tool.
  `get_project_url` answers; `list_migrations` times out, so the project is probably paused.
- **Devices:** `adb devices` lists none. No emulator, no system image, no AVD, no Android Studio. C: 11 GB free
  (97% used), D: 359 GB free. `JAVA_HOME` and `ANDROID_HOME` are still unset for the owner's shell.
- **Demo project:** HANDOFF still lists the lockdown as not done. Claude can't check it; the owner is asked.
- **Old Gate 2 reviewer:** its clone is `D:\cch-tools\clone-check` at `e4fc659`. Its mutation run had finished; it
  stopped before classifying its probe results and before a verdict. Its notes are copied to
  `D:\cch-tools\gate2-rev2-notes\`, off the C: Temp folder.

Clean run at `cfb2e85` (6 Nov, 22:06 to 22:14):

```text
npx supabase db reset && npx supabase test db
Files=4, Tests=233 ... Result: PASS
npx supabase db advisors --local --fail-on info          No issues found
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease --continue
BUILD SUCCESSFUL in 5m 21s
./gradlew :core-domain:test --rerun                      (it had been skipped as up to date)
core-domain 66 failed 0 | debug 412 failed 0 | release 355 failed 0 | skipped 0 | live test classes run: 0
```

The debug and release counts differ as expected: 352 tests are shared, 60 are debug-only (`src/testDebug`) and 3 are
release-only (`ReleaseHasNoDebugToolsTest`).

## Phase 0: Recon and safety net

- [x] 0.1 Inventory the folder. Done in `4f0257b` (`docs/agent/BASELINE.md`)
- [x] 0.2 Snapshot uncommitted items. Done, branch `local-snapshot-20261030` (two commits)
- [x] 0.3 Fetch, compare, create `rebuild/trust-core`. Done, `f858a87` on both sides, 0 ahead and 0 behind
- [x] 0.4 Environment check. Done in `720856f` (`docs/agent/ENVIRONMENT.md`, `docs/agent/HANDOFF.md`)
- [x] 0.5 Baseline build and tests. Done in `4f0257b`, see below
- [x] 0.6 Run `AuditFindingsTest`. Done in `4f0257b`, 5 of 5 pass (bugs present)
- [x] 0.7 Re-verify findings 1 to 16. Done in `4f0257b`, all stand (`docs/agent/BASELINE.md`)
- [x] 0.8 Create `CLAUDE.md`. Done in `720856f`

Proof for 0.5 and 0.6:

```text
./gradlew testDebugUnitTest --no-daemon --console=plain --continue -Dorg.gradle.java.home=... --init-script ...
BUILD SUCCESSFUL in 3m 50s
TOTAL 164 failures 0 errors 0 skipped 0
live test classes in results: []
```

**Gate 0:** the fresh reviewer failed criteria 3 and 5 (unsafe and broken commands, emulator steps that couldn't run). Both are fixed in the commit that follows this review, and the other six criteria and the counting claim passed.

## Phase 1: Unblock and lock down

- [x] 1.2 Make the live test opt-in, keep default tests offline (runs first). Done in `ca9c39c`
- [x] 1.1 Remove `org.gradle.java.home`, replace the interim commands in `CLAUDE.md`. Done in `060e8e8`
- [x] 1.3 `supabase init`, `0001_lockdown`, pgTAP. Done in `67aa2aa`
- [x] 1.3b Apply to dev project. Done 24 Nov through MCP (`0c42310` adds `0003`); see "CONTINUE run, second
  session", Block 1. The `blocked` note it carried until then is superseded
- [x] 1.4 Lockdown guidance in `HANDOFF.md`. Done in `720856f`
- [x] 1.5 Room schema export, no destructive fallback, migration test. Done in `31de93a`
- [x] 1.6 Move demo corruption and chaos paths to the debug source set. Done in `1bbe4cc`
- [x] 1.7 GitHub Actions workflow. Done in `d20b448`. Parsed and action tags checked; never run on GitHub (`PUSH_POLICY = local-only`)
- [x] 1.8 Fix the README ending. Done in `a02938a`

Proof:

```text
1.2  ./gradlew testDebugUnitTest --tests '*OfflineTestGuardTest'     failed first: reached the internet
     ./gradlew testDebugUnitTest --tests '*LiveSupabaseIntegrationTest*'
     > No tests found for given includes: [*LiveSupabaseIntegrationTest*]
1.3  with 0001_lockdown set aside:  Failed 26/35 subtests
     npx supabase test db:          Files=1, Tests=35 ... Result: PASS
     npx supabase db advisors --local:  No issues found
1.5  with the fallback present: expected IllegalStateException to be thrown, but nothing was thrown
     after removing it: tests 167 failures 0 errors 0
1.6  ./gradlew testDebugUnitTest testReleaseUnitTest
     Debug tests 170 failures 0 errors 0 | Release tests 157 failures 0 errors 0
     ./gradlew assembleRelease assembleDebug    BUILD SUCCESSFUL in 11m 38s
     release classes named ChaosScreen, InMemoryChaos*, ChaosAwareNetwork*: 0
Lint ./gradlew lintDebug     failed first on PermissionImpliesUnsupportedChromeOsHardware, fixed in ced35bd
     BUILD SUCCESSFUL in 36s (warnings only; dependency versions and kapt are Phase 3 work)
Clone git clone, no local.properties, JAVA_HOME and ANDROID_HOME only
     ./gradlew testDebugUnitTest    BUILD SUCCESSFUL in 6m 5s
     tests 170 failures 0 errors 0 | live ran: False
```

**Gate 1:** (superseded: `gate-1` tagged 24 Nov on the dev project's advisors and a clean clone; CONTINUE run,
second session, Block 1.) Then: met locally, not tagged. The fresh reviewer failed two criteria (the CI advisors step could never fail, and doc inaccuracies), and also found that anon could execute new functions and that materialized views escaped the tests. All are fixed, see `DECISIONS.md`. `gate-1` waits for the dev-project migration and advisors check, `HANDOFF.md` Q1.

## Phase 2: Safety logic

- [x] 2.1 `ProductProfile`, per-shipment selection, seed the freeze-sensitive profile. Done in `14ebab9`, stored in `cff7532`, picker in `fcf04f9`
- [x] 2.2 Heat and freeze evaluation; gaps produce UNKNOWN. Done in `14ebab9`
- [x] 2.3 Alerts on state change, deterministic IDs, timed escalation, notifications. Done in `be3d59b` (rules) and `e21b382` (Android)
- [x] 2.4 Lifecycle state machine. Done in `5972000`
- [x] 2.5 PASS, FAIL and UNKNOWN handovers, VVM and shake test, orchestrator recomputes the verdict. Done in `cff7532` (rules) and `fcf04f9` (screen)
- [x] 2.6 Flip audit tests into regression tests. Done in `fcf04f9`. The two chain tests stay in `AuditFindingsTest` until Phase 3

Proof:

```text
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug
BUILD SUCCESSFUL in 3m 3s
Debug tests 263 failures 0 errors 0 skipped 0 | live: False   (269 at the commit reviewed; see the Gate 2 review below)
Release tests 241 failures 0 errors 0 skipped 0 | live: False
./gradlew assembleRelease        BUILD SUCCESSFUL (at fcf04f9)
npx supabase test db             Files=2, Tests=47 ... Result: PASS
npx supabase db advisors --local --fail-on info     No issues found
Review mutations replayed in rolled-back transactions: 13 of 13 caught
```

Not proven yet: nothing has run on a phone or emulator. The handover and dispatch screens are driven by Robolectric, the others only open in the smoke test. WorkManager delays are read from its test harness, and no real delay has elapsed. The CI workflow has never run on GitHub.

**Gate 2, third review (6 Nov, fresh reviewer on a clean clone at `cfb2e85`):** UNMET on two items, both fixed and
re-checked in this session. Full review: `docs/agent/gate2_review.md`.

- **Probe d12** (one of the previous reviewer's probe tests): re-saving a moving shipment with a later journey start
  turned an UNKNOWN handover into PASS and ACCEPTED, and a start after the handover gave PASS (probe d3). Fixed in
  `4214448`: the start and the genesis are locked, adoption keeps the earlier start, and a start after the handover is
  UNKNOWN. Re-running the original probe now prints `saveShipment(later start) success=false ... verdict=UNKNOWN`,
  `start after handover=UNKNOWN` and, for the genesis (probe d13), `success=false`.
- **Mutant X3** (one of the reviewer's code changes): the offline queue could claim its history was synced and no test
  failed. `ServerHandoverTest.withNoSignalAndNoDeclaredVerdictNothingIsStoredAsAPass` now fails on that mutant
  (applied by hand, 1 of 11 failed, then reverted).
- 25 mutants run in all; the 6 survivors are explained in the review: 2 are equivalent, 4 are test gaps with no route
  to PASS (accepted in `DECISIONS.md`).

Before tagging, every `saveShipment` caller was checked against the new lock. One could hold the server's copy rather
than the stored one; fixed in `f8507ce` with a test, plus a test that a breach after the journey starts still
quarantines.

Proof at `f8507ce`, 7 Nov, tagged `gate-2`:

```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease --rerun-tasks --continue
BUILD SUCCESSFUL in 2m 27s
core-domain 75 failed 0 | debug 423 failed 0 | release 366 failed 0 | skipped 0
npx supabase test db        Files=4, Tests=233 ... Result: PASS    (at 189799e; no SQL changed after it)
live run on the local Docker stack: 3 of 3
```

**What Gate 2 means, in plain words:** the rules that decide PASS, FAIL and UNKNOWN are tested for heat, freezing,
gaps, alerts and every status change, and three fresh reviews tried to break them. The last one found one way to get
a false PASS, by moving a shipment's recorded start later, and that is now refused. All of this is tested on the PC,
not yet on a phone.

**Gate 2, earlier reviews:** first review not met, at `8b7c865`. It found four ways to reach an unsafe PASS, a lost second handover, no way to record a verdict when the cloud failed, and 32 surviving mutations. All were fixed test-first in the commits below, except what `DECISIONS.md` lists as accepted. A second fresh review is next. `gate-2` will then include the Phase 3 commits made in the meantime.

Proof after the fixes:

```text
Before the fixes, 12 of the new tests failed on behaviour, including each of the four unsafe PASS paths.
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease
BUILD SUCCESSFUL in 2m 30s
debug tests 342 failed 0 | release tests 298 failed 0 | core tests 53 failed 0
npx supabase test db        Files=4, Tests=233 ... Result: PASS
npx supabase db advisors --local --fail-on info     No issues found
```

## Phase 3: Toolchain upgrade and server trust

- [x] 3.0 Toolchain: Kotlin 2.x, KSP, AGP 9, Gradle, Java 17, SDK 36, Compose BOM, Room 2.8, supabase-kt 3.x. Done in `03ddefc`, `4db626c` and `047772f`. targetSdk 36, compileSdk 37, see `DECISIONS.md`

```text
Fresh clone of 047772f, no local.properties, --no-daemon:
BUILD FAILED only on SyncServiceTest.test9, a test race, fixed afterwards
Debug tests 270 failures 1 | Release tests 242 failures 0 | release APK built
This folder after the fix: ./gradlew testDebugUnitTest testReleaseUnitTest lintDebug
BUILD SUCCESSFUL in 3m 26s
Debug tests 271 failures 0 errors 0 | Release tests 243 failures 0 errors 0
```

- [x] 3.0b Extract `:core-domain`. Done in `7f89d30`, moved here from Phase 2
- [x] 3.1 Supabase Auth, profiles, roles, sign-in, session persistence, sign-out. Done in `01c8e38`. Email-code
  sign-in on a device against the local stack's real auth server (23 Nov rehearsal), then with real emails from
  the hosted dev project for all three people (24 Nov, Gate 3)
- [x] 3.2 Data-model migrations, RLS, RPCs. Done in `adf006d`, `33aeb1c` and `60ed408`; on the dev project since
  24 Nov (Block 1), proven equal to local by fingerprint
- [x] 3.3 `confirm_handover`. Done; proven on the dev project with two real devices (S24 and the emulator), two
  device IDs, custody moved, ACCEPTED (`SHIP-3E4005`, Gate 3)
- [x] 3.4 Chain v2 with shared vectors and SQL. Domain, Room and SQL done in `91fbf99`; the app uploads only through `append_readings` and compares its history with the server's head
- [x] 3.5 Clock-skew detection and Keystore interim signing. Done; `KeystoreDeviceSignerTest` 3/3 on the S24 and on
  the emulator (23 Nov, FINISH Block 2), and the Gate 3 readings were signed with the S24's Keystore key
- [x] 3.6 pgTAP attack tests and client-side JVM tests. pgTAP 238/238, `LiveSupabaseIntegrationTest` 3/3 on the
  local stack; `gate-3` (24 Nov)

The `[ ]` states these items had until 24 Nov are superseded by Gate 3 (CONTINUE run, second session, Block 3).

Proof so far:

```text
npx supabase test db        Files=4, Tests=232 ... Result: PASS
npx supabase db advisors --local --fail-on info     No issues found
Each server protection removed in turn, in a rolled-back transaction: 63 of 63 fail a test, all applied
Chain vectors: standalone Python, Postgres and Kotlin agree on all 18
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug
BUILD SUCCESSFUL in 2m 40s
Debug tests 272 failures 0 errors 0 | Release tests 243 failures 0 errors 0 | core-domain 23 tests, no failures
Superseded by the numbers in the Gate 2 proof above.
```

The app now syncs only through the server's functions. None of this has run against a hosted project yet.

Proof at `d1698b9`:

```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease
BUILD SUCCESSFUL      (run at 3f53519; d1698b9 added tests, rerun below)
debug tests 397 failed 0 | release tests 342 failed 0 | core tests 60 failed 0
./gradlew :core-domain:test testDebugUnitTest      at d1698b9
debug tests 402 failed 0 | core tests 60 failed 0, plus the pinning tests run on their own afterwards
npx supabase test db        Files=4, Tests=233 ... Result: PASS   (no SQL has changed since)
Mutating the worker's rebooking to KEEP, or removing the launch-time check, now fails a test.
```

**First real run, 6 Nov, on the local Docker stack** (Postgres, GoTrue, PostgREST and Kong; not a hosted project):
`LiveSupabaseIntegrationTest` signs up three accounts and runs one shipment from sender to receiver through the
app's own client, uploader and checks, and checks each shortcut is refused. It failed first: the server refused its
first reading as back-dated, because the test's readings were spaced a minute apart. Fixed in `75ac588`. The run also
showed the fake server had no clock rule; fixed in `189799e`.

```text
CCH_LIVE_SUPABASE_URL=http://127.0.0.1:54321 CCH_LIVE_SUPABASE_KEY=<local anon key> ./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL | live tests 3 failures 0 errors 0
local database afterwards: 1 shipment ACCEPTED, 8 readings, 2 handover confirmations
```

Test-key guard (resume prompt rule 7), app side, in `fed4912`: `LoggerHistoryVerifier.verify` refuses the published
test key and its mirror in any encoding. `TestLoggerKeyRefusedTest` runs in debug and release. Neither built APK
contains the private key (searched as hex and Base64 after unzipping, 6 Nov 23:16).

**Gate 3:** (superseded: `gate-3` tagged 24 Nov, `SHIP-3E4005` on the dev project with the S24 and the emulator;
CONTINUE run, second session, Block 3.) Then: not started.

## Phase 4: Logger as witness

This list uses MASTER 5.4's numbers. The live list is "FINISH run, Block 4" below, which numbers by FINISH's
items (its 4.1 is the virtual logger in the app, its 4.3 the server half), and CONTINUE and the owner's 26 Nov
message use those numbers.

- [x] 4.1 `docs/protocol/LOGGER_PROTOCOL.md`. Written in `863ccd5`; signed off by the owner on 26 Nov
  (`PROTOCOL_Q4 = drafts`), with the minimum MTU rule written in
- [ ] 4.2 Virtual logger. Kotlin protocol, virtual logger, verifier and ingestor in `cfb2e85`, checked against the
  Python vectors. Connecting it to the app is FINISH Block 4 item 1 (below)
- [x] 4.3 BLE ingestion behind `TelemetryRepository`: the BLE adapter is done against a fake GATT layer (FINISH
  Block 4 item 4); never run on hardware (`HARDWARE = none`)
- [ ] 4.4 Receiver reads and verifies the logger at handover. Domain half done (FINISH Block 4 item 2); the screen
  wiring is FINISH item 1's
- [x] 4.5 Firmware. Interfaces, failing tests and `WALKTHROUGH.md` done in `487db13`. With `FIRMWARE_OWNER = claude`
  Claude wrote the bodies on 26 Nov: 39/39 host tests pass with MSYS2 g++ (Session of 26 Nov). PlatformIO skeleton
  skipped while `HARDWARE = none`; never run on a board

Proof for 4.5, 6 Nov:

```text
cd firmware && cmake --preset host-tests -DCMAKE_CXX_COMPILER=g++ && cmake --build --preset host-tests
[16/16] Linking CXX executable cch_logger_tests.exe        (no warnings, MSYS2 g++ 13.2)
ctest --preset host-tests
3% tests passed, 37 tests failed out of 38    (by design: each fails with "TODO(owner): <function>, step N")
```

The sanitizer and fuzz presets were not built: MinGW has no ASan and libFuzzer needs clang.

**Gate 4:** not started; after Gate 5 (4.1 and 4.3 remain), closing as "met without hardware" (`HARDWARE = none`).

## Phase 5: App architecture, background work and completeness

The live list for Phase 5 is "FINISH run, Block 5" below and CONTINUE Step 2 above.

- [x] 5.1 ViewModels, Hilt, role-based navigation. Done 24 Nov (CONTINUE run, Step 2; `8cbdf71`)
- [x] 5.2 Foreground service, WorkManager sync, queued GPS, incremental download. Service and window fix 26 Nov; GPS
  in the service and finding 11's incremental fetch 27 Nov (`e942e87`, `1a4691c`); proven on the S24 (Sessions of
  26 and 27 Nov)
- [x] 5.3 Multi-leg custody in the UI. 27 Nov (`837aa9c`); on devices with `SHIP-2DE57A` (27 Nov, evening)
- [x] 5.4 Opaque QR codes. 26 Nov (`DECISIONS.md`)
- [x] 5.5 Android skills audits. Permissions, ANR, Compose (`4793425`); intent security, Play policy, R8, lint
  (`docs/agent/audit_5_5.md`); on the emulator: see Session of 27 Nov, evening
- [x] 5.6 Release build. R8, signing from `CCH_RELEASE_KEYS`, version 0.5.0 (`da4e737`); run on devices under R8
  (27 Nov, evening; one keep rule fixed, `55bc5c9`)

**Gate 5:** reviewer and security review of `gate-3..HEAD` next (Session of 27 Nov, evening).

## Phase 6: Verification kit and hand-off

- [ ] 6.1 Test pyramid summary
- [ ] 6.2 Emulator walk-through and screenshots
- [ ] 6.3 `docs/FIELD_TEST.md`
- [ ] 6.4 `docs/THREAT_MODEL.md`
- [ ] 6.5 README rewrite
- [ ] 6.6 Security pass and branch review
- [ ] 6.7 `docs/agent/FINAL_REPORT.md`

**Gate 6:** not started.

## Definition of done (section 6)

- [ ] Findings 1 to 16 each closed, with the commit and test that prove it
- [ ] Clean clone builds on Windows with only `JAVA_HOME` and `local.properties` set by the owner
- [ ] CI workflow runs unit tests, lint and database tests
- [ ] anon has no access; members see only their shipments; readings are append-only
- [ ] Tail deletion, edits, re-ordering, gaps in sequence, wrong custodian, same-device dual confirmation and clock skew are rejected or flagged, each by a test
- [ ] Heat, freeze and gap verdicts per product profile; one alert per excursion; timed escalation with notifications
- [ ] PASS, FAIL and UNKNOWN handovers recordable; quarantine and replacement reachable; VVM and shake test captured
- [ ] Recording and sync survive backgrounding, process death and offline periods
- [ ] Logger protocol documented with shared test vectors; virtual logger and (if any) hardware path verified at handover
- [ ] Multi-leg custody works
- [ ] No secrets in git history added by you; `local.properties` untouched by you
- [ ] README, THREAT_MODEL, FIELD_TEST and FINAL_REPORT written; no overclaiming text in the app
- [ ] HANDOFF lists every action the owner still has to take

## FINISH run, Block 5: Phase 5 (started 23 Nov, while Blocks 1 and 3 wait)

Done in small steps, each with the full proof, so the screens the Gate 3 run uses only change one at a time. The
Gate 3 run itself is built from `05b342e`, the code it was rehearsed on.

- [x] 5.1a Hilt in the build (`com.google.dagger.hilt.android` 2.60.1, AndroidX Hilt 1.4.0), `ColdChainApp` as the
  `@HiltAndroidApp` application, `MainActivity` an `@AndroidEntryPoint`. No behaviour change: everything still comes
  from `RepositoryProvider`, which `MainActivity` still initializes

```text
./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug --continue
BUILD SUCCESSFUL in 7m 29s
core-domain 118 failed 0 | debug 428 failed 0 | release 371 failed 0 | skipped 0
emulator: cold start after pm clear, LaunchState COLD, TotalTime 5566 ms, no FATAL or ANR, the sign-in screen shows
```

- [x] 5.1b One ViewModel per screen (finished 24 Nov, CONTINUE run, Step 2), starting with `AppRoot` (which screen to show), then Dispatch (removes the
  order-dependent `DispatchScreenTest`), Transit, Handover, Alerts. Each one's dependencies come through Hilt
  - [x] `AppRootViewModel`: which top-level screen shows, the owner check, local mode (kept in `SavedStateHandle`,
    so it survives process death) and sign-out. `AppRootViewModelTest`, 6 tests, 5 failed against a stub first.
    Hilt gets `AccountRepository` and `SessionGate` from `LegacyProviderModule`, a bridge to `RepositoryProvider`
    until 5.1c. Full proof: core-domain 118, debug 434, release 377, failed 0; lint and release APK built.
    Emulator, local stack: sign-in reaches "Case, Courier", Sign Out returns to sign-in, local mode survives
    `am kill` in the background, no FATAL or ANR
  - [x] `DispatchViewModel`: create, add people, dispatch, start a local journey, scan, reset. The shipment on
    screen is kept by ID in `SavedStateHandle` (before, a rotation lost a shipment just created). The form fields
    are `rememberSaveable`. `DispatchViewModelTest`, 9 tests, 9 failed against a stub first. `DispatchScreenTest`
    now builds its own ViewModel from local fakes, with no global client, which removes the order dependence
    recorded on 7 Nov; its waits idle Robolectric's main looper, which the ViewModel resumes on. Full proof:
    core-domain 118, debug 443, release 386, failed 0; lint and release APK built. Emulator, local stack: sender
    created `SHIP-C8AC36`, which stayed on screen through landscape and back, and dispatched it (server:
    DISPATCHED), no FATAL or ANR
  - [x] Transit, Handover, Alerts ViewModels: done 24 Nov (CONTINUE run, Step 2)
- [x] 5.1c Hilt builds everything; `RepositoryProvider` deleted. Done 24 Nov (`dccf20c`, `8cbdf71`)
- [x] 5.2 to 5.6: done, see "Phase 5" above (this list is kept as it stood; the live record is in the sessions of
  26 and 27 Nov at the top)
