# Handoff: what the owner needs to do

Rewritten on 6 Nov 2026 at the start of a new session. Questions first, then your list, most urgent first. Claude
carries on with everything these don't block. `docs/agent/STATUS.md` says what is proven and on what.

## Questions for you

### Q1. Can Claude write to the dev Supabase project? (blocks Gate 1 and Gate 3)

`.mcp.json` points at dev project `yjvoxozaoiarwbusyqke` with `read_only=true`. On 6 Nov at 22:00 the project
answered `get_project_url` but timed out on `list_migrations`, so it is probably paused. No migration has reached any
hosted project. Claude won't remove the flag, because that is your access decision. Answer by doing item 3 below.

### Q2. Should Claude tag `gate-1` on local evidence?

`gate-0` is tagged. `gate-1` is not, by design: Gate 1 asks for the dev project's advisors to be clean, and that waits
on Q1. Everything else Gate 1 asks for is proven locally (`PROGRESS.md`, Phase 1), and on 6 Nov the local Docker
database passed all 233 checks with no advisor issues. Reply "tag gate-1 now" or "wait for the dev project".

### Q3. Does `local.properties` still point at the old demo project?

Claude may not read that file. If it does, any phone build would sign in against the demo project, which has none of
the new server functions. See item 4.

### Q4. Review the logger protocol's open design decisions

`docs/protocol/LOGGER_PROTOCOL.md` is a draft until you sign it off here. The C++ tests Claude writes for you follow
it, so a change later means changing those tests too. Each line is a choice Claude made that you may want to change:

1. **Signature:** ECDSA P-256 with SHA-256, signatures as raw 64-byte `r || s`. Ed25519 is the alternative; Android
   only verifies it from API 33.
2. **High and low `s`:** verifiers accept both. Requiring low `s` would stop one signature having two valid forms.
3. **Record:** 22 bytes: sequence number, millisecond timestamp, temperature in hundredths of a degree, 16 flag bits.
   A set unknown flag bit counts as a sensor fault.
4. **History hash:** one running SHA-256 hash over the logger's whole life, starting from its ID, so the logger
   only has to store the latest hash.
5. **Batches:** the logger signs the first and last sequence number with the hashes before and after; batches must
   join end to end.
6. **Freshness:** the phone sends a 16-byte random challenge and the logger signs its newest hash with it.
7. **Bluetooth layout:** one service, seven characteristics under the base UUID `6f1c0000-8e4a-4c43-9b3a-2d7c5e1f0a00`,
   history sent in pages sized to the connection's MTU.
8. **Memory:** a ring buffer of 8,192 records, 5.7 days at one reading a minute.
9. **Shipment link:** when custody starts, the server records the logger's newest sequence number, so shipment
   reading `n` is logger record `start + n`.
10. **Key enrolment:** the sender records the logger's public key when creating the shipment, so the courier never
    supplies it.
11. **Minimum Bluetooth MTU (not in the protocol yet):** a signed batch header is 145 bytes, and Bluetooth's default
    MTU of 23 leaves 20 bytes per notification, too few for even one 22-byte record. The C++ tests assume the logger
    sends nothing below an MTU of 148, so the phone must ask for a larger one (Android allows up to 517). The other
    choice is to split the header across several notifications.

Reply with "signed off" or with the numbers you want changed.

## Your list

### 1. Lock down the old demo project (urgent, 2 minutes)

Anyone who has the old APK can still read and rewrite the demo project's shipments, readings, alerts and handovers.
Claude is not allowed to touch that project.

1. Open the demo project (`ljqwaqesiszczixbclwg`) in the Supabase dashboard, then SQL Editor, then New query.
2. Paste all of `docs/agent/lockdown_demo_project.sql` and click Run.
3. **Check:** the final result table shows `rls_enabled = true` and `policies = 0` on every row.
4. Tell Claude it's done, so this item can come off the list.

The old APK stops syncing, which is intended. No data is deleted.

### 2. Docker: working now, but move its disk off C:

On 6 Nov at 22:00 `docker ps` answered and the local database ran all its tests, so nothing needs fixing today.
C: has only 11 GB free (97% full), which is what can make Docker hang again.

1. Docker Desktop, Settings, Resources, Advanced, Disk image location. Choose a folder on D: and click Apply & Restart.
2. **Check:** `docker ps` in a new terminal returns a table without an error.

Only if Docker hangs on "starting" again: quit Docker Desktop, run `wsl --shutdown` in PowerShell, then start Docker
Desktop. This stops the local database, which is fine; Claude restarts it.

### 3. Make the dev project reachable and decide on write access (answers Q1)

1. Open https://supabase.com/dashboard/project/yjvoxozaoiarwbusyqke and click Restore if the project is paused.
2. Choose one:
   - **Let Claude apply migrations:** delete `&read_only=true` from the URL in `.mcp.json`, then start a new Claude
     Code session.
   - **Keep it read-only:** after each phase, run these yourself. `link` asks for the database password, which you
     can reset in the project's database settings.

     ```powershell
     npx supabase login
     npx supabase link --project-ref yjvoxozaoiarwbusyqke
     npx supabase db push
     ```
3. Turn on email sign-in: Authentication, Sign In / Providers, Email, enabled.
4. Make the emails carry a code, not just a link. A hosted project sends a sign-in link by default, and the app asks
   for the 6-digit code. In Authentication, Emails (Templates), edit **Magic link** and **Confirm signup** and add
   `<p>Your code: {{ .Token }}</p>`, then Save. (Source: Supabase's "Passwordless email logins" guide, read on 7 Nov.)
5. Know the email limit. Supabase's built-in email service sends only a few emails an hour per project, and the
   Gate 3 run needs 4 to 6. If the app shows "email rate limit exceeded", wait an hour, or set up your own SMTP
   (Authentication, Emails, SMTP Settings) and then raise the limit under Authentication, Rate Limits. The exact
   number is shown on that page.
6. **Check:** in a new Claude session, ask Claude to list the dev project's migrations. It should list `0000` to `0002`
   (after `db push`), or no timeout error (before).

### 4. Point the app at the dev project (answers Q3)

Once item 3 is done, set `supabase.url` and `supabase.anon.key` in `local.properties` to the dev project's URL and
publishable key (Project Settings, API). Claude doesn't read or change that file.

**Check:** a debug build on a phone shows the sign-in screen, and a code arrives by email when you ask for one.

### 5. An emulator or a phone (needed for Gate 3 and Gate 5)

Today this PC has no emulator, no system image, no Android Studio, and `adb devices` lists nothing.

**Quickest: your own phone.** Settings, About phone, tap Build number seven times. Then Developer options, USB
debugging on. Plug it in and accept the prompt. **Check:** `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices`
lists it as `device`.

**Emulator:** keep its files on D:, because C: is nearly full.

1. Install Android Studio with the SDK on D: (for example `D:\Android\Sdk`; choose the path in the setup wizard).
2. Set the emulator's folder to D: once, then open a new terminal:

   ```powershell
   [Environment]::SetEnvironmentVariable('ANDROID_AVD_HOME', 'D:\Android\avd', 'User')
   ```
3. In Android Studio, Device Manager, create a Pixel device with a recent Google APIs image, and start it.
4. **Check:** `adb devices` lists `emulator-5554  device`.

**How many devices Gate 3 needs.** The run uses three accounts: sender, courier and receiver. A phone stays with the
first account that signs in on it, and the courier and receiver must be on different phones. So it needs three
devices, or two with the extra "Clear storage" steps below. Two emulators side by side need about 16 GB of RAM, so
one emulator plus your phone is the easier pair.

#### The Gate 3 run (after items 3, 4 and 5)

You need three email addresses you can read: sender, courier and receiver. The server only lets the sender add people
who already have an account, so the courier and the receiver sign up first. Claude has not been able to run this on a
device, so if a screen differs from these steps, note what you saw and carry on.

1. Install the debug app on each device: with one attached, `./gradlew installDebug` (or
   `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` for each, serials from `adb devices`).
2. **Device A, courier:** open the app, enter the courier's email, Send Code, type the code from the email, Verify.
   Enter a name, choose Courier, Continue.
3. **Device B, receiver:** the same with the receiver's email, choosing Receiver.
4. **Sender:** on a third device, or on device B after Settings, Apps, Cold Chain Handshake, Storage, Clear storage.
   Sign in with the sender's email and choose Sender. On Dispatch, fill in Origin, Destination and Logger ID, then
   Create Shipment. Enter the Courier's Email and Receiver's Email, then Add People. Then Dispatch Shipment (Seal
   Cargo). Leave the shipment's QR code on screen.
   - If you used device B: once dispatched, clear its storage again and sign in as the receiver again.
5. **Device A, courier:** Transit, Scan Consignment QR, and scan the sender's QR code. On an emulator without a camera,
   type the shipment ID into the scanner's text box. Then Take Custody. Readings start; leave it running at least
   5 minutes, with the app open.
6. **Device B, receiver:** Transit, Scan Consignment QR, the same shipment. Open Handover and wait for the readings to
   arrive.
7. **Device A, courier:** Handover, Confirm Handover, naming the receiver.
8. **Device B, receiver:** choose the verdict the screen offers (PASS if nothing went wrong), then Confirm Handover.
9. **Check:** the receiver's screen shows the shipment as accepted. Tell Claude the shipment ID. Claude then checks the
   server's custody and handover records through the MCP server before tagging `gate-3`: two confirmations from two
   different devices, custody moved to the receiver, status ACCEPTED.

### 6. Back up the branch off this PC

`PUSH_POLICY = local-only`, so this work exists only on this PC. The newest bundle, made on 7 Nov after `gate-2`, is
`D:\cch-backup-20261107-0248.bundle` (1.9 MB, every branch and tag up to `7aaf3ba`). The older
`D:\cch-backup-20261106-2158.bundle` misses that night's work and can be deleted.

1. Copy it to cloud storage or a USB drive.
2. **Check:** on the copy, `git bundle verify <path to the copy>` ends with "is okay".

### 7. Claude's safety settings: done

`.claude/settings.json` is in place and holds the deny rules (no reading `local.properties`, keystores or `.env`; no
force-push, hard reset, `git clean`, branch deletion or stash drop). Nothing to do.

### 8. Set `JAVA_HOME` and `ANDROID_HOME` for your own builds

Both are still unset on this PC. Claude sets them per command. To build yourself, run this once, then open a new
terminal:

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME', 'D:\cch-tools\jdk-17.0.20.1+1', 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', "$env:LOCALAPPDATA\Android\Sdk", 'User')
```

If you install Android Studio, point `JAVA_HOME` at its `jbr` folder instead. **Check:** `.\gradlew.bat --version`
shows JVM 17.

### 9. Optional: `actionlint`

Claude can only check the GitHub Actions workflow as YAML. `scoop install actionlint` lets it check the workflow
properly. **Check:** `actionlint --version` prints a version.
