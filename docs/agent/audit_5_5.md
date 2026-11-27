# 5.5 audits: what was checked, found and left (27 Nov 2026)

MASTER 5.5 item 5 asks for the Android skill audits (permission security, intent security, ANRs, Play policy, R8,
Compose performance, edge-to-edge). The permissions, ANR and Compose audits and their fixes are in `PROGRESS.md`
(27 Nov, `4793425`). This file holds the rest: intent security, Play policy, R8 and the lint warnings left.

## Intent security

Checked on the release APK's own manifest (`aapt2 dump xmltree`, build of `db0ce9b`'s parent), not the merged
manifest in `build/intermediates`, which was a stale copy from 30 Oct.

| Component | Exported | Guard |
|---|---|---|
| `MainActivity` | yes | the launcher; no intent extras are read anywhere in `app/src/main` |
| `RecordingService` | no | started only by the app; `foregroundServiceType` `connectedDevice\|location` |
| WorkManager `SystemJobService` | yes | `BIND_JOB_SERVICE` (system only) |
| WorkManager `DiagnosticsReceiver`, `ProfileInstallReceiver` | yes | `DUMP` (shell and system only) |
| Every other library service, receiver and provider | no | |

The recording notification's `PendingIntent` is `FLAG_IMMUTABLE` and names `MainActivity` explicitly. Nothing to fix.

## Play policy (for whoever publishes it; this is a prototype and isn't published)

- **Foreground service types:** `connectedDevice` (the logger connection) and `location` each need a declaration in
  the Play Console with a short video of the feature.
- **Location:** foreground only (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`; no background location). It is
  recorded while a courier holds a shipment and is sent to the server with the readings, so the data safety form
  lists precise location as collected and shared with the operator's server, for app functionality.
- **Camera:** QR scanning on the phone; frames never leave it.
- **Notifications:** asked for at the first start on Android 13 and later (seen on the S24 and the emulator, 27 Nov).
- **ML Kit's transport library** (`com.google.android.datatransport`, pulled in by barcode-scanning 17.3.0) can send
  ML Kit usage logs to Google; the data safety form must say so, or the dependency be configured off.
- Email addresses (sign-in) and names (profile) are collected for accounts.

## R8 (5.6)

One missing keep rule found on a device and fixed test-first: ML Kit's component registrars lost their no-argument
constructors under R8's full mode (`55bc5c9`). `checkReleaseKeepRules` now fails `assembleRelease` if that comes
back. Signed-in flows, uploads, the recording service, handover confirmations and the scanner ran under R8 on the S24
and the emulator with no `NoSuchMethod`, `ClassNotFound` or serialization error (`PROGRESS.md`, `SHIP-2DE57A`).

## Lint: 0 errors, 12 warnings, each justified

`./gradlew lintDebug` went from 22 warnings to 12 (`f3636bc`: unused template colours, a redundant activity label,
an always-true SDK check, `fullBackupContent` beside the data extraction rules, the round launcher icon now used, and
an `InlinedApi` that lint can't see is guarded).

| Warning | Count | Why it stays |
|---|---|---|
| `UseKtx` | 10 | Style only: `SharedPreferences.edit { }`, `createBitmap`, `Bitmap.set` instead of the platform calls. Same behaviour; changing ten call sites that write the device ID, the recording store and pending handovers isn't worth the risk before the gate |
| `AndroidGradlePluginVersion` | 1 | Gradle 9.9.2 exists; 9.8.2 is pinned on purpose (DECISIONS, toolchain), and CI uses the wrapper |
| `ObsoleteSdkInt` (`mipmap-anydpi-v26`) | 1 | The folder name only; minSdk is 26, so it applies to every device. Renaming it changes nothing |
