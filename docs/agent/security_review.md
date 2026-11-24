# Security review: `git diff gate-2..HEAD` (branch `rebuild/trust-core`)

Reviewer: read-only agent, one sitting, 2026-11-24. Scope: the 75 files changed since tag `gate-2` (25 commits).
No Gradle, no network, no Supabase project contacted. Findings are appended as they are verified.

Severity: high / medium / low / info. Status: CONFIRMED = code path read end to end; PLAUSIBLE = depends on
something not traced (runtime, library behaviour).

## Findings

### S1. A Bluetooth peer can crash the app with one forged batch header (OutOfMemoryError)

- Severity: **medium** (denial of service by any nearby device that answers as a logger; no verdict is raised).
  Today `LoggerHandoverCheck` and `BleLoggerLink` are not called from `:app` (grep finds no caller outside
  `core-domain` and the adapter), so the crash is reachable only once the logger check is wired into a screen.
- Where: `core-domain/src/main/java/com/coldchain/handshake/logger/ble/BleLoggerLink.kt:67`, and the same pattern
  in `core-domain/src/main/java/com/coldchain/handshake/logger/LoggerHistoryVerifier.kt:110` (older than gate-2,
  but it now receives batches built from Bluetooth bytes).
- Scenario: `LoggerGatt.decodeHistoryNotification` (`LoggerGatt.kt:117-119`) takes `firstSeq` and `lastSeq` as two
  independent `i64`s from the peer. A peripheral answers a history request with a single 145-byte batch header
  `u8(2) i64(0) i64(0x7FFFFFFFFFFFFFFF) ...` (with or without a records page before it). `readHistory` then
  evaluates `(header.firstSeq..header.lastSeq).toList()`. `LongRange` is not a `Collection`, so `toList()` boxes
  every value into an `ArrayList` until the heap is exhausted. This runs outside `withTimeout` (it doesn't suspend),
  and `attempt` catches only `Exception`, so the `OutOfMemoryError` escapes `Result` and kills the process. A span of
  a few hundred million (e.g. `firstSeq=1, lastSeq=500_000_000`) also stalls the calling thread for seconds and
  allocates gigabytes before failing. No key is needed: the size check runs before any signature is verified.
- Status: **CONFIRMED** by reading the path `LoggerGatt.decodeHistoryNotification` -> `BleLoggerLink.readHistory:59-67`
  -> `attempt` (`:126-132`). Not executed (no Gradle in this review).
- Fix: check the count arithmetically before building anything, e.g.
  build nothing proportional to the claimed range. Require `records` non-empty,
  `records.first().seq == header.firstSeq`, `records.last().seq == header.lastSeq`, and
  `records.zipWithNext().all { (a, b) -> a.seq != Long.MAX_VALUE && b.seq == a.seq + 1 }`; otherwise throw
  INCOMPLETE. (Do not compute `lastSeq - firstSeq`: with `firstSeq = Long.MIN_VALUE` it overflows even when
  `lastSeq >= firstSeq`. If a subtraction is wanted, use `Math.subtractExact` and map `ArithmeticException` to MALFORMED.)
  Do the same in `LoggerHistoryVerifier.verifyAnyKey:110`. Also consider a cap on records per batch equal to the
  `maxRecords` that was requested: a header claiming more than was asked for is malformed.
- Test: in `BleLoggerLinkTest`, a `FakeLoggerGatt` that answers with one record page and a header covering
  `0..Long.MAX_VALUE` must return `Result.failure(LoggerGattException(INCOMPLETE))` promptly (wrap the test body in
  a short `withTimeout`). Same for `LoggerHistoryVerifierTest` with a `LoggerBatch(firstSeq=0, lastSeq=Long.MAX_VALUE, records=[one])`.

### S2. No cap on records per batch: a peer can make the check hold hundreds of MB before verifying

- Severity: **low** (memory/liveness only; outcome is still FAILED or UNKNOWN, never PASS).
- Where: `core-domain/src/main/java/com/coldchain/handshake/logger/ble/BleLoggerLink.kt:58-73` and
  `core-domain/src/main/java/com/coldchain/handshake/logger/LoggerHandoverCheck.kt:78-88`.
- Scenario: `readHistory(from, 500)` asks for 500 records but accepts any number, as long as the header's range
  matches what arrived. `newestSeq` comes from the unsigned STATUS read, so a peer can report `Long.MAX_VALUE`
  and keep the loop going. Each request may stream record pages for up to `timeoutMs` (15 s) before the header;
  each accepted batch is kept in `batches` until all 64 pages are in, then the verifier boxes each batch's range
  again (`LoggerHistoryVerifier.kt:110`). At realistic BLE notification rates that is ~10^5 records per request and
  up to 64 of them retained, plus up to ~16 minutes of the check waiting (64 x 15 s). Whether that exhausts a given
  phone's heap depends on throughput, hence PLAUSIBLE.
- Status: **PLAUSIBLE** (the code path is CONFIRMED; the memory figure depends on radio throughput).
- Fix: in `readHistory`, treat `records.size > maxRecords` (or a header claiming more) as `MALFORMED`, and stop
  collecting once more than `maxRecords` records have arrived without a header. Optionally bound the total
  wall-clock time of `LoggerHandoverCheck.check`.
- Test: `BleLoggerLinkTest` with a fake that answers a request for 2 records with 3 records and a matching header
  must fail with `MALFORMED`.

### S3. GATT callbacks complete whichever operation is pending, without checking it is theirs

- Severity: **low** (reliability and confusion; cannot produce PASS because every batch and head is still
  signature-checked against the enrolled key).
- Where: `app/src/main/java/com/coldchain/handshake/data/ble/AndroidGattConnection.kt:77-99,114-118,156-168`.
- Scenario: `finish()` completes `pending` from any of `onMtuChanged`, `onCharacteristicRead`,
  `onCharacteristicWrite` and `onDescriptorWrite` without checking the operation type or characteristic UUID.
  (a) If an `onMtuChanged` arrives that this class didn't ask for (for example an MTU exchange started by the peer
  or the stack; not traced on a real Android version), it completes a pending `read` with an `Int`; the `as ByteArray` cast at `:125` throws `ClassCastException`, which `attempt`
  turns into UNKNOWN. During `open()` the same event completes the first CCCD write early, so the second
  `writeDescriptor` is refused while the first is still in flight and connecting fails. (b) A read callback that
  arrives after its operation timed out completes the next read with the previous characteristic's bytes. `pending`
  is also written on a coroutine thread and read on a binder thread without `@Volatile`.
- Status: **PLAUSIBLE** (the code path is CONFIRMED; whether the stray callbacks happen depends on the Android
  stack and the peer, and this class has not run on hardware, per its own KDoc).
- Fix: keep the expected kind and UUID with `pending` (e.g. `Pending(kind, uuid, deferred)`), ignore callbacks that
  don't match, update `attMtu` from unsolicited `onMtuChanged` without touching `pending`, and mark `pending`
  `@Volatile` (or guard it with the mutex's owner thread).
- Test: an instrumented or Robolectric test of the callback object: with a read pending, calling `onMtuChanged`
  must leave the read pending; a read callback for another UUID must be ignored.

### S4. Text from the Bluetooth peer is shown verbatim among the verdict reasons

- Severity: **info**.
- Where: `core-domain/src/main/java/com/coldchain/handshake/logger/LoggerHandoverCheck.kt:74-76`.
- Scenario: `info.loggerId` is an untrusted `lp()` string of up to ~500 bytes (`LoggerGatt.kt:159-163`). A peer can
  answer with an ID such as `"X.\nThe logger's own signed history matches the shipment's readings"`. The outcome is
  still FAILED, so the verdict is right, but the reason list shown to the receiver carries attacker-chosen text that
  imitates a passing line.
- Status: **CONFIRMED** (path read; no screen renders it yet).
- Fix: quote and truncate the ID, and drop control characters, e.g. `"…logger \"${info.loggerId.take(40).filter { !it.isISOControl() }}\"…"`.
  Also consider rejecting a protocol-invalid ID in `decodeDeviceInformation`.
- Test: `LoggerHandoverCheckTest` with a fake whose ID contains `\n`: the reason must contain no newline.

### S5. The temperature card treats NaN as within range

- Severity: **info** (display only; the verdict uses `SafetyEngine`/`TemperatureVerdict`, not this card).
- Where: `app/src/main/java/com/coldchain/handshake/ui/components/TemperatureCard.kt` (`of`), used by
  `TransitScreen.kt`.
- Scenario: `celsius > profile.maxC || celsius < profile.minC` is false for `NaN`, so a NaN reading shows
  "NaN°C WITHIN PRODUCT RANGE" in green. `TemperatureEvent.temperature` is a `Double` and the simulator's
  `latestTemperature` is a `Double?`; I did not trace a path that produces NaN today (server values pass through
  `tempCentiC`, whose `roundToInt()` throws on NaN), hence PLAUSIBLE.
- Status: **PLAUSIBLE**.
- Fix: write the in-range test positively (`celsius in profile.minC..profile.maxC` -> IN_RANGE, else
  OUT_OF_RANGE), or treat `!celsius.isFinite()` as NONE/OUT_OF_RANGE.
- Test: `TemperatureCardTest`: `TemperatureCard.of(Double.NaN, profile).level != IN_RANGE`.

### S6. Build-time Supabase overrides: unescaped values and a substring-only demo guard

- Severity: **info** (developer-controlled input; nothing reaches a release build).
- Where: `app/build.gradle:26-31,59-65`.
- Scenario: (a) `CCH_APP_SUPABASE_URL`/`_KEY` are pasted into `buildConfigField` inside `"\"...\""` without
  escaping, so a value containing `"` breaks or injects into the generated `BuildConfig.java`. (b) The demo-project
  refusal matches the project ref as a substring, so a custom domain or proxy that fronts the demo project is not
  refused. (c) Whatever key is set is baked into the debug APK; setting a `service_role` key by mistake would ship
  it in the APK. The override itself is correctly confined to the `debug` build type, and when only one of the two
  variables is set it falls back to `local.properties`, which the same guard checks.
- Status: **CONFIRMED** (read).
- Fix: reject values containing `"` or `\`; refuse a key whose JWT payload says `"role":"service_role"` (or whose
  prefix is `sb_secret_`); keep the substring guard but document that it is a tripwire, not a control.
- Test: a Gradle TestKit check, or simply a CI step that runs `assembleDebug` with
  `CCH_APP_SUPABASE_KEY` set to a fake service-role JWT and expects failure.

### S7. Six-digit email code: hosted project settings decide its strength

- Severity: **info**.
- Where: `supabase/config.toml:233-235` (`otp_length = 6`, `otp_expiry = 3600`, unchanged) and the new
  `supabase/templates/sign_in_code.html` and `[auth.email.template.*]` sections, which make the 6-digit code the
  sign-in factor ("expires within the hour").
- Scenario: the local config only affects the local stack and nothing in the diff weakens auth. On the hosted
  project, the same template change (HANDOFF) means a 1-in-10^6 code valid for an hour, limited only by the
  project's verification rate limits (`token_verifications = 30` per 5 min per IP locally).
- Status: **CONFIRMED** (config read; hosted settings not inspected, by the rules of this review).
- Fix: when applying the template to the hosted project, also set the OTP expiry to 10 minutes or less and confirm
  the verify rate limit; change the template's "within the hour" text to match.
- Test: none in code; a HANDOFF checklist item.

### S8. Deleting the last readings gives UNKNOWN, not FAIL

- Severity: **info** (UNKNOWN still quarantines; `decide()` refuses anything more lenient than the computed verdict).
- Where: `core-domain/src/main/java/com/coldchain/handshake/logger/LoggerHandoverCheck.kt:118-127`.
- Scenario: a courier who withholds or deletes the newest readings (say the ones holding an excursion) leaves logger
  records at or after `newestHeld` with no matching reading. They are classed "not sent yet" -> UNKNOWN, the same as
  an honest phone that is behind. With no readings at all, `newestHeld = 0` and every record is UNKNOWN. The
  verdict engine maps `LOGGER_CHECK_UNKNOWN` to UNKNOWN and `targetStatusFor(UNKNOWN)` is QUARANTINED
  (`HandoverVerdictEngine.kt`, `verdict = when` and `targetStatusFor`), and `decide()` rejects a declared verdict
  below the computed one (`HandoverModels.kt:6-10` orders PASS < UNKNOWN < FAIL). So no PASS, but evidence of
  suppression is reported as "couldn't tell" rather than "tampered". Intended, per the test
  `recordsTheShipmentDoesntHoldYetAreUnknownNotPassed`.
- Status: **CONFIRMED**.
- Fix (optional): once the shipment's chain is known to be closed at release, treat logger records up to the
  release point that the shipment lacks as FAILED; keep UNKNOWN only for records after the last synced head.
- Test: shipment chain closed at seq N, logger holds N+1..N+3, expect FAILED.

### S9. Device helper passes arguments through the device shell

- Severity: **info** (developer tooling; inputs are typed by the developer).
- Where: `tools/device/drive.sh:44` (`shell input text "$3"`), same pattern at `:28`; `:58` puts the email into a
  Mailpit URL unencoded (local only).
- Scenario: `adb shell` joins its arguments and the device's `sh` parses them again, so a shipment ID or email
  containing `;`, a backtick or `$( )` runs as a command on the device. `code_for` only passes on `\d{6}`, and
  `$xy` comes from layout `center` fields, so those paths are fine. Nothing prints a key or token.
- Status: **CONFIRMED** (read).
- Fix: quote for the remote shell, e.g. `shell "input text $(printf %q "$3")"`, or refuse arguments outside
  `[A-Za-z0-9@._%+-]`.
- Test: `focus_type 1 1 'a;id'` on an emulator should type the literal text and run nothing.

## What was checked and found sound

- **Verdict can only be lowered.** `HandoverVerdictEngine.evaluate` only appends `LOGGER_CHECK_FAILED` (-> FAIL)
  or `LOGGER_CHECK_UNKNOWN` (-> UNKNOWN, in `UNKNOWN_REASONS`); PASSED and NOT_CHECKED add nothing. `decide()`
  refuses any declared verdict below the computed one. `HandoverOrchestrator` threads `loggerCheck` through
  `assess`, `evaluate` and `record` unchanged, defaulting to NOT_CHECKED.
- **NOT_CHECKED only for a null link.** Every failed read after that is UNKNOWN (`unreadable`), a wrong ID is
  FAILED, a missing enrolled key is UNKNOWN.
- **Test key.** `LoggerHistoryVerifier.verify` refuses `TestLoggerKey` (matched on X, so the mirror point,
  compressed, raw and SPKI forms are all caught) before any other check; `verifyAnyKey` is `internal` to
  `:core-domain`, so `:app` can't reach it. The published vector key's X equals `TestLoggerKey.X_HEX`.
- **Truncation and a lying STATUS.** `newestSeq` from STATUS is unsigned, but the signed head must equal the last
  verified batch's `lastSeq` and `endHash` (HEAD_MISMATCH). Empty batches with a head past `startSeq`, a reset
  logger (head below `startSeq`), and a peer that returns null early all end in FAILED.
- **Replay.** On BLE the head's nonce is never read off the wire (`decodeChallengeResponse` uses the reader's own
  nonce), so a replayed head fails the signature check: FAILED, reported as BAD_SIGNATURE rather than STALE_HEAD.
  Each check draws a fresh 16-byte `SecureRandom` nonce. (The nonce proves liveness, not proximity: a relayed
  connection to the real logger is outside what this protocol can detect.)
- **Gaps, faults, unknown flags** go to UNKNOWN; readings older than the oldest verified record are skipped from
  the comparison only when the verifier has already raised GAP, BROKEN_CHAIN or BAD_SIGNATURE.
- **Overflow and endless peers.** `from = batch.lastSeq + 1` can wrap and a peer can return backward batches, but
  `maxPages` (64) bounds the loop and ends in UNKNOWN. `timed` bounds each I/O step; `attempt` rethrows
  `CancellationException`.
- **Parser bounds.** `LoggerGatt.decoding` maps underflow to MALFORMED and rejects trailing bytes; `lp()` checks
  negative and over-long lengths; pages require `remaining == count * 22`; header and challenge sizes are exact;
  the public key must be 65 bytes starting `04`. Hex strings fed to the verifier are produced locally, so
  `hexToBytes` can't throw. `LoggerProtocol.verify` returns false on malformed keys and signatures.
- **Debug network config.** `debug_network_security_config.xml` and the manifest that applies it are in
  `app/src/debug` only; cleartext is limited to `127.0.0.1` and `localhost` with `includeSubdomains="false"`, no
  `<base-config>` and no user-CA trust; `app/src/main/AndroidManifest.xml` sets neither `usesCleartextTraffic` nor
  a network config. The `CCH_APP_SUPABASE_*` override is inside `buildTypes.debug` only.
- **Local mode is debug-only.** `DebugTools.ENABLED` is `false` in `app/src/release` (locked by
  `ReleaseHasNoDebugToolsTest`). The `localOnly` precedence over the owner check in `AppRootViewModel` is
  unchanged from the gate-2 `AppRoot`.
- **SavedStateHandle** holds a boolean (`localOnly`) and a shipment ID only; no tokens, emails or profile data.
  The Ready state still waits for `confirmedOwner == userId` before showing anything.
- **SupabaseAccountRepository** change only moves the cache write before the state emit, keyed by `user.id`.
- **Supabase.** `config.toml` and the template change only the local stack's emails; the pgTAP change adds five
  assertions that manifest columns are locked (tightening, not loosening).
- **Manifest.** The only exported component is still the launcher `MainActivity`; new permissions are
  `BLUETOOTH` (max SDK 30) and `BLUETOOTH_CONNECT`; `allowBackup="false"` kept.
- **Tools.** `find_element.py` and `list_screen.py` only parse JSON from stdin; `gen_logger_vectors.py` publishes
  the intended test key and nothing else; no script prints secrets.

Not covered: runtime behaviour (no Gradle, no device), the hosted Supabase project, firmware C++ beyond the diff
listing, the uncommitted working-tree changes, and the doc and evidence files (read only where they made claims).

## Summary

No path found turns a tampered, truncated, replayed, wrong-key or test-key logger history into PASS, and the logger
outcome can only lower a handover verdict: `evaluate` only adds failure reasons and `decide` rejects anything more
lenient than the computed result. The real problems are in handling bytes from an untrusted Bluetooth peer. A
single forged batch header with a huge `firstSeq..lastSeq` span makes `BleLoggerLink.readHistory` (and the
verifier) box the whole range into a list, and the resulting `OutOfMemoryError` escapes `Result` and kills the app
(S1, medium; not reachable from a screen yet because the logger check isn't wired in). A peer can also make the
check retain large batches for up to about 16 minutes (S2), and the Android GATT adapter completes whatever
operation is pending from any callback (S3). Release builds are not affected by the debug-only cleartext config or
the Supabase env overrides, and nothing in the Supabase changes weakens auth. The rest are informational:
peer-chosen text in reason lines, a NaN temperature shown as in range, unescaped build-time overrides, OTP strength
left to the hosted project's settings, tail truncation classed UNKNOWN rather than FAIL, and device-shell
re-parsing in `drive.sh`. Fix S1 before the logger check goes on a screen.

Count: high 0, medium 1 (S1), low 2 (S2, S3), info 6 (S4 to S9).

## Triage (main session, 24 Nov)

Each fix was written test-first, and each new test failed before its fix. Proof in `PROGRESS.md`, Step 1.5.

| # | Decision | Change and proof |
|---|---|---|
| S1 | Fixed | `recordsRunExactly` walks the records once and builds nothing in the size of the claimed range; used by `BleLoggerLink` and `LoggerHistoryVerifier`. `aHeaderClaimingAHugeRangeIsRefusedWithoutBuildingIt` and `theVerifierRefusesAHugeClaimedRangeWithoutBuildingIt` first failed with `OutOfMemoryError` |
| S2 | Fixed | A batch with more records than were asked for is MALFORMED, counted as pages arrive. `aBatchWithMoreRecordsThanAskedForIsMalformed` failed first. A wall-clock bound on the whole check is left for 4.1, when the screen calls it |
| S3 | Fixed | `GattOperationSlot` keeps the kind and characteristic of the one operation in flight (`@Volatile`); stray or late callbacks are ignored, an MTU change nobody asked for only updates the MTU. `GattOperationSlotTest`, 7 tests: 4 failed against a stub, and the 4 "ignored" cases fail against the old finish-whatever-is-waiting behaviour |
| S4 | Fixed | The device's ID is quoted, stripped of control characters and cut to 40 characters. `aLoggerIdCanNotWriteItsOwnReasonLines` failed first |
| S5 | Fixed | The in-range test is asked positively (`celsius in minC..maxC`). `aValueThatIsNotANumberIsNeverWithinRange` failed first |
| S6 | Fixed | `app/build.gradle` refuses a quote or backslash in any BuildConfig value, and an `sb_secret_` key or a JWT with `"role":"service_role"`, never printing the value. Checked by building with fake keys: secret, service-role JWT and a quote refused; an anon-shaped JWT accepted. The demo guard is documented as a tripwire |
| S7 | Owner (HANDOFF) | Not changed: the owner asked that the sign-in flow stay as it is. HANDOFF asks for an OTP expiry of 10 minutes or less when the hosted templates are set |
| S8 | Accepted for now | UNKNOWN still quarantines and can't be declared down to PASS. Revisited in 4.3, when the server records the logger's start sequence and the shipment's closing head (`DECISIONS.md`) |
| S9 | Fixed | `drive.sh` refuses text outside `A-Z a-z 0-9 @ . _ % + -` before it reaches `adb shell` or the mail catcher's URL. Checked: `a;id` and `$(reboot)` refused, emails, shipment IDs and `%s` accepted |
