# Handoff: what the owner needs to do

Updated on 28 Nov 2026. Questions first, then your list, most urgent first. Claude carries on with everything these
don't block. `docs/agent/STATUS.md` says what is proven and on what.

## Questions for you

- **Q5 (28 Nov, ~01:42): may Claude run the full proof again?** The full proof at `001b0bb`
  (`./gradlew :core-domain:test testDebugUnitTest testReleaseUnitTest lintDebug assembleRelease assembleDebug
  --continue`, about 25 minutes) was stopped by Claude Code itself because the PC ran critically low on memory while
  it ran in the background (no emulator was running; the S24 was connected). Claude Code says not to start it again
  without you. Every change since the last full proof (`67b034e`) passed its own targeted tests. `gate-5` needs that
  full proof and the reviewer's re-check, so it is **not tagged yet**. Reply "run the proof" (or run it yourself and
  paste the last lines), and close other programs first if you can.

- Q1, write access to the dev project: done (24 Nov).
- Q2, tag `gate-1`: settled, tagged on 24 Nov on the dev project's own advisors.
- Q3, `local.properties`: settled. You set it yourself on 26 Nov (only `sdk.dir`, the dev URL and the dev
  publishable key). A debug build made without overrides points at the dev project (checked 26 Nov).
- Q4, the logger protocol: settled on 26 Nov (`PROTOCOL_Q4 = drafts`). `docs/protocol/LOGGER_PROTOCOL.md` is
  signed off as it stood, with the minimum Bluetooth MTU of 148 written in as a rule.

## Your list

### 0. State of your devices and accounts (night of 27 to 28 Nov)

- **Nothing is recording anywhere.** `SHIP-2DE57A` and `SHIP-CB7F34` (both multi-leg, both ACCEPTED) are finished.
- **The S24 now has two installs, both signed in:** the debug app as Gate3 Courier and the release app
  (`com.coldchain.handshake`, signed with your upload key) as Gate3 Receiver. The debug app's Nearby devices and
  location were revoked with `adb` for one test and then allowed again through the app's own prompt.
- **The emulator:** the release app is signed in as Courier Two (`you+courier2@gmail.com`, a new account on the dev
  project); its Nearby devices was granted by `adb`, location and camera refused. The debug app is signed in as
  Gate3 Sender.
- **No codes are needed for the next steps** (Gate 5's review, Phase 4, Phase 6). Claude will write here if a
  device step needs you.
- **The release key:** `D:\cch-keys\cch-release.jks` and `keystore.properties` were created today (PKCS12, alias
  `cch-release`, random passwords in the file, never printed or committed). Back up `D:\cch-keys`. A signed release
  build needs the environment variable `CCH_RELEASE_KEYS=D:\cch-keys\keystore.properties`; without it the release
  APK is built unsigned, as on CI.
- **Three false alerts on the dev project:** DATA_GAP on `SHIP-49A74F` from the receiver's account (10:46:55 to
  10:46:56 on 27 Nov: 49:29, 12:58 and 718:54). They came from a bug fixed that day (`91db6ea`); clients can't delete alerts,
  so they stay. Delete them in the dashboard if you like.

### 1. Optional, 1 minute: OTP expiry (security review S7)

Authentication, Providers, Email: set the **Email OTP Expiration** to 600 seconds or less. A 6-digit code is one in
a million, so it shouldn't stay valid for the default hour. Claude doesn't change project settings.

### 2. Optional: leaked password protection

The dev project's security advisor warns "leaked password protection disabled" (Authentication, Providers, Email,
or Attack Protection). The app signs in with email codes, not passwords, so it doesn't affect it; turning it on only
clears the warning.

### 3. Optional: move Docker's disk off C:

C: has 64 GB free today, so nothing is urgent. If Docker ever hangs on "starting": quit Docker Desktop, run
`wsl --shutdown` in PowerShell, then start Docker Desktop. To move it: Docker Desktop, Settings, Resources,
Advanced, Disk image location, a folder on D:, Apply & Restart. **Check:** `docker ps` returns a table.

### 4. Optional: `actionlint`

`scoop install actionlint` lets Claude check the GitHub Actions workflow before pushing. CI on GitHub checks it
anyway now that the branch is pushed. **Check:** `actionlint --version` prints a version.

## Done (newest first)

- **26 Nov: the old demo project is gone.** You found `ljqwaqesiszczixbclwg` is no longer in your Supabase account
  and its URL no longer resolves. The lockdown script (`docs/agent/lockdown_demo_project.sql`) is no longer needed.
  Claude never contacted that project, by rule, so this rests on your check.
- **26 Nov: backups.** `rebuild/trust-core` is pushed to `origin` at the end of every session (never `main`, never
  with force), and a verified bundle is still made on D: each session. Older bundles on D: are covered by newer ones.
- **24 Nov: Gate 3 passed on the dev project, `gate-3` tagged.** `SHIP-3E4005` went from creation to ACCEPTED with
  two confirmations from two devices (`PROGRESS.md`, CONTINUE run, second session, Block 3).
- **24 Nov: the dev project is set up and `gate-1` is tagged.** Migrations `0000` to `0003` are on
  `yjvoxozaoiarwbusyqke`, applied through MCP, so its migration history has timestamp versions (`20261124…`).
  Don't run `supabase db push` against it: it would try to apply them all again.
- **23 Nov: emulator, phone and environment.** The S24 (`RZCY820AG0F`) has USB debugging; the emulator
  `cch_pixel_api36` lives in `D:\Android\avd` (start it with
  `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd cch_pixel_api36`). `JAVA_HOME`, `ANDROID_HOME`,
  `ANDROID_AVD_HOME` and `GRADLE_USER_HOME` are set for your user account. `.claude/settings.json` holds the deny
  rules.

## Reference: the Gate 3 run as it was done (pinned build `05b342e`)

Kept as the record of how `gate-3` ran. Later builds change these screens (5.2 and later), so a new run needs new
steps, written when it happens.

A phone stays with the first account that signs in on it, and the courier and receiver must be on different
phones, so the run needs three devices, or two with the "Clear storage" steps below. Three Gmail plus-addresses on
one inbox (`you+sender@gmail.com`, `you+courier@gmail.com`, `you+receiver@gmail.com`) are three accounts to
Supabase. The server only lets the sender add people who already have an account, so the courier and the receiver
sign up first. Device A is your phone, device B the emulator.

1. Claude installs the debug app on both, built for the dev project.
2. **Device A, courier:** a notifications prompt first (Allow). Enter the courier's email, Send Code, type the code
   from the email, Verify. On "About you": a name, Courier, Continue.
3. **Device B, receiver:** the same with the receiver's email, choosing Receiver.
4. **Sender, on device B** after clearing its storage (`adb shell pm clear`, which also gives it a new device key).
   Sign in with the sender's email, choose Sender. On Dispatch, type the Courier's Email and Receiver's Email into
   the creation form, then Create Shipment, then Dispatch Shipment (Seal Cargo). Then clear device B's storage again
   and sign in as the receiver again.
5. **Device A, courier:** Transit, Scan Consignment QR, type the shipment ID into "Manual QR Payload", Apply Code,
   Take Custody. Leave it **on the Transit screen for at least 5 minutes** (that build records only while Transit
   is on screen).
6. **Device B, receiver:** Transit, the same shipment ID, Apply Code. Stay on Transit until its telemetry log shows
   the readings (that build's Handover screen doesn't download them itself).
7. **Device A, courier:** Handover, Confirm Handover. "Confirmed. Waiting for the other person". Confirming stops
   device A's recording in that build.
8. **Device B, receiver, within 3 minutes of the courier's last reading:** Handover, Record PASS handover. "Custody
   has moved. The shipment is now ACCEPTED."
9. **Check:** Claude checks the server's records through MCP: two confirmations from two different devices, custody
   moved to the receiver, status ACCEPTED.
