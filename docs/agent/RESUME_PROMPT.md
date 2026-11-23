# Resume prompt — Cold Chain Handshake, after /clear (written 6 Nov 2026)

You are Claude Code working on the owner's Windows PC in `D:\ColdChainHandshake`, branch `rebuild/trust-core`.
This is a fresh session. The previous one ran from 30 Oct to 6 Nov 2026 and stopped at a usage limit. You have no
memory of it. Everything you know comes from this file, the repo, and the session files the last session wrote.
Those files were written by the agent whose work you are about to check, so treat them as claims to verify.

This file replaces MASTER_PROMPT section 8 ("first actions"). Do not start again at Phase 0.

**Shell.** The last session used Git Bash: `cd /d/ColdChainHandshake`, `./gradlew`, forward slashes. Keep doing that.
Do not switch to PowerShell or `gradlew.bat`. Wrap any command that talks to Docker in `timeout -k 5 30`, because
Docker was hanging. A full Gradle run can take longer than your tool's 10-minute limit: run it in the background and
wait for it to finish, or split it into two runs.

**Where the last session's records are** (all on the owner's PC; read them, don't edit them):
- full log: `C:\Users\LENOVO\.claude\projects\D--ColdChainHandshake\2760c0bd-7e3a-4262-87b3-14021ecc1aad.jsonl`
- old scratchpad: `C:\Users\LENOVO\AppData\Local\Temp\claude\D--ColdChainHandshake\2760c0bd-7e3a-4262-87b3-14021ecc1aad\scratchpad`
  (on C:, a Temp folder; it may have been cleaned)
- the old reviewer's clean clone: its path was never printed. Find it by searching the log for `e4fc659` and "clean
  clone". Don't spend more than a few minutes on this.

## 1. Read first, in this order

1. `CLAUDE.md`
2. `docs/agent/MASTER_PROMPT.md`: the full plan. Sections 0 (switches), 2 (operating rules), 3.2 (the 16 findings),
   5 (phases and gate criteria) and 7 (stop and ask) still apply, except where this file says otherwise (for example
   effort: `high` here, not `xhigh`). Where the two conflict, this file wins.
3. `docs/agent/HANDOFF.md`, `docs/agent/PROGRESS.md`, `docs/agent/DECISIONS.md`
4. `docs/protocol/LOGGER_PROTOCOL.md` (skim)

Switches, unless the owner has edited them: `HARDWARE=none`, `FIRMWARE_OWNER=me`, `PUSH_POLICY=local-only`,
`DEADLINE=none`, `SUPABASE_TARGET=dev-project`.

## 2. What the last session reported (verify every line before relying on it)

Done in code (hashes as the last session gave them):
- **Phase 3, server:** chain v2 (`91fbf99`); migrations, RLS and the server functions `append_readings` and
  `confirm_handover` (`adf006d`, `33aeb1c`, `60ed408`); 233 pgTAP database tests passed on the local stack.
- **Phase 3, app:**
  - `ColdChainRemote` and `SupabaseColdChainRemote` (supabase-kt 3.8.0), with a contract test;
  - a `FakeColdChainServer` test fixture that copies the server's refusal rules;
  - email-OTP sign-in, a cached profile, and a debug-only "local mode";
  - the switchover (`3f53519`). The legacy client is deleted and Room is at v4, which stores the server's
    `receivedAt`. A refused record is marked `REJECTED` and not retried; only error `CC001` is retried, which means
    the phone's chain head is out of date. `ServerHandover` stores PASS only after the server accepts it, and
    FAIL/UNKNOWN wait in an offline queue. The handover checkboxes are gone (finding 4: one phone used to confirm for
    both people). A QR code is now only a shipment ID. Sign-out is refused while unsent data exists.
- `BatchSignature`, `ClockSkewDetector` and `KeystoreDeviceSigner`. The last one's instrumented test has never run.
- **Gate 2 fixes.** The first adversarial review found 32 of 112 code mutations surviving, plus unsafe paths to PASS:
  - final readings not credited;
  - duplicate timestamps hiding a heat breach;
  - no check at journey start;
  - late readings switching off the logger checks;
  - cloud-read failures not handled;
  - second handovers lost.

  All were fixed test-first. The second review's interim findings were fixed across several commits, including
  `4324315`; see `git log` for the rest:
  - heat and freeze are now evaluated as two series, because a hot reading was hiding a frozen one;
  - the owner check runs before the main app opens;
  - a handover transaction test now really refuses the last save;
  - the WorkManager `KEEP` mutation is caught;
  - pinning tests were added for the reviewer's probe IDs (L4, M5, M3, B8, L8, G2, among others), and the clock
    tolerance is pinned to the server migration's value.
- **Security fixes from the automated commit reviews (four findings, all fixed):**
  - fail-open uploader reconciliation (`aa9da30`);
  - a new person signing in could see the previous person's records;
  - or wipe the previous person's unsent records;
  - a queued PASS could later be sent unreviewed.

  The last session says the later fixes went in with `d1698b9`; verify this with `git show --stat`.
- **Phase 4 started (`cfb2e85`):**
  - `LOGGER_PROTOCOL.md`;
  - `tools/protocol/gen_logger_vectors.py`: hand-written P-256 ECDSA with RFC 6979 nonces, checked against one
    published RFC vector;
  - `docs/protocol/logger_vectors.json`, which contains a test-only private key;
  - Kotlin `LoggerProtocol`, `LoggerLink`, `VirtualLogger`, `LoggerHistoryVerifier`, `LoggerIngestor` and
    `LoggerVectorsTest`.
- `LiveSupabaseIntegrationTest` was committed in `d1698b9` (the message of `2045c09` wrongly claims it). It has never
  run.

Not done or not proven:
- **No clean full test run at HEAD.** The last full run was 405 debug, 349 release and 61 core-domain tests. One
  failure was then fixed and rerun on its own. The debug/release gap is expected: tests that use debug-only code live
  in `src/testDebug` (HANDOFF says so). Confirm it; don't investigate further.
- **gate-2 is not tagged.**
  - The second reviewer worked on a clean clone at `e4fc659` and was paused about 12 checks from the end with no
    verdict.
  - Do not try to resume that agent. Its context is gone, and the handover code changed after it started.
  - If it left notes in the old clean clone, the old scratchpad or the log (paths above), read them and carry the
    unfinished checks into the new review.
- **Gate 3 is not tagged.** Most of the code is done, but PROGRESS still has items 3.1 and 3.5 open, and none of it
  has run on real infrastructure.
- **Nothing has ever run against a hosted Supabase project, an emulator or a phone.** Every passing test checks the
  app against fixtures and vectors that Claude wrote. None of it proves the core claim until a real run backs it.
- Gates 0 and 1 were passed before an earlier context compaction. Their tags are not confirmed.

Owner-only blockers when the last session ended (section 4 checks each one):
1. The old demo Supabase project is still open to anyone with the APK, because `lockdown_demo_project.sql` hasn't
   been run.
2. Docker Desktop has been stuck "starting" since 17:27 on 6 Nov.
3. HANDOFF Q1 is unanswered. `.mcp.json` points at dev project `yjvoxozaoiarwbusyqke` with `read_only=true`, so no
   migration has reached any hosted project.
4. The owner said `local.properties` still points at the demo project; you can't read it, so ask. The dev project's
   email auth provider may not be enabled.
5. There is no emulator or phone. An earlier session found that Android Studio wasn't installed and C: was 96% full.
   Check whether that is still true.
6. No backup: `PUSH_POLICY=local-only`, so the work may exist only on this PC.
7. It's unknown whether `.claude/settings.json` (the deny rules) is in place.

## 3. Rules for this session (they add to MASTER_PROMPT section 2)

1. **Verify before you trust.** Your first output is the status check in section 4, not code.
2. **Reality before features.** Don't start Phase 4 app wiring, BLE, the server half of Phase 4 or any Phase 5 work
   until gate-2 and gate-3 are both tagged. Do only the work in section 5.
3. **Edit with the Edit and Write tools.** No Python or shell scripts that rewrite source files; last time they
   half-applied a patch and produced a malformed block. Make a change that spans many files file by file, and build
   after each group.
4. **Allowance.** The owner is on Claude Pro.
   - Work at `high` effort.
   - The gate reviewer runs in the foreground with the hard limits in section 5, never in the background. Start it
     only early in a fresh 5-hour window, not near its end.
   - Pipe long build and mutation output through `tail -n 40`.
   - Keep the section 4 paperwork short (STATUS.md is one page). It must not take a large share of the window.
   - If you get a usage warning, finish the current step, update PROGRESS.md and HANDOFF.md, and stop.
5. **Reports.** At each tag, write a short plain-English report in PROGRESS.md and tell the owner. Stop and wait only
   where section 5 says to.
6. **Firmware belongs to the owner** (`FIRMWARE_OWNER=me`).
   - For C++ you may write only these: the CMake project, header interfaces, a host-side harness that loads
     `docs/protocol/logger_vectors.json`, failing GoogleTest cases, sanitizer and fuzz presets, and
     `firmware/WALKTHROUGH.md`.
   - Every function body stays `TODO(owner)`.
   - The host tests are meant to compile and fail until the owner implements them. That overrides MASTER section
     5.4's "must still build and pass"; Gate 4 waits on him.
   - Skip the PlatformIO skeleton while `HARDWARE=none`.
   - `LOGGER_PROTOCOL.md` is a draft. List its open design decisions in HANDOFF.md for the owner to review or
     change, and don't treat it as frozen until he signs it off there.
7. **The test key is never a real logger.** Every APK variant must refuse a logger whose public key matches the
   test-only key in `logger_vectors.json`, debug "local mode" included. Only the test source sets may use it. Later,
   the server refuses it too.
8. **Write plainly for the owner.** HANDOFF.md, PROGRESS.md and STATUS.md must make sense without the last session's
   shorthand (L4, M5, B8 and so on). Say what each code means the first time you use it.
9. **Safety rules from MASTER_PROMPT still hold.**
   - No force-push, `reset --hard`, `git clean`, history rewrites or `rm -rf`.
   - Never read `local.properties`, keystores or `.env`.
   - Never touch the demo project.
   - Reach Supabase only through the scoped MCP server or the local CLI.
   - Never push while `PUSH_POLICY=local-only`.
   - Don't add gate tags after the fact for work you didn't verify yourself. The one exception is the Gate 3 run on
     two devices: the owner does it, and you verify it by checking the server's custody and handover records through
     the MCP server before you tag.

## 4. First actions: the status check (about 15 minutes, no code changes)

Run these and write the results at the top of `docs/agent/PROGRESS.md` under "Status at session start":
- **Git:** `git status`, `git log --oneline -30`, `git tag -n`, `git remote -v` and `git stash list`.
  - Confirm that the hashes in section 2 exist.
  - If `gate-0` or `gate-1` is missing, find the proof for it in PROGRESS.md and ask the owner in HANDOFF.md whether
    to tag it. Don't tag it yourself.
- **Deny rules:** check that `.claude/settings.json` exists and contains them.
- **Docker:** `timeout 30 docker version`, `timeout 30 docker ps`, then `timeout 60 npx supabase status`.
- **Supabase MCP:** is it connected, which project ref does it use, and is `read_only` still on? Don't change
  `.mcp.json`; that is the owner's answer to Q1.
- **Devices:** `adb devices`, and whether an emulator image exists. Note the free space on C: and D:.
- **Backup:** if no remote holds this branch, make a local bundle (this is not a push):
  `B=/d/cch-backup-$(date +%Y%m%d-%H%M).bundle; git bundle create "$B" --all && git bundle verify "$B"`.
  Add "copy the bundle to cloud storage or a USB drive" to the owner's list.
- **Demo project:** check whether HANDOFF.md records it as locked. You can't check this yourself; ask if it doesn't.

Then write `docs/agent/STATUS.md`, one page of plain English, and keep it up to date from now on. Use three lists:
- **Proven on real systems** (a hosted project, an emulator or phone, a real Docker stack)
- **Proven only against our own fakes and vectors**
- **Not proven**

Add a table of the 16 findings (MASTER_PROMPT 3.2), giving each one's state at HEAD (fixed and proven on a real
system / fixed and tested against fakes / open) and the test that shows it.

Then replace the owner's list at the top of HANDOFF.md (it currently has items such as "0" and "0b") with one list,
numbered as in section 2, each with exact steps and a check
that shows it worked. For Docker:
- quit Docker Desktop, run `wsl --shutdown` and restart it;
- free space on C:, or move Docker's disk image to D: (Settings → Resources → Disk image location);
- `docker ps` returns without error when it's fixed.

For emulators:
- install Android Studio and the SDK on D:;
- set `ANDROID_AVD_HOME` to a folder on D:;
- note that two emulators side by side need a lot of RAM, so one emulator plus his own phone also works.

## 5. Work plan

Do the common steps first. Then do every Track A step whose infrastructure is available now, and Track B for the rest.

### Common steps (no infrastructure needed)

1. **Clean test run at HEAD.** Use the command the last session used:
   `./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease`
   Record the exact counts and the commit. Fix any failure test-first.
2. **Finish Gate 2 with a fresh, bounded reviewer.** Use one foreground subagent on a new clean clone at HEAD (not the
   old `e4fc659` clone).
   - **Scope:**
     - the diff `e4fc659..HEAD` in the handover, sync, upload and safety-logic code;
     - a regression check of each unsafe PASS path in section 2;
     - any unfinished checks recovered from the old reviewer's notes.
   - **Gate 2 is MET when** all of these hold:
     - MASTER_PROMPT's "Gate 2:" line is satisfied: domain tests cover heat, freeze, gaps, alert de-duplication,
       escalation timing, every lifecycle transition and all three verdicts;
     - the section 5.2 rules hold, each with a test that fails if it's broken: a gap gives UNKNOWN and never PASS;
       FAIL and UNKNOWN need a reason; the orchestrator recomputes the verdict itself;
     - no path gives PASS on missing, duplicate or late data, or when the server refuses;
     - each surviving mutant is explained.
   - **Hard limits:**
     - at most 25 mutants;
     - no repeated flakiness trials;
     - the reviewer appends each finding as it goes to `docs/agent/gate2_review.md` in the main repo, not the clone,
       so a cut-off loses nothing.
   - **Verdict:** MET, UNMET, or "UNMET (incomplete)" if it hits a limit.
   - If the verdict is UNMET, fix test-first and re-check only the failed items.
   - Tag `gate-2` when it is MET and step 1 is clean. If Docker wasn't available, say in the tag message that the pgTAP
     suite wasn't re-run (no SQL has changed since it passed).
3. **Test-key guard (rule 7), app side,** with tests.

### Track A: steps that need the owner's infrastructure

- **Docker working:**
  - `npx supabase test db`;
  - `LiveSupabaseIntegrationTest`, run as HANDOFF describes, with keys kept out of the output;
  - fix whatever this first real run breaks.
- **Dev project writable (Q1 answered):** apply the migrations through the MCP server and run its advisors.
- **Emulator or phone:** `./gradlew connectedDebugAndroidTest`, which runs `KeystoreDeviceSignerTest`.
- **Two devices plus a writable dev project:**
  - write the exact steps in HANDOFF.md, following MASTER's Gate 3 (a courier and a receiver, each signed in on
    their own device) and HANDOFF's existing steps: sign in as the sender, create and dispatch a shipment naming the
    other accounts' emails, take custody as the courier, then confirm the handover as the courier and as the
    receiver;
  - a phone stays registered to the first account that signs in on it, so count the devices the steps really need.
    If that's three, say so, and offer two emulators plus his phone;
  - finish Track B first, then **stop and wait** for the owner's result.
- **Tagging `gate-3`:** only when all of these hold: the owner's run passed, you have confirmed it in the server's
  records, the Supabase advisors are clean (MASTER's Gate 3), and STATUS.md is updated. Then do Phase 4's app side,
  in this order:
  - connect the virtual logger to the app;
  - add the logger check on the handover screen;
  - add the server half: each logger's key is recorded on its shipment, and the server refuses the test key.

  **Then stop and report.** BLE and Phase 5 wait for the owner's go-ahead. Phase 5 should be cut down to what the real
  runs showed is needed.

### Track B: while the blockers stay

1. **C++ scaffolding as rule 6 allows,** in `firmware/` with CMake and Ninja:
   - the interfaces, the vector harness, the failing tests, and a test that the test key is refused;
   - ASan/UBSan and libFuzzer presets, labelled "clang on Linux/WSL only";
   - build only the plain test preset here with the MSYS2 g++ 13. MinGW has no ASan and libFuzzer needs clang, so
     don't try the other presets or install toolchains.
2. **`firmware/WALKTHROUGH.md`:** a step-by-step ladder for the owner — frame encode/decode, then the ring buffer of
   readings, then signing, then history export. Each step names the failing tests it turns green.
3. **Wrap up:** update STATUS.md and HANDOFF.md, then **stop and report**. Don't start anything else.

## 6. Stop and report

When you stop, tell the owner in plain words:
- what is now proven, and on what (real or fake);
- the exact commands or clicks he needs to do next, each with a check that shows it worked;
- what you will do once he has.

Keep the demo-project lockdown first in his list until he confirms it's done.
