# Handoff: what the owner needs to do

Updated on 23 Nov 2026 (the `FINISH_PROMPT.md` run). Questions first, then your list, most urgent first. Claude
carries on with everything these don't block. `docs/agent/STATUS.md` says what is proven and on what.

## Done on 24 Nov (evening): the dev project is set up and `gate-1` is tagged

The migrations `0000` to `0003` are on the dev project `yjvoxozaoiarwbusyqke`, its schema is proven equal to the
tested local one, and the advisors show no errors (`PROGRESS.md`, Block 1). One finding was fixed: a function
Supabase adds to every hosted project could be run by anyone signed out (`0003`).

### Optional, 1 minute: the dev key in `local.properties`

Claude couldn't write it: your deny rule `Edit(./local.properties)` in `.claude/settings.json` blocks shell
appends too, and Claude doesn't work around your deny rules. Nothing waits on it, because Claude passes the dev
URL and key to its builds through environment variables. If you want plain `./gradlew assembleDebug` to point at
the dev project:

1. Supabase dashboard, project `yjvoxozaoiarwbusyqke`, Project Settings, API Keys. Copy the **publishable** key
   named `default` (it starts with `sb_publishable_`), not the secret one.
2. Add these two lines at the end of `local.properties` (they override earlier lines with the same name):

   ```properties
   supabase.url=https://yjvoxozaoiarwbusyqke.supabase.co
   supabase.anon.key=<the sb_publishable_ key>
   ```

3. **Check:** tell Claude; it builds and confirms the debug build carries the dev project's reference and no
   placeholder, without reading the file.

### Still open from before: OTP expiry (security review S7, optional)

Authentication, Providers, Email: set the **Email OTP Expiration** to 600 seconds or less. A 6-digit code is one in
a million, so it shouldn't stay valid for the default hour. Claude doesn't change project settings.

## Questions for you

### Q1. Write access to the dev project: done (24 Nov).

### Q2. Tag `gate-1` on local evidence? Settled: tagged on 24 Nov on the dev project's own advisors.

### Q3. `local.properties`: settled.

`LOCAL_PROPS = yes`. Claude appended the dev URL on 23 Nov without reading the file, and a debug build is checked for
the demo project's ref before anything is installed on a phone.

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

What a change would touch now (23 Nov), so you can weigh it:

- **1 or 2 (signature):** `LoggerProtocol.verify`, `VirtualLogger`, the Python generator and vectors, and the
  firmware's signing step and tests.
- **3 (record):** `LoggerRecord`, the page arithmetic in `LoggerGatt`, the vectors, and firmware step 1.
- **7 (Bluetooth layout):** `LoggerGatt`, `AndroidGattConnection`, the test `FakeLoggerGatt`, the vectors' `gatt`
  section, and firmware step 4.
- **9 and 10 (shipment link, key enrolment):** nothing yet. The server half is written after `gate-3`.
- **11 (MTU):** splitting the header instead would change `LoggerGatt.decodeHistoryNotification`, how
  `BleLoggerLink` collects notifications, firmware `history_notifications`, and the vectors. The phone now asks
  for 517 and refuses below 148; the 104-byte challenge answer needs at least 107 as well.

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
6. **Check:** ask Claude to list the dev project's migrations. Since 24 Nov it lists `0000_legacy_schema` to
   `0003_revoke_platform_function`, each with a timestamp version (`20261124…`), because Claude applied them
   through MCP. Don't run `supabase db push` against this project: it would try to apply them all again.

### 4. Point the app at the dev project (answers Q3)

Once item 3 is done, set `supabase.url` and `supabase.anon.key` in `local.properties` to the dev project's URL and
publishable key (Project Settings, API). Claude doesn't read or change that file.

**Check:** a debug build on a phone shows the sign-in screen, and a code arrives by email when you ask for one.

### 5. An emulator or a phone: done (23 Nov)

Your Galaxy S24 is connected with USB debugging (`RZCY820AG0F`). Claude installed the emulator, an Android 16
system image and a Pixel 7 device called `cch_pixel_api36`, with its files in `D:\Android\avd`. To start it yourself:
`& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd cch_pixel_api36`. Nothing to do. For the phone part of
the checks, keep it unlocked while it is plugged in.

**How many devices Gate 3 needs.** The run uses three accounts: sender, courier and receiver. A phone stays with the
first account that signs in on it, and the courier and receiver must be on different phones. So it needs three
devices, or two with the extra "Clear storage" steps below. Two emulators side by side need about 16 GB of RAM, so
one emulator plus your phone is the easier pair.

#### The Gate 3 run (after items 3, 4 and 5)

You need three email addresses you can read: sender, courier and receiver. Gmail plus-addresses on one inbox work
(`you+sender@gmail.com`, `you+courier@gmail.com`, `you+receiver@gmail.com`); Supabase treats them as three accounts.
The server only lets the sender add people who already have an account, so the courier and the receiver sign up
first. **Claude drives both devices itself**; all it needs from you is each sign-in code as the email arrives.

Rehearsed by Claude on 23 Nov on the emulator against the local stack (`PROGRESS.md`, Block 3 rehearsal), ending
in PASS and ACCEPTED with custody moved between two device IDs. These steps are what the screens really do. Device A is your phone, device B the emulator. The run is built from commit
`05b342e` (the code these steps were checked on). If later work changes these screens before you're ready, Claude
builds that commit in a separate worktree, so the steps still match.

1. Claude installs the debug app on both, built for the dev project.
2. **Device A, courier:** a notifications prompt first (Allow). Enter the courier's email, Send Code, type the code
   from the email, Verify. On "About you": a name, Courier, Continue.
3. **Device B, receiver:** the same with the receiver's email, choosing Receiver.
4. **Sender, on device B** after clearing its storage (`adb shell pm clear`, or Settings, Apps, Cold Chain
   Handshake, Storage, Clear storage, which also gives it a new device key). Sign in with the sender's email, choose
   Sender. On Dispatch the form already has Origin, Destination, Worker ID and Logger ID filled in; **type the
   Courier's Email and Receiver's Email into the same form, then Create Shipment**, which adds both people at once
   (Add People appears afterwards only to change them). Then Dispatch Shipment (Seal Cargo).
   Then clear device B's storage again and sign in as the receiver again.
5. **Device A, courier:** Transit, Scan Consignment QR. Scan the sender's QR code, or type the shipment ID into the
   "Manual QR Payload" box and Apply Code. Take Custody. One reading a minute starts; leave it **on the Transit screen
   for at least 5 minutes**.
6. **Device B, receiver:** Transit, Scan Consignment QR, the same shipment ID, Apply Code. **Stay on Transit until
   its telemetry log shows the readings** (a few seconds): the Handover screen doesn't download them itself, and
   says "Waiting for Complete History" until Transit has. Fill in the vial monitor and shake test on Handover now if
   you want them, before the courier confirms.
7. **Device A, courier:** Handover. The receiver is already chosen under "Taking Over"; Confirm Handover. The screen
   says "Confirmed. Waiting for the other person". Confirming **stops device A's recording**.
8. **Device B, receiver, within 3 minutes of the courier's last reading:** Handover, Record PASS handover. The
   receiver's verdict covers readings right up to this moment, so after more than 3 minutes without one it shows
   UNKNOWN (a gap) and offers no PASS. That is the safety rule working, not a fault; if it happens, run the steps
   again with a new shipment. The screen then says "Custody has moved. The shipment is now ACCEPTED."
9. **Check:** Claude checks the server's records through the MCP server before tagging `gate-3`: two confirmations
   from two different devices, custody moved to the receiver, status ACCEPTED.

### 6. Back up the branch off this PC

`PUSH_POLICY = local-only`, so this work exists only on this PC. The newest bundle, made on 23 Nov, is
`D:\cch-backup-20261123-2123.bundle` (3.6 MB, every branch and tag up to `6509490`, `git bundle verify`: "is okay").
The two older ones (`20261106-2158` and `20261107-0248`) are covered by it and can be deleted.

1. Copy it to cloud storage or a USB drive.
2. **Check:** on the copy, `git bundle verify <path to the copy>` ends with "is okay".

### 7. Claude's safety settings: done

`.claude/settings.json` is in place and holds the deny rules (no reading `local.properties`, keystores or `.env`; no
force-push, hard reset, `git clean`, branch deletion or stash drop). Nothing to do.

### 8. `JAVA_HOME` and `ANDROID_HOME`: done (23 Nov)

Claude set them for your user account, as `FINISH_PROMPT.md` allowed: `JAVA_HOME`, `ANDROID_HOME`,
`ANDROID_AVD_HOME` and `GRADLE_USER_HOME` (the last two on D:). Open a new terminal to pick them up.
**Check:** `.\gradlew.bat --version` shows JVM 17.

### 9. Optional: `actionlint`

Claude can only check the GitHub Actions workflow as YAML. `scoop install actionlint` lets it check the workflow
properly. **Check:** `actionlint --version` prints a version.
