# Progress

**Next action:** Block 2 (instrumented test and smoke pass on the phone, then the emulator), then Block 4 against
the draft protocol. Blocks 1 and 3 wait on the owner restoring the dev project and removing `read_only`
(top of `HANDOFF.md`). The run follows `docs/agent/FINISH_PROMPT.md`.

Status words: `done`, `doing`, `blocked` (with the reason), or blank for not started.

## FINISH run, Block S: setup (23 Nov 2026)

- [x] S1 User env vars, checked by reading them back with `[Environment]::GetEnvironmentVariable(..., 'User')`:
  `JAVA_HOME=D:\cch-tools\jdk-17.0.20.1+1`, `ANDROID_HOME=C:\Users\LENOVO\AppData\Local\Android\Sdk`,
  `ANDROID_AVD_HOME=D:\Android\avd`, `GRADLE_USER_HOME=D:\cch-tools\gradle-home` (see `DECISIONS.md`)
- [ ] S2 Emulator: `doing`. Installing `cmdline-tools/latest`, `emulator` and `system-images/android-36/google_apis/x86_64`
  with `android sdk install`. The first try failed on `platform-tools`, because the running adb held `adb.exe`; the
  installed platform-tools 37.0.1 is kept
- [x] S3 Phone: `adb devices -l` lists `RZCY820AG0F device model:SM_S921E`. `getprop`: SM-S921E (Galaxy S24),
  Android 17, API 37
- [ ] S4 Dev project: `blocked`. `get_project_url` answers; `list_tables` says "Database is possibly not accessible";
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
- [ ] B2.2 Smoke pass on the emulator: `done`. On the phone: `blocked`, the phone is locked (`isKeyguardShowing=true`);
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
- [ ] 1.3b Apply to dev project. Status: `blocked`, read-only MCP and database unreachable, `HANDOFF.md` Q1
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

**Gate 1:** met locally, not tagged. The fresh reviewer failed two criteria (the CI advisors step could never fail, and doc inaccuracies), and also found that anon could execute new functions and that materialized views escaped the tests. All are fixed, see `DECISIONS.md`. `gate-1` waits for the dev-project migration and advisors check, `HANDOFF.md` Q1.

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
- [ ] 3.1 Supabase Auth, profiles, roles, sign-in, session persistence, sign-out. Done in `01c8e38` and tested against a fake auth server. Not run against a real one yet, and the Keystore signer's instrumented test needs a device
- [ ] 3.2 Data-model migrations, RLS, RPCs. Server done in `adf006d`, `33aeb1c` and `60ed408`, and the app now uses them. Waiting only on the dev project (`HANDOFF.md` Q1)
- [ ] 3.3 `confirm_handover`. Server and app done. The checkboxes are gone and each person confirms on their own phone, so finding 4 is closed in code. A real two-account run is still to do
- [x] 3.4 Chain v2 with shared vectors and SQL. Domain, Room and SQL done in `91fbf99`; the app uploads only through `append_readings` and compares its history with the server's head
- [ ] 3.5 Clock-skew detection and Keystore interim signing. Server, domain and app done: uploads are signed with the Keystore key and the handover screen shows clock and signature checks. The signer itself has only an instrumented test, not yet run
- [ ] 3.6 pgTAP attack tests and client-side JVM tests. Attack tests done; client tests done for the chain

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

**Gate 3:** not started. A real two-account run needs the dev project (`HANDOFF.md` Q1) and an emulator or phones.

## Phase 4: Logger as witness

- [ ] 4.1 `docs/protocol/LOGGER_PROTOCOL.md`. Written in `863ccd5`, a draft until the owner signs it off (`HANDOFF.md` Q4)
- [ ] 4.2 Virtual logger. Kotlin protocol, virtual logger, verifier and ingestor in `cfb2e85`, checked against the
  Python vectors. Not connected to the app: that waits for `gate-2` and `gate-3`
- [ ] 4.3 BLE ingestion behind `TelemetryRepository`. Waits for the owner's go-ahead
- [ ] 4.4 Receiver reads and verifies the logger at handover. Waits for `gate-3`
- [ ] 4.5 Firmware interfaces, failing tests, `WALKTHROUGH.md` (`FIRMWARE_OWNER = me`). Claude's part done in
  `487db13`; the function bodies are the owner's. PlatformIO skeleton skipped while `HARDWARE = none`

Proof for 4.5, 6 Nov:

```text
cd firmware && cmake --preset host-tests -DCMAKE_CXX_COMPILER=g++ && cmake --build --preset host-tests
[16/16] Linking CXX executable cch_logger_tests.exe        (no warnings, MSYS2 g++ 13.2)
ctest --preset host-tests
3% tests passed, 37 tests failed out of 38    (by design: each fails with "TODO(owner): <function>, step N")
```

The sanitizer and fuzz presets were not built: MinGW has no ASan and libFuzzer needs clang.

**Gate 4:** not started.

## Phase 5: App architecture, background work and completeness

- [ ] 5.1 ViewModels, Hilt, role-based navigation
- [ ] 5.2 Foreground service, WorkManager sync, queued GPS, incremental download
- [ ] 5.3 Multi-leg custody in the UI
- [ ] 5.4 Opaque QR codes
- [ ] 5.5 Android skills audits
- [ ] 5.6 Release build

**Gate 5:** not started.

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
