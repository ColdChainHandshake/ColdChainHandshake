# Threat model

What Cold Chain Handshake protects, against whom, and what it doesn't. Written 23 Nov 2026 against branch
`rebuild/trust-core`. This is a prototype. It is not fit for real vaccine or medicine stock, and nothing below is a
claim of compliance with any regulation.

Each "prevented" or "detected" line names the test that shows it. "Fakes" means the test runs against code written
for the test; "real" means it ran on a real database, auth server or device. `docs/agent/STATUS.md` keeps the
current list of what has run where.

## What is being protected

- **The temperature history of a shipment**, from the moment a courier takes custody to the moment a receiver
  accepts it. The question the system answers is: "did this cargo stay in its safe range the whole way, and can
  anyone have changed the record of that?"
- **Who held the shipment when**, and who agreed to each change of hands.
- **The receiver's verdict** (PASS, FAIL or UNKNOWN) and its reason, the vial monitor stage and the shake test.

## Who is involved

| Actor | Holds | Trusted for | Not trusted for |
|---|---|---|---|
| Sender | An account with the sender role | Creating a shipment, choosing its product and logger, naming the courier and receiver | Anything after dispatch |
| Courier | An account, a phone | Carrying the cargo, relaying readings, confirming the handover | The temperature. The courier is the person with a reason to hide a breach |
| Receiver | An account, a phone | Inspecting the cargo and recording a verdict | Making a verdict more lenient than the checks allow |
| Logger | A key pair and a sequence counter | The temperature, once real hardware exists | Nothing yet: with `HARDWARE = none` the logger is virtual (below) |
| Server (Supabase) | Postgres with row-level security and functions | The time a reading arrived, append-only storage, the chain head, custody | Temperatures it is told about |

## Attacks and what happens

### Against the cloud database

| Attack | Outcome | Shown by |
|---|---|---|
| Read or write anything without signing in | Prevented: every table denies the anonymous role | pgTAP `0001_lockdown`, `0002_deny_by_default`; live test `someoneNotSignedInSeesNothing` (real, local stack) |
| A signed-in stranger reads another shipment | Prevented: members only | live test `aStrangerSeesNoneOfAnotherShipment` (real, local stack) |
| Edit or delete a stored reading | Prevented: no client role may update or delete readings; inserts go only through `append_readings` | pgTAP `0003_trust_core` |
| Delete the newest readings (tail deletion) | Prevented on the server; detected on the phone, which compares its copy with the server's head and count | pgTAP CC001 stale head; `RegressionTest.finding2_*`; live test (real) |
| Upload a reading with an edited value, a gap in the sequence, or out of order | Prevented: the server recomputes every hash and needs the next sequence number (CC003, CC004) | pgTAP `0003_trust_core`, `0004_chain_vectors` |
| Upload a back-dated reading | Prevented: earlier than the last reading, or more than 5 minutes before the shipment existed (CC009) | pgTAP; live test |
| Upload a reading dated in the future | Detected, not prevented: stored with `clock_ahead` and flagged on the handover screen, because a logger's clock can drift | pgTAP; `ClockSkewDetectorTest` (fakes) |
| Someone without custody uploads, or the old custodian uploads after handing over | Prevented (CC002) | pgTAP; live test (real) |
| Change a shipment's manifest (logger, product, sender) after creation | Prevented (CC010) | pgTAP |

### Against the handover

| Attack | Outcome | Shown by |
|---|---|---|
| One person confirms for both sides on one phone | Prevented: a phone is registered to one account, and each confirmation names its device (CC005) | pgTAP "one device cannot confirm for both people"; live test's borrowed-phone check (real); emulator rehearsal, two device IDs (real, local stack) |
| Custody moves on one confirmation | Prevented: it moves only when the custodian and the named receiver have both confirmed the same history | pgTAP; `ServerHandoverTest` (fakes); live test and emulator rehearsal (real, local stack) |
| A courier blocks a bad verdict by not confirming | Prevented: a verdict other than PASS quarantines the shipment straight away | pgTAP |
| A receiver records PASS over a breach, a gap or a failed check | Prevented: the app recomputes the verdict and a declared verdict can only be stricter | `HandoverDecisionTest`, `HandoverOrchestratorTest` (fakes) |
| Missing readings pass as safe | Prevented: a silence longer than 3 logging intervals makes the verdict UNKNOWN, never PASS | `RegressionTest.finding6_*` (fakes); seen on the emulator rehearsal (real) |
| A scanned QR code is trusted as the shipment | Prevented: a code is only ever an ID to look up | `ShipmentWorkflowTest` (fakes) |
| A courier confirms early, stops recording and still gets a PASS | Prevented for the app as shipped: the courier keeps recording until custody moves and the receiver's verdict covers the readings up to their own confirmation | `HandoverWindowTest.aCourierWhoConfirmsEarlyAndStopsRecordingCannotGetAPass` (fakes) |
| One reading made between the courier's last renewal and custody moving | Lost, by design: once custody moves the old custodian may no longer upload, and the next person carries the chain on from the head both confirmed. At most about a minute of simulated temperature at each handover; the receiver's own inspection covers that moment (Gate 5 review S11) | Reasoned from `confirm_handover` and `append_readings`; not tested |

### Against the readings themselves

| Attack | Outcome | Shown by |
|---|---|---|
| The courier's phone invents readings | **Not prevented today.** The readings come from a simulator in the courier's own app (finding 5). Each upload is signed by the phone's Keystore key, which proves which phone sent it, not that the temperatures are true | `KeystoreDeviceSignerTest` (real: Galaxy S24, emulator) |
| A modified courier app confirms, uploads readings dated ahead to cover the time until the receiver confirms, then stops | **Not prevented.** The server accepts a reading dated in the future and only marks it `clock_ahead`; the Handover screen shows the clock warning, but the verdict doesn't use it, so the receiver can see PASS. While readings are simulated on the courier's phone the courier can invent any reading anyway; with a signed logger this needs the logger's key. A fix would give no credit for time past a reading's server arrival (Gate 5 review S2) | Reasoned from `0002_trust_core.sql` and `BreachDetector`; not tested |
| A logger's history is edited, cut short, replayed, forked under a copied key, or signed with another key; or a reading on the server differs from the logger's record | Detected at handover whenever a logger is read: FAIL, recorded in the receiver's confirmation, and PASS isn't offered on screen. Today a logger is read only in debug local mode, where the shipment's virtual logger is on the same phone; no server shipment has a logger enrolled (no hardware, `HARDWARE = none`) | `LoggerHandoverCheckTest`, `LoggerHistoryForkTest` (domain); `ServerHandoverTest` (recorded verdict, fake server); `HandoverScreenTest.aChaosLabRewriteThatKeepsTheChainAddingUpIsCaughtByTheLoggerOnScreen` (debug local mode) |
| A logger enrolled on the shipment that the receiver's phone can't reach, or whose key on record can't be read or has no start | UNKNOWN: no PASS is recorded over it | `ServerHandoverTest` (carry-forward, malformed key, no start sequence, enrolment unreadable) |
| A replayed head over Bluetooth | Detected: FAIL, but worded as "a signature doesn't match the logger's key on record", not as a replay. Over Bluetooth the reader fills in its own nonce (`LoggerGatt`), so an old head's signature no longer verifies; the replay sentence fires only for links that return the nonce themselves (Gate 4 review G4-6) | `BleLoggerLinkTest`; `LoggerHandoverCheckTest.aReplayedHeadFails` |
| Someone with rights to the database swaps a shipment's enrolled logger key (or disables the manifest trigger as a superuser) and then signs a history with a key they hold | **Not detected by the receiver.** The enrolled key is locked by `private.protect_shipment_manifest`, but it is not part of the genesis hash, so a receiver takes the server's word for it, unlike every other manifest field. Kept this way so the pinned Gate 3 app (`05b342e`) still works; a manifest v2 that includes the key would close it (Gate 4 review G4-4) | `0005_logger_enrolment.test.sql` (the lock); reasoned from `0004_logger_enrolment.sql:7` |
| A logger presents the published test key | Refused everywhere in the app | `TestLoggerKeyRefusedTest`; `LoggerHandoverCheckTest` |
| A logger overwrote records since custody started, or reported a sensor fault | Detected: UNKNOWN | `LoggerHandoverCheckTest` (fakes) |

## What it does not protect against

- **The virtual logger proves nothing about temperature.** Its key lives inside the courier's app. It exists so the
  protocol and checks run end to end. Only a hardware logger that keeps its own key can be a witness.
- **A logger kept cold while the cargo warms.** A real logger only reports its own temperature. Placement inside the
  cooler, sealing and the receiver's own inspection (vial monitor, shake test) are the defences, not software.
- **Collusion between courier and receiver.** Two people who agree to record a false PASS can do so. The sender can
  read every record, each confirmation keeps its device and server time, and one phone can't play both parts, which
  makes collusion visible afterwards, not impossible.
- **A reinstalled app.** Reinstalling gives a phone a new device ID, so one person with two accounts could confirm
  both sides from one handset across a reinstall. The server would still see two devices.
- **A compromised phone.** Root access can read the session and use the device key. Key attestation (proving the
  key is hardware-backed) is not done.
- **A lost or swapped logger.** When a logger is read, a different logger's ID or key fails the check, and an
  enrolled logger the receiver's phone can't reach leaves the handover UNKNOWN. Today only debug local mode reads a
  logger (the virtual one on the same phone), and no server shipment has one enrolled, so on a two-phone handover the
  logger check is "not checked" and decides nothing.
- **The old demo project.** The owner found it deleted on 26 Nov 2026 (no longer in their Supabase account, and its
  URL no longer resolves). Claude never contacted it, by rule, so this rests on the owner's check.
- **Location privacy (S8, fixed 28 Nov).** While a courier records, the phone uploads a precise fix every minute.
  Since `0005_location_privacy.sql` only the shipment's sender and the person whose phone sent a point can read it,
  and nobody can 30 days after the trip ends; `append_gps_points` takes at most 500 points per call, each with values
  a real fix can have; the courier's phone deletes the points it sent once custody moves on. Left: the sender sees the
  whole route while the trip goes on, and the points stay in the table, unreadable, until the owner runs the purge
  (HANDOFF). Proven in pgTAP (`0006_location_privacy.test.sql`) and applied to the dev project.
- **Availability.** Nothing here resists denial of service or a Supabase outage beyond working offline and sending
  later.
