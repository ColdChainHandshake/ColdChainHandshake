# Gate 2 review (third reviewer, adversarial)

Reviewed commit: `cfb2e85` on branch `gate2-review` in the clean clone `D:\cch-tools\gate2-clone3`. All builds,
mutants and probes ran there. Date: 2026-11-06.

Codes used below:
- "mutant": a deliberate one-line break of the code. KILLED means at least one test failed (the break is caught).
  SURVIVED means every test still passed (the break is not caught).
- Mutant ids (B4, W2, L4 and so on) are the previous reviewer's ids from `D:\cch-tools\gate2-rev2-notes\mutate2.py`.
  The letter is the area: B BreachDetector, W alert worker, N new handover-window rules, M saveShipment / adopt
  checks, T transactions, L logger-silence check, G grouped notifications. New mutants in this review are X1, X2...
- Probe ids (d12, u1 and so on) are the previous reviewer's probe tests in `probes2/Gate2*ProbeTest.kt`: d = domain
  or data probe, u = screen probe, t = timing probe, w = worker probe.

## A. Baseline

Command (clone, unmutated): `timeout -k 10 580 ./gradlew :core-domain:test testDebugUnitTest --console=plain`
Result: `BUILD SUCCESSFUL in 3m 6s`. core-domain: 66 tests, 0 failures. app debug: 412 tests, 0 failures, 0 skipped.
Matches the main repo's numbers at the same commit.

## C. The previous reviewer's probes, re-run at cfb2e85

How: copied `probes2/Gate2*ProbeTest.kt` to `app/src/testDebug/java/com/coldchain/handshake/review/` in the clone and
ran `./gradlew testDebugUnitTest --tests '*Gate2*Probe*'`.
- `Gate2ProbeTest` (domain and data probes) compiled unchanged: 17 tests, 0 failures. Output is identical to
  e4fc659 for d3, d6, d9a-d9d, d12 and d13. Only d1 changed: `twins tolerant=FAIL` (was PASS).
- `Gate2ScreenProbeTest` no longer compiled (it used `SupabaseRemoteDataSource`, deleted in this range). I
  re-expressed u1, u2 and u4 with `HandoverScreenTest`'s local-mode setup (`FakeColdChainServer`, `SyncServiceImpl`;
  same shipment, same readings, same steps). u3 was not on my list and depended on the removed fetch path, so it was
  dropped. 3 tests, 0 failures.
- `Gate2WorkerProbeTest` (w1-w4) no longer sets up: all 4 throw `IllegalStateException: Failed to create default
  settings for SettingsSessionManager`, because `RepositoryProvider` now builds a Supabase client. Not on my list;
  not re-expressed.

Output lines at cfb2e85 (verbatim):
```
PROBE d12: before=UNKNOWN saveShipment(later start) success=true stored start+60m verdict=PASS status=ACCEPTED
PROBE d3: start mid-history=PASS start after handover=PASS
PROBE d13: saveShipment(new genesis) success=true
PROBE d9a: remote ACCEPTED over local IN_TRANSIT with hot readings: success=true status=ACCEPTED handover=null
PROBE d9b: remote ACCEPTED over local QUARANTINED: success=false
PROBE d9c: remote later start over local start: success=true start moved by minutes=60
PROBE d9d: unsent local change after adopting: pending before=1 after=0
PROBE d6: second save success=true rows=1 temps=[5.0]
PROBE d1: twins tolerant=FAIL hotOnly tolerant=FAIL twins default=FAIL
PROBE u1: stored status=QUARANTINED 'FINAL VERDICT: PASS'=true 'Eligible for Acceptance'=true 'Recorded: FAIL...'=true 'Handover Recorded'=true 'Record PASS handover'=false
PROBE u2: preview still offers PASS after the tolerance=true refusal shown=true stored=null status=ARRIVED button still says PASS=true
PROBE u4: stored status=ACCEPTED 'FINAL VERDICT: UNKNOWN'=true 'Recorded: PASS'=true 'Record PASS handover'=false
```

Verdicts:

- **d12: STILL OPEN, and it gives PASS and ACCEPTED on late data.** The readings start 60 minutes after the recorded
  journey start, so the handover check correctly says UNKNOWN (a late first reading). Then
  `ShipmentRepositoryImpl.saveShipment(same shipment with journeyStartedAt + 60 min)` is accepted, and the
  orchestrator records PASS and moves the shipment to ACCEPTED. Why this is a gap and not a design choice:
  - `saveShipment`'s own KDoc says it is where "a bad one is refused whichever screen or engine attempts it", and
    `requireSameManifest`'s KDoc locks fields because "changing one would change the outcome of a shipment already
    under way". `journeyStartedAt` sets the start of the checked window (DECISIONS 2026-10-30: "without them a late
    first reading ... can't be seen"), so it is exactly such a field. Only product and logger are locked
    (DECISIONS 2026-10-31, line 95). No DECISIONS line says the journey start may change once set.
  - `saveShipment` skips the transition check when the status is unchanged, so re-saving an IN_TRANSIT or ARRIVED
    row with a later start is accepted.
  - No test fails if the start moves (nothing in `ShipmentLifecycleTest` or `RoomRepositoriesTest` covers it).
  - Reachability today: the only main-code writer of `journeyStartedAt` is Start Journey in local mode
    (`DispatchScreen.kt:690`, DISPATCHED to IN_TRANSIT), and server mode takes it from the server's custody record.
    I found no current screen that re-saves a moving shipment with a different start. So this is an open hole in the
    designated choke point, not a demonstrated button-press. It still meets the task's rule: a still-open item that
    leads to PASS or ACCEPTED on late data.
- **d3: first half by design, second half part of d12.** "Start mid-history = PASS" is correct: the readings cover
  the whole window, and readings before the start still count towards excursions. "Start after handover = PASS" is
  d12 at its extreme: a journey start later than the truth hides the front of the journey. Nothing in
  `BreachDetector.evaluateForHandover` checks `journeyStartedAt <= handoverAt` (the window start is just put first in
  the checkpoint list, and a negative step is never a gap). Folded into d12.
- **d13: STILL OPEN at the same choke point, but not a PASS path by itself.** `saveShipment` accepts a new genesis
  hash on an existing shipment. Clearing or changing the genesis makes the existing chain fail to verify (integrity
  not VERIFIED, so no PASS); making a PASS from it needs a rebuilt chain, which is tampering, not missing, late or
  duplicate data. The only app callers that save a snapshot (SafetyEngine, QuarantineManager) save it as
  QUARANTINED, which can never reach ACCEPTED (d9b). Does not block on its own; it should be locked with the start.
- **d9a: by design.** DECISIONS 2026-10-30 line 37 (a status from elsewhere may skip steps but never go back) and
  2026-11-06 ("The local status follows the server's WAITING or TRANSFERRED"). No local PASS record is created
  (`handover=null`). Safe as designed: the server moves a shipment to ACCEPTED only on the receiver's confirmation
  against the server's head, and a local QUARANTINED is never overwritten (d9b). The server's acceptance rule is
  outside these client tests.
- **d9c: by design, with a caveat.** In server mode the start is the server's leg-1 custody time
  (`SupabaseColdChainRemote.getShipment`), and the local copy came from the same place, so the two only differ if
  the server's record changes. DECISIONS 2026-11-06 (`ShipmentWorkflow` stores only the server's answer). Safe as
  long as the server's custody record is append-only. Caveat: it moves the start in the same unsafe direction as
  d12, and `adoptRemoteShipment` does not refuse a later start.
- **d9d: by design, harmless.** Shipment rows are no longer uploaded at all: `getPendingShipments` is declared in
  `ShipmentDao.kt:29` and called nowhere in `app/src/main` or `core-domain/src/main`. Shipment changes go to the
  server first through `ShipmentWorkflow` (DECISIONS 2026-11-06). Dropping a pending flag loses nothing.
- **d6: still open, not a PASS path in practice.** `TemperatureEventDao.insertEvent(s)` uses `IGNORE`, so a second
  reading with an existing ID is silently dropped and the save reports success. Reading IDs are random
  (`TE-<uuid8>`, `TemperatureSimulator.kt:195`) or `<shipment>:L<seq>` (`LoggerIngestor.kt:50`, Phase 4). A same-ID
  swap in the middle of a chain breaks the hash links and is caught, and the server path compares the server head.
  Only a swapped last reading on a local-mode shipment (no server head) could slip through, and the simulator never
  reuses an ID. Does not block.
- **d1: fixed.** DECISIONS 2026-11-06 ("Heat and freeze are evaluated as two series"), guarded by
  `ProductProfileEvaluationTest` (see mutants X6 and X7 below).
- **u1, u2, u4: still open, display only, do not block.** One cause: the headline verdict on `HandoverScreen` is
  recomputed from the live readings with "now" taken when the screen is composed (`HandoverScreen.kt:170`); it does
  not show the stored record. None of the three stores a PASS or ACCEPTED:
  - u1 and u4: the form is disabled ("Handover Recorded" shown, no "Record PASS handover" button) and the stored
    record is shown beside it. u1 shows "FINAL VERDICT: PASS / Eligible for Acceptance" on a QUARANTINED shipment,
    which is misleading. u4's PASS was valid when it was recorded.
  - u2: the stale preview still offers PASS, but pressing it is refused ("Cannot record PASS: the checks found
    UNKNOWN", stored=null, status stays ARRIVED), because the orchestrator recomputes with the real time. Guarded by
    `HandoverDecisionTest.theReceiverCannotPassARecordWithAGap` and `theReceiverCannotPassABreach`.

## D. Mutants

Runner: `mutate3.py` in my scratchpad, a copy of the previous `mutate2.py` with the same edit strings, pointed at
the new clone, a fresh results file, no excused tests, every Gradle run wrapped in `timeout -k 10 420`, and old
result XML files deleted before each run so a failed build can't reuse stale results. Command per mutant:
`./gradlew :core-domain:test :app:testDebugUnitTest --console=plain --continue`. Every run below reported 478 tests
(66 + 412), so each result is a full run. `git status --short` was empty after each batch.

Unreliable test noted once: `DispatchScreenTest.startingTheJourneyStartsWatchingForASilentLogger` and
`startingTheJourneyRecordsWhenItStarted` passed at the baseline, then failed on every run from T3 to X9.
- The symptom is a 10 s `waitUntil` timeout in their `startJourney()` helper (`DispatchScreenTest.kt:95`). The
  cause in the log is `IllegalStateException at SettingsUtil.kt:11`: the same supabase-kt `SettingsSessionManager`
  error that broke the worker probes.
- `DispatchScreenTest.setUp` calls `RepositoryProvider.initialize(...)` without a sync service, so Start Journey
  can make the provider build a real Supabase client. Whether that fails depends on test order and state, not on
  the mutant. This is a test-isolation bug for the owner.
- None of the mutants touches `DispatchScreen`, so these two are never counted as kills. A run where only they
  failed counts as a survivor, which is the conservative choice.
- I did not re-run anything to confirm this.

### D1. The previous review's survivors, re-run at cfb2e85

| id | what it changes | result | killed by |
|---|---|---|---|
| B4 | excursion split when the silence equals the limit (`>` to `>=`) | SURVIVED | - |
| B8 | heat and freeze share one running total | KILLED | `ProductProfileEvaluationTest.aFreezeEarlierInTheTripDoesNotUseUpTheHeatBudget` |
| W2 | worker's own rebooking uses `KEEP` | KILLED | `AlertNotificationsTest.aCheckBookedWhileACheckIsRunningIsKept` |
| W10 | `MainActivity` doesn't run a check at launch | KILLED | `AppSmokeTest.theFirstLaunchAsksForNotificationsAndChecksForAlerts` |
| N7 | reading dated after handover: boundary `>` to `>=` | KILLED | `ProductProfileEvaluationTest.aReadingDatedExactlyTheToleranceAfterHandoverIsAllowed` |
| N11 | clock tolerance 5 min to 50 min | KILLED | `ClockSkewDetectorTest.theToleranceIsTheServersOwn` (pins it to the migration's value) |
| M3 | logger locked from creation | KILLED | `ShipmentLifecycleTest.aShipmentsLoggerCanStillChangeBeforeDispatch` |
| M5 | adopting skips the product and logger check | KILLED | `ShipmentLifecycleTest.adoptingFromTheServerCannotChangeTheProductOrTheLogger` |
| T1 | the app's orchestrator (now `handoverOrchestratorFor`) gets no transaction | KILLED | `RoomRepositoriesTest.theAppsOwnOrchestratorRecordsAHandoverInOneTransaction` |
| T2 | `RoomTransactionRunner` runs no transaction | KILLED | `RoomRepositoriesTest.aHandoverThatCannotFinishLeavesNoRecordBehind` (+1) |
| T3 | orchestrator doesn't use its runner | KILLED | `RoomRepositoriesTest.aHandoverThatCannotFinishLeavesNoRecordBehind` (+1) |
| L4 | silence check doesn't fall back to the journey start | KILLED | `LoggerSilenceCheckTest.aJourneyWithNoReadingsYetIsCheckedWhenItWouldGoQuiet` |
| L6 | silence check books the latest shipment's check, not the earliest | SURVIVED (only the flaky `DispatchScreenTest` pair failed) | - |

Survivor explanations so far:
- **B4: equivalent at the boundary for the verdict.** When a silence is exactly `maxSilenceMs`, `>=` closes the
  excursion and starts a new one, but the reading is still credited the same amount (`credited` is capped at the
  same limit either way) and the two excursions' durations are summed per type in `markBreaches`. The gap rule
  (`findDataGaps`, `>`) is separate and unchanged. Only the excursion count and start time differ, which nothing
  safety-relevant reads. Equivalent for PASS/FAIL/UNKNOWN.
- **L6: real test gap, alert timing only.** With two moving shipments, booking the later of their next checks
  delays the silence alert for the one that goes quiet first. Every test uses one moving shipment, where min and
  max are the same. It cannot produce a PASS: the handover computes gaps itself from the readings. But it is part
  of "escalation timing" and is not covered.

## E. Diff read, e4fc659..cfb2e85 (handover, sync, upload, safety)

Read: `HandoverOrchestrator.kt`, `ServerHandover.kt` (new), `ShipmentHistorySyncCoordinator.kt` (moved to
core-domain), `BreachDetector.kt`, `SyncServiceImpl.kt`, `HandoverDao.kt`, `HandoverRepositoryImpl.kt`,
`ShipmentRepositoryImpl.kt`, `ShipmentWorkflow.kt`, the handover part of `HandoverScreen.kt`, `AppNavigation.kt`,
and `confirm_handover` in `supabase/migrations/0002_trust_core.sql` (to see what the server checks).

No new path to PASS on missing, duplicate or late data, or on a server refusal, was found beyond d12 above. What
holds, and what to know:

1. **PASS is stored only after the server accepts it.** `ServerHandover.receive` saves the handover
   (`saveConfirmedHandover`) only when `confirmHandover` succeeded. Any `RemoteRejection` other than CC008 returns a
   failure with nothing stored. With no connection a PASS is refused ("this PASS wasn't recorded"); UNKNOWN and FAIL
   are queued through `processHandover(historySynced = false)`, which can't produce PASS, because missing history
   caps the verdict at UNKNOWN and `decide()` refuses a declared verdict more lenient than the computed one.
2. **A queued PASS is never sent.** `SyncServiceImpl.syncHandovers` marks a pending PASS `REJECTED` and skips it.
   Queued UNKNOWN or FAIL is sent against the server's current head on purpose (DECISIONS 2026-11-06).
3. **Retry rule.** `statusAfter`: any CC code except CC001 (stale head) is permanent (`REJECTED`); CC001 and network
   failures stay `FAILED` and are retried.
4. **The server does not recompute the temperature verdict.** `confirm_handover` checks member role, leg, status,
   the head hash and that a non-PASS has a reason, then stores `p_verdict` as sent. So the receiver's phone
   (`BreachDetector` via `HandoverOrchestrator.assess`) is the only place the temperature verdict is computed, and
   any client bug becomes ACCEPTED on the server. Not a Gate 2 failure (Gate 2 is about the domain logic), but it
   makes the client-side rules the whole defence, which is why d12 matters.
5. **New REPLACE path for handover rows.** `HandoverDao.replaceHandover` (`OnConflictStrategy.REPLACE`), used by
   `saveConfirmedHandover`, lets a later confirmation replace this phone's copy. The orchestrator path still refuses
   a second handover (`aSecondHandoverForTheSameShipmentIsRefusedNotSilentlyLost`), the screen disables the form once
   a record exists (`HandoverScreenTest.aRecordedHandoverIsShownAndCannotBeRecordedAgain`), and the server keeps
   every confirmation and never lets a later PASS lift a quarantine. So the worst case is that this phone's copy shows
   a later PASS while its status (which follows the server) is QUARANTINED. Display only; does not block.
6. **Two-series evaluation.** Gaps, "dated after handover" and "no readings" are computed on the heat series only.
   That series has exactly one reading per timestamp, the same timestamps as the freeze series, so those checks are
   unchanged by the split.
7. **Readings the server refused are not resent and not counted as pending.** `ServerHandover.release` uploads only
   pending readings, so a refused batch never reaches the server, and the receiver judges the server's history. A
   refusal also marks every later reading refused, so the server's history stops there and the receiver sees a gap
   (UNKNOWN) once more than `maxSilenceMs` (3 min on the seed profile) has passed. Narrow edge: a refusal inside the
   last 3 minutes before handover. This is a Phase 3 server-trust matter; the courier's screen shows the upload
   problem (`UploadAudit`). Not blocking for Gate 2.
8. **Signed-out and local mode.** `HandoverScreen` treats a shipment as on the server only when someone is signed in
   and it has a genesis; `needsSignIn` stops a signed-out person recording a server shipment. A local-mode shipment
   is recorded on the phone alone, by design (DECISIONS 2026-10-31, debug-only local mode). `RemoteShipment`
   derives the genesis from the manifest, so a server shipment never arrives with a null genesis.
9. **History sync never replaces a held reading.** A differing local copy is caught by comparing with the server's
   head (`ServerHandoverTest.anEditedReadingOnThisPhoneCannotPass`, `aRemovedTailIsDownloadedAgainAndCounted`).

More of D1 (same runner):

| id | what it changes | result | killed by |
|---|---|---|---|
| L8 | worker books the later of the silence check and the escalation check | KILLED | `AlertNotificationsTest.theNextCheckIsWhicheverComesFirst` |
| G2 | grouped notification shows the lowest level | KILLED | `AlertLifecycleTest.aShipmentsSingleNotificationShowsItsHighestLevel` |
| W4 | worker rebooks itself with no initial delay | TIMEOUT (killed by hang) | test JVM never finished; `timeout -k 10 420` stopped it at 421 s, exit 124. Same as at e4fc659 (19 min hang). No stray test JVM was left afterwards. |

D1 summary: of the previous review's 16 survivors, 13 are now KILLED by a named test, W4 is killed by hang, and 2
survive: B4 (equivalent) and L6 (real test gap, alert timing only). None of the 16 targets had moved out of
existence; T1's edit now targets `RepositoryProvider.handoverOrchestratorFor`.

### D2. New mutants on code changed in e4fc659..cfb2e85

| id | what it changes | result | killed by |
|---|---|---|---|
| X1 | `SyncServiceImpl.syncHandovers` sends a queued PASS (guard `if (false)`) | KILLED | `SyncServiceTest.aPassRecordedWithoutTheServerIsNeverSent` |
| X2 | `statusAfter`: a stale head (CC001) counts as a permanent refusal | SURVIVED (only the flaky `DispatchScreenTest` pair failed) | - |
| X3 | `ServerHandover.receive`'s offline queue calls `processHandover(historySynced = true)` | SURVIVED (only the flaky pair failed) | - |

Survivor explanations:
- **X2: real test gap, safe side for the verdict.** DECISIONS 2026-11-06 says only CC001 stays retryable, but no test
  pins it: `SyncServiceTest.aRefusalIsFinalAndSaysWhy` and `aCourierAcknowledgingIsRefusedAndNotRetried` cover the
  permanent side only. With the mutant, readings refused for a stale head are never sent again. The receiver then
  judges a server history that stops early, which shows as a gap (UNKNOWN), except for the narrow last-3-minutes
  edge in section E item 7. Lost evidence, not a PASS on the normal path.
- **X3: real test gap in "PASS is never stored before the server accepts it".** The queued offline handover is
  recorded with `historySynced = false` (`ServerHandover.kt`, the `queueOffline` lambda), and that is the only thing
  that stops the orchestrator recomputing PASS there. With no server head, `receive` goes straight to
  `queueOffline()` without the PASS refusal branch. A caller that passes `verdict = null` (the API allows it) would
  then get a locally stored PASS and a local ACCEPTED status on history that could not be synced, which is missing
  data. Reasoned from the code, not run as a probe, to keep to one run per mutant.
  - Why the tests miss it: `ServerHandoverTest.withNoSignalAPassCannotBeRecorded` only declares PASS, which `assess`
    refuses before the queue is reached. `withNoSignalAFailIsKeptHereAndSentLater` declares FAIL, which stays FAIL.
  - Why the app is safe today: `HandoverDecisionForm.kt:98` always passes an explicit verdict, and a declared
    UNKNOWN or FAIL is kept even when the recomputed verdict is PASS. The queued PASS would also never be sent (X1).
  - So the code at cfb2e85 is correct, but this protection could be removed without any test failing.

More of D2:

| id | what it changes | result | killed by |
|---|---|---|---|
| X4 | `receive` loses the "offline PASS isn't recorded" branch | KILLED | `ServerHandoverTest.aPassIsNeverStoredBeforeTheServerAgrees` |
| X5 | `receive` assesses with `historySynced = synced` (ignores a missing server head) | SURVIVED (only the flaky pair failed) | - |
| X6 | freeze series built as the heat series | KILLED | `ProductProfileEvaluationTest.aSafeTwinAtTheSameTimeCannotHideAFrozenReading`, `aHotTwinCannotHideAFrozenReading`, `aHotReadingTwinnedWithAFrozenOneKeepsItsTime` |
| X7 | one worst reading per timestamp, direction ignored (the old single-series rule) | KILLED | `ProductProfileEvaluationTest.aHotTwinCannotHideAFrozenReading`, `aHotReadingTwinnedWithAFrozenOneKeepsItsTime` |
| X8 | `AppNavigation` opens the main app before the owner check | SURVIVED (only the flaky pair failed) | - |
| X9 | a confirmation the server refused is stored as if accepted | KILLED | `ServerHandoverTest.aPassIsNeverStoredBeforeTheServerAgrees`, `theSenderCannotConfirmAndNothingIsStored` |

Survivor explanations:
- **X5: equivalent.** When the server head is null, `receive` goes straight to `queueOffline()`, which recomputes
  through `processHandover(historySynced = false)`, so the flag given to `assess` never reaches what is stored. A
  declared PASS that `assess` would now allow is refused by `processHandover`. Only the computed reason text could
  differ, and `processHandover` recomputes that too.
- **X8: real test gap, privacy rather than verdict.** DECISIONS 2026-11-06 says the main app opens only after the
  owner check. `LocalOwnerTest` covers the repository side, but no test drives the `AppNavigation` gate, so the
  previous person's records could show during the check with no test failing. Not a PASS path.

D summary: 25 mutants run (16 re-runs, 9 new). 18 KILLED by a named test, 1 killed by hang (W4), 6 SURVIVED. (The runner's own log, `mutants3.jsonl` in my scratchpad, marks L6, X2, X3, X5 and X8 as KILLED, because the flaky `DispatchScreenTest` pair failed in those runs; they are counted here as survivors.) The 6:
- B4 and X5: equivalent.
- L6: real gap (alert timing).
- X2: real gap (retry rule, safe side).
- X3: real gap (an unguarded protection against storing PASS before the server agrees).
- X8: real gap (privacy).

## B. Regression check of the unsafe PASS paths found before

1. **Final readings credited up to the handover: holds.** `findExcursions` credits the last reading up to
   `windowEnd` (`?: windowEnd`, unchanged in this range). Guarded by
   `ProductProfileEvaluationTest.aShipmentStillHotAtHandoverCountsTheTimeUpToHandover`: hot readings end 2 min 59 s
   before handover, and the test asserts FAIL. Also guarded by `LoggerSilenceCheckTest.aHotLoggerThatStopsIsCountedUpToNow`.
   Both killed mutant N10 at e4fc659 and both still exist. I read them and did not re-run N10, since the line didn't
   change.
2. **Duplicate timestamps hiding a breach, including a hot twin hiding a frozen one: holds.** The old
   `worstAtEachTime` target is gone. Its replacement, `seriesFor`, is guarded by X6 and X7 (both killed).
3. **No check at journey start: the check holds, but its input does not.**
   - `HandoverDecisionTest.aFirstReadingLongAfterTheJourneyStartedIsUnknown` (start 2 h before the first reading,
     UNKNOWN) and `aShipmentWhoseJourneyStartIsUnknownCannotPass` (null start, UNKNOWN) still exist. They killed O1 and
     N9 at e4fc659, and `evaluateForHandover` is unchanged.
   - But the start itself can be moved later on a moving shipment through `saveShipment`, and no test fails (d12 in
     section C). This turns exactly this protection off.
4. **Readings dated after the handover: holds.**
   - `ProductProfileEvaluationTest.readingsDatedAfterTheHandoverCannotShowTheLoggerWasRunning` (N5) and
     `aSmallClockDifferenceAtHandoverIsAllowed` (N6) still exist.
   - The previous survivors are now killed: N7 by `aReadingDatedExactlyTheToleranceAfterHandoverIsAllowed`, and N11 by
     `ClockSkewDetectorTest.theToleranceIsTheServersOwn`, which ties the tolerance to the migration's value.
5. **Cloud-read failures letting PASS through: holds.**
   - Orchestrator: `HandoverDecisionTest.aHandoverCanBeRecordedWhenTheHistoryCouldNotBeFetched` (UNKNOWN) and
     `anEditedChainIsStillAFailWhenTheHistoryCouldNotBeFetched`.
   - Server path: `ServerHandoverTest.withNoSignalAPassCannotBeRecorded`, and X4 is killed. X5 is equivalent.
   - The old screen test `HandoverScreenTest.whenTheHistoryCannotBeFetchedTheReceiverCanStillRecordWhatTheyFound` no
     longer exists. Its server-mode role is now `ServerHandoverTest`'s, and in local mode the phone holds every
     reading itself.
6. **A second handover lost or replacing the first: holds on the orchestrator path.**
   - `HandoverDecisionTest.aSecondHandoverForTheSameShipmentIsRefusedNotSilentlyLost` (A3) and
     `RoomRepositoriesTest.aHandoverRowWithTheSameShipmentCannotReplaceTheFirst` (A1, A2) still exist, and
     `insertHandover` is still `ABORT`.
   - New in this range: `saveConfirmedHandover` uses `REPLACE` on purpose (KDoc in `HandoverDao.kt`), with no
     DECISIONS line. Display only; see section E item 5.
7. **A PASS stored before the server accepts it, or a queued PASS sent later: FAILS the check for one protection (X3); the rest holds.**
   - A queued PASS is never sent: X1 is killed.
   - The offline PASS refusal is tested: X4 is killed.
   - A refused confirmation is never stored: X9 is killed.
   - The `historySynced = false` on the offline queue is not tested: X3 survived. The code is right today, and the UI
     can't reach the gap, but nothing would catch its removal.

## Gate 2 criteria, one by one

1. **Coverage of heat, freeze, gaps, alert de-duplication, escalation timing, lifecycle transitions and all three
   verdicts: met.** One test per area:
   - Lifecycle: `ShipmentLifecycleTest.everyPairOfStatusesIsEitherLegalOrRejected` checks every pair of statuses
     against the legal set, so every allowed transition and every illegal one is checked. The legal set is
     CREATED to DISPATCHED to IN_TRANSIT to ARRIVED to INSPECTED to ACCEPTED, plus QUARANTINED from DISPATCHED,
     IN_TRANSIT, ARRIVED and INSPECTED, then REPLACEMENT_REQUESTED.
   - Heat and freeze: `ProductProfileEvaluationTest.aFreezeEarlierInTheTripDoesNotUseUpTheHeatBudget` (B8),
     `aHotTwinCannotHideAFrozenReading` (X6, X7).
   - Gaps: `HandoverDecisionTest.aGapInTheReadingsRecordsUnknownAndHoldsTheShipment`.
   - Alert de-duplication: `LoggerSilenceCheckTest.aRepeatCheckRaisesNoSecondAlert`,
     `AlertLifecycleTest.theAlertIdIsDeterministic`.
   - Escalation timing: `AlertLifecycleTest.theEscalationIntervalIsPinned`,
     `AlertEscalationEngineTest.deterministicEscalationFollowsWorkerSupervisorPharmacistChain`.
   - The three verdicts: `HandoverDecisionTest.cleanReadingsRecordAPass`, `aFailureRecordsItsReason`,
     `aGapInTheReadingsRecordsUnknownAndHoldsTheShipment`.
   Two notes:
   - The rule tests live in `app/src/test/.../safety` and `.../handover`, not in `:core-domain` (whose own tests are
     crypto, logger and uploader). They test the domain code all the same.
   - Escalation timing has one uncovered case (L6, two moving shipments).
2. **Gap means UNKNOWN never PASS, FAIL and UNKNOWN need a reason, and the orchestrator recomputes the verdict: met.**
   Each has a test that fails when it is broken (e4fc659 mutants N5, N8, N9, N10, O1, H3, H4, H6, V9, all with
   tests that still exist; here N7, N11, X6, X7).
3. **No path gives PASS on missing, duplicate or late data, or when the server refuses: NOT met (d12).**
4. **Every surviving mutant explained: met.** B4 and X5 are equivalent; L6, X2, X3 and X8 are real test gaps (above).

## Verdict

**UNMET**

Failed items:

1. **d12 (section C), with the second half of d3: PASS and ACCEPTED on late data through the shipment choke point.**
   - `ShipmentRepositoryImpl.saveShipment` lets a moving shipment's `journeyStartedAt` move later, and nothing checks
     the start against the handover time.
   - Probe result at cfb2e85: `before=UNKNOWN saveShipment(later start) success=true stored start+60m verdict=PASS
     status=ACCEPTED`.
   - `requireSameManifest` locks product and logger, but not the field that decides where the checked window starts.
     No DECISIONS line allows it, and no test fails.
   - No current screen was found that does this. The hole is in the layer DECISIONS 2026-10-30 names as the single
     place that refuses bad changes.
   - Fix direction: lock `journeyStartedAt` (and `genesisHash`, d13) once set, in `saveShipment` and against a later
     value in `adoptRemoteShipment`. Treat `journeyStartedAt > handoverAt` as UNKNOWN. Add failing tests first.
2. **Regression check B7 fails (X3).** B7 asks whether removing the protection against "a PASS stored before the
   server accepts it" makes a test fail. For one of its protections, no test fails: `historySynced = false` in
   `ServerHandover.receive`'s offline queue, which keeps an offline handover from being recomputed as PASS.
   - The code is correct at cfb2e85.
   - The screen always declares a verdict, so no user can reach the gap today.
   - Fix direction: a `ServerHandoverTest` case that calls `receive(verdict = null)` with no signal and asserts
     nothing is stored as PASS.

Not blocking, recorded for the owner:
- u1, u2 and u4: the handover screen's headline verdict is a live recomputation, not the stored record.
- d6: a reading with a duplicate ID is silently dropped.
- d13: the genesis can change on a moving shipment.
- Real test gaps: L6, X2 and X8.
- Section E item 4: the server stores the receiver's verdict without recomputing it.
- Section E item 7: a refused upload in the last 3 minutes before handover.

The clone's `git status --short` was empty at the end of the review.
