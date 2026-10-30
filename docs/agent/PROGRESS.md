# Progress

**Next action:** Phase 1, task 1.2, then 1.1 (order swapped, see `DECISIONS.md`).

Status words: `done`, `doing`, `blocked` (with the reason), or blank for not started.

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

- [ ] 1.2 Make the live test opt-in, keep default tests offline (runs first)
- [ ] 1.1 Remove `org.gradle.java.home`, then replace the interim commands in `CLAUDE.md`
- [ ] 1.3 `supabase init`, `0001_lockdown`, pgTAP
- [ ] 1.3b Apply to dev project. Status: `blocked`, read-only MCP, `HANDOFF.md` Q1
- [x] 1.4 Lockdown guidance in `HANDOFF.md`. Done in `720856f`
- [ ] 1.5 Room schema export, no destructive fallback, migration test
- [ ] 1.6 Move demo corruption and chaos paths to the debug source set
- [ ] 1.7 GitHub Actions workflow
- [ ] 1.8 Fix the README ending

**Gate 1:** not started.

## Phase 2: Safety logic

- [ ] 2.1 `ProductProfile`, per-shipment selection, seed the freeze-sensitive profile
- [ ] 2.2 Heat and freeze evaluation; gaps produce UNKNOWN
- [ ] 2.3 Alerts on state change, deterministic IDs, timed escalation, notifications
- [ ] 2.4 Lifecycle state machine
- [ ] 2.5 PASS, FAIL and UNKNOWN handovers, VVM and shake test, orchestrator recomputes the verdict
- [ ] 2.6 Flip audit tests into regression tests

**Gate 2:** not started.

## Phase 3: Toolchain upgrade and server trust

- [ ] 3.0 Toolchain: Kotlin 2.x, KSP, AGP 9, Gradle, Java 17, SDK 36, Compose BOM, Room 2.8, supabase-kt 3.x
- [ ] 3.1 Supabase Auth, profiles, roles, sign-in, session persistence, sign-out
- [ ] 3.2 Data-model migrations, RLS, RPCs
- [ ] 3.3 `confirm_handover`
- [ ] 3.4 Chain v2 with shared vectors and SQL
- [ ] 3.5 Clock-skew detection and Keystore interim signing
- [ ] 3.6 pgTAP attack tests and client-side JVM tests

**Gate 3:** not started.

## Phase 4: Logger as witness

- [ ] 4.1 `docs/protocol/LOGGER_PROTOCOL.md`
- [ ] 4.2 Virtual logger
- [ ] 4.3 BLE ingestion behind `TelemetryRepository`
- [ ] 4.4 Receiver reads and verifies the logger at handover
- [ ] 4.5 Firmware interfaces, failing tests, skeleton, `WALKTHROUGH.md` (`FIRMWARE_OWNER = me`)

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
