# Test pyramid

What is tested, how, and how many, from the full proof at `a14ecc1` (28 Nov 2026) and the pgTAP run of the same day.
This is a prototype: the tests show what the code does, not that it is fit for real vaccine or medicine stock.

## Counts

| Layer | Where | How it runs | Tests |
|---|---|---|---|
| Domain unit tests | `core-domain/src/test` | Plain JVM, no Android | 143 |
| App unit tests | `app/src/test` (shared by debug and release) | JVM, with fakes for the server | 557 |
| ... of the debug run's 635, plain JVM | | JVM only | 415 |
| ... under Robolectric | `app/src/test`, `app/src/testDebug`, `app/src/testRelease` | Android framework on the JVM: Room on real SQLite, WorkManager's test harness, services | 173 (153 in the release run) |
| ... Compose UI tests | `app/src/testDebug` | Robolectric with `createComposeRule`: the real screens, driven by their semantics | 47 |
| Debug-only tests | `app/src/testDebug` (included above) | Use debug-only code (chaos lab, local mode) | 78 |
| Release-only tests | `app/src/testRelease` | Prove the release build has no debug tools | 3 |
| Instrumented tests | `app/src/androidTest` | On a phone or emulator | 3 (`KeystoreDeviceSignerTest`, run on the Galaxy S24 and the emulator, 23 Nov) |
| Database tests | `supabase/tests/database` | pgTAP on the local Docker stack (Supabase's own Postgres 17) | 279 |
| Firmware host tests | `firmware/tests` | GoogleTest with MSYS2 g++ (CI: g++, then ASan/UBSan with clang, and 30 s of fuzzing per decoder) | 39 |
| Live integration test | `LiveSupabaseIntegrationTest` | The app's own client against a real Supabase API (the local stack); off by default | 4 |

Distinct Kotlin tests on the JVM: 143 + 557 + 78 + 3 = **781**. The debug run executes 635 of them and the release run
560, because `app/src/test` runs in both builds.

At the start (`BASELINE.md`, 30 Oct): 159 app unit tests and 8 live tests that weren't run, no domain module, no
database tests, no firmware, no instrumented tests, no CI.

## Shared vectors (one answer, several implementations)

| What | Kotlin | Elsewhere |
|---|---|---|
| The shipment hash chain | `ChainVectorsTest` (5) | pgTAP `0004_chain_vectors` (19), the Python generator in `tools/protocol` |
| The logger protocol: records, hashes, batch and head payloads | `LoggerVectorsTest` (5), `VirtualLoggerVectorsTest` (3) | Firmware `Step2RecordLog.*`, `Step4Vectors.*` |
| The logger's Bluetooth bytes | `LoggerGattVectorsTest` (7) | Firmware `Step4Vectors.EveryCharacteristicMatchesThePythonBytes` |

Kotlin vector tests: 20. Every implementation was written by Claude; one signature is checked against an outside
source (RFC 6979).

## Attack tests

Tests whose whole point is that an attack fails.

- **Database (pgTAP), 279 checks in six files.** Strangers and signed-out callers see nothing (`0001`, `0002`).
  `0003` covers each shortcut being refused:
  - a stale head, a gap, a re-ordered or edited reading;
  - an upload by someone without custody;
  - one phone confirming for both people;
  - the sender confirming;
  - a back-dated reading, or one dated the same as the last. A reading dated ahead is accepted but flagged
    `clock_ahead`; the threat model says what that leaves open.

  Then the logger's test key refused (`0005`), and location bounds and readers (`0006`).
- **Kotlin, 100 tests in ten classes:**
  - `LoggerHandoverCheckTest` (23), `LoggerHistoryForkTest` (4), `RecordsRunExactlyTest` (2): an edited, removed,
    invented, replayed, truncated, withheld or forked logger history; a sequence overflow.
  - `ServerHandoverTest` (22): what the receiver's confirmation records when the logger disagrees.
  - `BleLoggerLinkTest` (15): forged headers, extra records, silent notifications.
  - `TestLoggerKeyTest` (7), `TestLoggerKeyRefusedTest` (2): the published key and its mirror.
  - `ShipmentWorkflowTest` (13): scanned text is never trusted.
  - `UploadAuditTest` (7): an unsigned batch, one signed by another phone's key, readings no batch covers.
  - `RegressionTest` (5): findings 2, 3 and 6 to 8, from the original review.
- **Mutation runs:** Gate 4 (`gate4_review.md`) and Gate 5 (`gate5_review.md`) each applied mutants by hand and named
  the test that killed each one; survivors led to new tests.

## Proven on real systems, not by these tests

Devices and the hosted dev project are covered in `docs/agent/STATUS.md`:
- two-device handovers;
- recording with the screen off, in deep Doze, after a kill and offline;
- the release build under R8.

## Running each suite (Windows, Git Bash)

```bash
export JAVA_HOME='<JDK 17 folder>'            # docs/agent/ENVIRONMENT.md
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew :core-domain:test                   # domain
./gradlew testDebugUnitTest                   # app, debug (Robolectric and Compose included)
./gradlew testReleaseUnitTest                 # app, release
./gradlew lintDebug
npm ci && npx supabase db start && npx supabase test db     # database, needs Docker
./gradlew :app:connectedDebugAndroidTest      # instrumented, needs a phone or emulator
cd firmware && cmake --preset host-tests && cmake --build --preset host-tests && ctest --preset host-tests
```

The live test runs only when `CCH_LIVE_SUPABASE_URL` and `CCH_LIVE_SUPABASE_KEY` point at a project you own,
normally the local stack (`npx supabase start`). The build refuses the old demo project.
