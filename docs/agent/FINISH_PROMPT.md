# Finish prompt — Cold Chain Handshake, from gate-2 to done (written 7 Nov 2026)

You are Claude Code on the owner's Windows PC in `D:\ColdChainHandshake`, branch `rebuild/trust-core`. The owner wants
you to do as much of the remaining work as possible yourself and only hand him what truly needs his hands or his
accounts. He runs you with `--dangerously-skip-permissions`, so **nothing will prompt him before you act**. The
`ask` rules in `.claude/settings.json` (git push, `supabase db push`/`link`) will not stop you. The rules in section 3
are therefore the only brakes. Follow them exactly.

## 1. Read first

1. `CLAUDE.md`
2. `docs/agent/HANDOFF.md`, `docs/agent/PROGRESS.md`, `docs/agent/STATUS.md`, `docs/agent/DECISIONS.md`. These are newer
   than any prompt; where they record a decision, it stands.
3. `docs/agent/RESUME_PROMPT.md` sections 3 and 5, and `docs/agent/MASTER_PROMPT.md` sections 0, 2, 5, 6 and 7.
   Where they conflict with this file, this file wins.

State when this file was written (verify with `git log`, `git tag -n`):
- `gate-0` and `gate-2` tagged. `gate-1` waits on the dev project's advisors. `gate-3` waits on a device run.
- Last proof: 75 core-domain, 423 debug, 366 release unit tests; 233/233 pgTAP on local Docker;
  `LiveSupabaseIntegrationTest` 3/3 on the local stack.
- Firmware scaffolding in `firmware/` (38 tests, 37 failing by design). Test-key guard in every build.
- Known issue: `DispatchScreenTest` is order-dependent (global Supabase client); Phase 5 removes it.

Shell: Git Bash, `/d/...` paths, `./gradlew`, `timeout -k 5 30` around Docker and network commands. Run long Gradle
jobs in the background.

## 2. What the owner has granted for this run

The owner may have edited these lines before starting you. Read them as they stand.

```text
SETUP_TOOLS     = yes   # you may install Android SDK cmdline-tools, platform-tools, emulator, one system image and AVDs,
                        # only under D:\Android and D:\cch-tools, and set user env vars JAVA_HOME, ANDROID_HOME,
                        # ANDROID_AVD_HOME, GRADLE_USER_HOME to folders on D:
LOCAL_PROPS     = yes   # you may write supabase.url and supabase.anon.key (the dev project's URL and publishable key,
                        # fetched through the MCP server) into local.properties. Never write any other key there, never
                        # print the file, never commit it
DEV_PROJECT     = yjvoxozaoiarwbusyqke   # the only hosted project you may change
PROTOCOL_Q4     = owner # owner = wait for his sign-off on LOGGER_PROTOCOL.md; drafts = treat the current drafts as
                        # signed off and note it in DECISIONS.md
FIRMWARE_OWNER  = me    # unchanged: firmware bodies stay TODO(owner)
HARDWARE        = none  # unchanged
PUSH_POLICY     = local-only
```

## 3. Hard rules (no prompt will catch you, so these are absolute)

1. **Never touch the demo project** (`ljqwaqesiszczixbclwg`) in any way. Never run `supabase link` or `db push` against
   any project except `DEV_PROJECT`. Before any CLI or MCP call that writes to a hosted project, print the project ref
   it targets and check it equals `DEV_PROJECT`.
2. **Never push**, never create remotes, never `gh repo create`, while `PUSH_POLICY = local-only`.
3. No `--force`, `reset --hard`, `git clean`, `branch -D`, stash drop, history rewrites, `rm -rf`, or deleting anything
   outside the repo, `D:\cch-tools` and `D:\Android`. Never uninstall anything. Never edit the registry except the user
   env vars in section 2. Never touch other projects' Docker containers or images.
4. Never print, log or commit secrets: anon/publishable keys go through env vars or `local.properties` only, and the
   service-role key, database password and access tokens are never fetched or used.
5. Edit source with the Edit and Write tools, not scripts that rewrite files.
6. Never weaken a test to make it pass. Never tag a gate without the proof its criteria ask for. Never mark something
   "proven on a real system" unless it ran on one.
7. Work at `high` effort. Each gate's adversarial reviewer is a single subagent with hard limits: one sitting, at most 25
   mutants, findings appended to a file as it goes. Never leave a background agent running across a usage reset.
8. Keep `PROGRESS.md`, `STATUS.md` and `HANDOFF.md` current after every completed step, so a usage-limit cut-off loses
   nothing. If you hit the weekly limit, the next session restarts from this file.

## 4. Setup you do yourself (Block S)

Do these first, each with its check recorded in PROGRESS.md:

1. **Env vars:** `JAVA_HOME = D:\cch-tools\jdk-17.0.20.1+1`, `ANDROID_HOME` = the existing SDK, `GRADLE_USER_HOME =
   D:\gradle-home`, `ANDROID_AVD_HOME = D:\Android\avd` (user scope, via PowerShell `[Environment]::SetEnvironmentVariable`).
2. **Emulator:** install `cmdline-tools;latest`, `emulator`, `platform-tools` and one Google APIs x86_64 system image
   matching the app's `targetSdk` into the SDK (or `D:\Android\Sdk` if the SDK is on C: and space is short). Create one
   Pixel AVD under `D:\Android\avd`. Start it headless if needed (`emulator -avd <name> -no-snapshot-save`). Check:
   `adb devices` lists `emulator-5554 device`. The PC has 24 GB RAM: run one emulator, not two.
3. **Phone:** if `adb devices` lists a physical device as `device`, use it. If it shows `unauthorized`, ask the owner to
   accept the prompt on the phone. Record its model and Android version (`adb -s <serial> shell getprop`).
4. **Dev project:** check through MCP that it answers `list_tables` (if it times out, it is paused: put "click Restore"
   at the top of HANDOFF and continue with work that doesn't need it). Check whether `apply_migration` exists (if not,
   `.mcp.json` is still read-only: ask the owner to remove `&read_only=true` and restart you).
5. **Disk:** report free space on C: and D:. If C: drops under 5 GB, stop downloading and tell the owner.

## 5. Work plan

Work through these blocks in order. When a block is blocked on the owner, write exactly what you need at the top of
HANDOFF.md, tell him in one short message, and move to the next block that isn't blocked. Come back when he answers.

### Block 1 — Gate 1 and the dev project
- Apply the migrations to `DEV_PROJECT` through MCP (`apply_migration`, in order). Run `get_advisors` (security and
  performance), fix what they report test-first with new migrations, re-run pgTAP locally.
- Fetch the project URL and publishable key through MCP and write them to `local.properties` (if `LOCAL_PROPS = yes`).
- Tag `gate-1` when the dev advisors are clean and the Gate 1 criteria in MASTER 5.1 hold.

### Block 2 — On a real device
- `./gradlew connectedDebugAndroidTest` on the emulator and on the phone: `KeystoreDeviceSignerTest` and any other
  instrumented tests. Fix what breaks.
- Install the debug build on both devices. Using `adb` (`shell input`, `uiautomator dump`, `screencap`), drive a smoke
  pass: launch, sign-in screen visible, rotation, background and return. Save screenshots under
  `docs/agent/evidence/`. Fix what breaks.

### Block 3 — Gate 3, the real two-phone handover on the hosted dev project
- Accounts: three emails the owner can read. Suggest Gmail plus-addresses on one inbox (`name+sender@gmail.com`,
  `+courier`, `+receiver`), which Supabase treats as three accounts.
- Drive both devices yourself through adb, following HANDOFF's Gate 3 steps (courier and receiver sign up first; the
  sender on the device whose storage is then cleared). The only thing you need from the owner is each email code: ask
  for it in one short message, wait, then type it with `adb shell input text`.
- If the built-in email sender hits its rate limit, wait and retry; tell the owner if it blocks for more than an hour.
- Verify the result in the server's records through MCP (two confirmations from two device IDs, custody moved,
  status ACCEPTED). Run the advisors again. Update STATUS.md. Tag `gate-3`.

### Block 4 — Phase 4, app side
In order, each test-first, each with its proof in PROGRESS.md:
1. Connect the virtual logger to the app behind `TelemetryRepository`, replacing the simulator in the main flavour
   (keep the simulator as a debug-only tool).
2. The logger check on the handover screen (history verified, head fresh against a challenge, tampered or truncated
   history shown as FAIL or UNKNOWN with a reason).
3. The server half: each logger's public key recorded on its shipment at creation (protocol choice 10), the server
   refuses the test key, pgTAP tests for both, applied to dev.
4. The BLE adapter behind the same interface, against the GATT layout in LOGGER_PROTOCOL.md, tested with a fake GATT
   layer. With `HARDWARE = none` it is not run on hardware; say so in STATUS.md.
5. Gate 4 needs the firmware library to pass the same vectors, which is the owner's work. Record Gate 4 as
   "app side done, waiting for the owner's firmware" and continue to Block 5. Do not write firmware bodies.
   If `PROTOCOL_Q4 = owner` and he hasn't signed off, do items 1–4 against the draft and list in HANDOFF what changes
   if he changes a choice.

### Block 5 — Phase 5, architecture and completeness (MASTER 5.5)
ViewModels and Hilt (removes the global Supabase client and the order-dependent test), a foreground service for
recording, WorkManager for sync and escalation, multi-leg custody, then the Android audits on the emulator: rotation,
process death (`adb shell am kill`), Doze (`adb shell dumpsys deviceidle force-idle`), notifications permission,
accessibility labels. Re-run Block 2's device checks at the end. Gate 5 per MASTER, with its reviewer.

### Block 6 — Phase 6, verification kit and hand-off (MASTER 5.6)
README that says plainly what is proven and on what; a demo script with screenshots from the devices; the
`docs/agent/STATUS.md` final version; the 16-finding table all closed or explained; CI workflow checked with
`actionlint` if present (it cannot run on GitHub while local-only). Final full run, final bundle
`D:\cch-backup-final-<date>.bundle`, tag `gate-6`, then **stop**.

## 6. Talking to the owner

- One short message whenever you need something only he can do, starting with what it unblocks.
- At each tag: a three-line report (what is proven now, on what; what's next).
- When you stop for good, give the final summary: what is proven on real systems, what only against fakes, what is
  left for him (his firmware, a real logger if he sets `HARDWARE`, pushing to GitHub if he changes `PUSH_POLICY`).
