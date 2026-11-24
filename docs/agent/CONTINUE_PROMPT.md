# Continue prompt — Cold Chain Handshake, from Phase 5.1b to done (written 24 Nov 2026)

You are Claude Code on the owner's Windows PC in `D:\ColdChainHandshake`, branch `rebuild/trust-core`, run with
`--dangerously-skip-permissions`. This is a fresh session after `/clear`. The last session (23 Nov) stopped at a usage
limit with everything committed and the tree clean. **The owner is away and his phone is not connected.** Work
without waiting for him; stop only where this file says to.

## 1. Read first

1. `CLAUDE.md`
2. `docs/agent/PROGRESS.md` (newest state; its "FINISH run" sections at the top and Block 5 at the bottom),
   `docs/agent/HANDOFF.md`, `docs/agent/STATUS.md`, `docs/agent/DECISIONS.md`
3. `docs/agent/FINISH_PROMPT.md` sections 2 and 3 (switches and hard rules: they all still apply), then
   `docs/agent/MASTER_PROMPT.md` sections 5 and 6 (phase tasks, gate criteria, definition of done)

This file adds to FINISH_PROMPT and wins where they conflict. `DECISIONS.md` entries stand unless this file overrides
one by name.

Then verify the state below with `git log --oneline -15`, `git tag -n`, `git status`, and record a short
"Status at session start (24 Nov)" at the top of PROGRESS.md.

## 2. State when this was written (claims to verify)

- Tags: `gate-0`, `gate-2`. `gate-1` waits for the dev project's advisors; `gate-3` for the hosted two-device run.
- Last full proof: core-domain 118, debug 443, release 386, 0 failures; lint and release APK built. pgTAP 233/233,
  live test 3/3 on the local Docker stack (Supabase CLI 3.3.0 via unpinned `npx`, Postgres 17.12).
- Real-system proof so far: Keystore signer test 3/3 on the S24 and the emulator; a whole handover through the
  app's screens on the local stack, one emulator with `pm clear` between roles (`SHIP-744113`, ACCEPTED).
- Phase 4: BLE adapter and the domain half of the handover logger check done. Items 4.1 and 4.3 not started.
- Phase 5: 5.1a done (Hilt in the build); 5.1b done for `AppRootViewModel` and `DispatchViewModel`.
- The Gate 3 run is pinned to commit `05b342e` (built in a separate worktree), so later screen changes don't
  invalidate HANDOFF's steps.
- Dev project `yjvoxozaoiarwbusyqke` was paused and `.mcp.json` read-only at last check. The owner may have fixed
  this; check (section 4, step 1).

## 3. Switches for this run (the owner may edit these lines)

```text
PHONE           = absent    # absent | present. Absent: use emulators only; never wait for the phone
EMULATORS       = 2         # you may create a second AVD (cch_pixel_api36_b, 2 GB RAM, under D:\Android\avd)
                            # so two devices exist at once; the PC has 24 GB RAM. Close other emulators first
FIRMWARE_OWNER  = me        # me: firmware bodies stay TODO(owner) and Gate 4 ends "waiting for the owner's firmware"
                            # claude: you write the C++ library against the existing tests and vectors (step 6.0)
PROTOCOL_Q4     = owner     # unchanged unless edited: draft protocol, owner signs off later
```

All other switches as in FINISH_PROMPT section 2 (`HARDWARE=none`, `PUSH_POLICY=local-only`, `LOCAL_PROPS=yes`,
`SETUP_TOOLS=yes`, `DEV_PROJECT=yjvoxozaoiarwbusyqke`).

## 4. Work plan, in order

When a step is blocked, write the exact need at the top of HANDOFF.md and go on to the next unblocked step. Come back
when the block clears. Run the full proof command after each numbered step and record it in PROGRESS.md:
`./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease --continue`
(in the background; add `npx supabase test db` whenever SQL changed).

### Step 1 — Housekeeping (about 30 minutes)

1. **Dev project check:** through MCP, does `list_tables` answer, and does `apply_migration` exist? If both, do Block 1
   of FINISH_PROMPT now (migrations, advisors, real publishable key into `local.properties`, tag `gate-1` when MASTER
   5.1's criteria hold). If not, leave the request at the top of HANDOFF and continue.
2. **Pin the Supabase CLI:** add a root `package.json` with `supabase` as a pinned devDependency (the version now in
   use) and switch commands in `CLAUDE.md` to it, so `npx` can't jump a major version again. Re-run pgTAP.
3. **CC010:** confirm a pgTAP test covers the manifest lock (CC010). If not, add it test-first.
4. **Escalation worker cold start:** check whether `AlertEscalationWorker` (and any other worker) gets its
   repositories when WorkManager starts the process with no activity open. If it doesn't, write a failing test and
   fix it; Hilt's `HiltWorker` in 5.1c is the intended fix.
5. **Security review:** the automatic commit security review failed on 23 Nov (network error). Run a security
   review of `git diff gate-2..HEAD` yourself (a subagent, one sitting, findings to `docs/agent/security_review.md`),
   fix valid findings test-first.

### Step 2 — Finish 5.1 (ViewModels and Hilt)

5.1b: `TransitViewModel`, `HandoverViewModel`, `AlertsViewModel`, each test-first against a stub, state in
`SavedStateHandle` where it must survive process death. 5.1c: Hilt modules build everything, `HiltWorker` for the
workers, `RepositoryProvider` and `LegacyProviderModule` deleted, role-based navigation. After each ViewModel, a
quick emulator check on the local stack (`tools/device/drive.sh`): the screen opens, survives rotation and `am kill`,
no FATAL or ANR.

### Step 3 — 5.2 Background work and the handover window

1. The Handover screen downloads its own history (today it waits on Transit's polling).
2. A foreground service for recording, so readings continue with the screen off and the app in the background.
3. WorkManager for sync and escalation, with backoff and constraints.
4. **The 3-minute window** (`DECISIONS.md`, 23 Nov, "Known limitation"): fix it as verdict logic, test-first, so a
   receiver can inspect for longer without a gap. The courier keeps recording until custody moves, and the
   receiver's confirmation binds to the head they saw. The fix must not let a courier confirm early, stop recording
   and still get a PASS: write that attack as a test first. Update HANDOFF's Gate 3 steps only for runs built from
   the new code; the pinned `05b342e` steps stay as they are.

Then prove on the emulator: recording continues with the screen off for 10 minutes (`adb shell input keyevent
KEYCODE_SLEEP`), through `am kill`, through Doze (`dumpsys deviceidle force-idle`), and through 10 minutes of
airplane mode (`cmd connectivity airplane-mode enable`), reaching the server in order afterwards.

### Step 4 — 5.3 to 5.6

- 5.3 Multi-leg custody: the server already supports TRANSFER events; add the UI and a test for sender → courier A →
  courier B → receiver, and run it on the local stack with the device driver.
- 5.4 Opaque QR codes: the QR carries only the shipment ID. Remove the old `CCH:SHIP:...` text and the "Authoritative
  QR Identifier" label.
- Screen honesty pass: remove developer jargon ("Supabase Polling ~3s", "Phone A/B", "Simulated Time (+60s/tick)",
  the sender's UUID as "Worker ID"), fix wrapped labels, and check no screen states a temperature, verdict or
  custody it didn't get from data.
- 5.5 Android audits on the emulator: rotation, process death, Doze, the notifications permission on Android 13+,
  accessibility labels and touch targets, dark mode. Screenshots to `docs/agent/evidence/`.
- 5.6 Release build: R8 rules, no debug tools in release (keep `ReleaseHasNoDebugToolsTest`), signing left to the
  owner (document it in HANDOFF; never create or read a keystore).
- **Gate 5:** MASTER's criteria, one bounded reviewer (one sitting, at most 25 mutants, findings to a file). Tag
  `gate-5` when met.

### Step 5 — Phase 4 items 4.1 and 4.3

Start these after Gate 5 even if `gate-3` is still blocked, with one rule: **the server change must stay backward
compatible with the pinned `05b342e` app** (new parameters optional, no renamed functions). Prove it by running the
`05b342e` worktree's `LiveSupabaseIntegrationTest` against the new local schema.
- 4.3: the logger's public key and start sequence recorded on the shipment at creation; the server refuses the test
  key; pgTAP tests first.
- 4.1: the virtual logger behind `TelemetryRepository` in debug (simulator kept as a debug tool), logger selection
  (add `BLUETOOTH_SCAN` only if a scan is really needed), and the handover screen wiring of `LoggerHandoverCheck`,
  including the carry-forward rule in PROGRESS: an enrolled real logger that this phone can't reach is UNKNOWN, not
  NOT_CHECKED, with a test.
- Gate 4: with `FIRMWARE_OWNER=me`, record "app side done, waiting for the owner's firmware". With `claude`, do
  step 6.0 first.

### Step 6 — Phase 6 and the end

- 6.0 (only if `FIRMWARE_OWNER=claude`): implement the four WALKTHROUGH steps in `firmware/src/` until all 39 host
  tests pass with g++; then tag `gate-4` if MASTER's Gate 4 criteria hold.
- 6.1 Test pyramid summary; 6.2 emulator walk-through with screenshots; refresh `docs/FIELD_TEST.md` and
  `docs/THREAT_MODEL.md` for the final code; 6.5 README rewrite that says plainly what is proven and on what;
  6.6 security pass over `gate-2..HEAD`; 6.7 `docs/agent/FINAL_REPORT.md`.
- Final STATUS.md and the 16-finding table, each closed or explained.
- Gate 6 with its reviewer. Final full proof, a final bundle `D:\cch-backup-final-<YYYYMMDD>.bundle` (verify it),
  tag `gate-6`, then **stop**.

## 5. The hosted Gate 3 run (whenever it unblocks)

It needs the dev project restored and writable, the email templates carrying `{{ .Token }}`, and **the owner online
to read the sign-in codes from his inbox** (`kiritojhj2000+sender@gmail.com`, `+courier`, `+receiver`). When the
first two hold, set everything up (worktree at `05b342e`, debug APKs built for the dev project, both devices ready),
then ask him once, at the top of HANDOFF and in a message, to be online for about 30 minutes. Don't start it before.

Devices: the S24 plus one emulator if `PHONE=present`. Otherwise two emulators at once (`EMULATORS=2`). If two
emulators won't run on this PC, use one emulator with `pm clear` between roles as rehearsed, record that as a
deviation from MASTER's Gate 3 in DECISIONS.md, and tag `gate-3` only if the owner replies "accept one-emulator
gate-3". Verify the result through MCP (two confirmations from two device IDs, custody moved, ACCEPTED, advisors
clean) before tagging.

After gate-3, repeat the handover once on the final code at the end of Step 6, so the finished app is proven too.

## 6. Reminders

- Hard rules: FINISH_PROMPT section 3, all of them. Never the demo project; never push; secrets never printed or
  committed; edits to source and docs with Edit and Write (no `cat >>` appends, even for DECISIONS.md); never weaken a
  test; never tag without the gate's proof.
- `high` effort. Reviewers in the foreground, bounded, started early in a usage window.
- Keep PROGRESS, STATUS and HANDOFF current after every step, so a usage-limit stop loses nothing. At a limit:
  commit, write the next action at the top of PROGRESS, stop.
- Messages to the owner: one short message when he must act, three lines at each tag, and a final summary when you
  stop for good: proven on real systems, proven only against fakes, and what's left for him.
