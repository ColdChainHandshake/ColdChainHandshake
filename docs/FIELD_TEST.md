# Field test

A script for trying Cold Chain Handshake outside the test suite, and for filming it. Written 23 Nov 2026. This is a
prototype: use water bottles or empty vials, never real vaccine or medicine stock.

Part A works today with two phones (or a phone and the emulator) and the debug build. Part B needs a hardware
logger, which doesn't exist yet (`HARDWARE = none`); it is the test that would make the temperature claim real.

## Before you start

- The dev project is restored, and the app is built for it (`docs/agent/HANDOFF.md`, "Do this now").
- Three accounts: sender, courier, receiver (Gmail plus-addresses on one inbox are fine).
- The handover steps in `HANDOFF.md`, "The Gate 3 run", work. Do that run first; everything below builds on it.
- For filming: screen recording on both phones (Android's own Screen record tile), and a third camera on the cooler.
  Hide notifications first (Do not disturb), because a recording shows them.

## Part A: what you can show now (debug build, virtual readings)

The readings in Part A come from a simulator inside the courier's app. They show the rules and the server's
checks, not real temperatures. Say so on camera.

| # | Do this | Expect | Film |
|---|---|---|---|
| A1 | Run a normal handover (the Gate 3 steps) | Receiver sees PASS; server status ACCEPTED | Both screens at confirm time |
| A2 | Courier: Chaos tab, Inject Heat Spike, wait 10 minutes, deactivate | One alert, not one per reading; escalation notifications if nobody acknowledges; receiver sees FAIL and can't record PASS | The alert list, the notification, the receiver's verdict |
| A3 | Courier: Chaos tab, Logger Disconnect for over 3 minutes | A data-gap alert; receiver sees UNKNOWN, not PASS | The receiver's "gap in the readings" line |
| A4 | Courier: aeroplane mode for 10 minutes while recording, then back online | Readings keep being stored on the phone and reach the server afterwards, in order | The telemetry log moving from PENDING to SYNCED |
| A5 | Courier: confirm the handover, then close the app straight away | Receiver gets UNKNOWN after 3 minutes of silence: the courier's phone must keep recording until the receiver confirms | The receiver's verdict |
| A6 | Receiver: try to record PASS when the screen says FAIL or UNKNOWN | Not possible; FAIL and UNKNOWN need a reason | The verdict options |
| A7 | One phone: sign in as the courier, confirm, sign out, sign in as the receiver on the same phone | Refused: a phone stays registered to its first account | The refusal message |

### Attacks on the server (Part A, dev project only)

In the dev project's SQL editor, signed in as the dashboard owner, these show the rules hold even for direct
database access through the app's roles. Never run them on the old demo project.

| # | Try | Expect |
|---|---|---|
| A8 | As the courier, through the app's API: update or delete a reading | Refused: no client role may change readings |
| A9 | Upload a reading with an edited temperature but the old hash | Refused, "does not match its hash" (CC004) |
| A10 | Upload a reading dated before the previous one | Refused, "back-dated" (CC009) |
| A11 | Upload from an account that doesn't hold custody | Refused (CC002) |

Claude has these as automated tests (`supabase/tests/database/`); on camera they are easier to show through
`LiveSupabaseIntegrationTest` run against the dev project (`CLAUDE.md`, "Build and test").

## Part B: with a hardware logger (later)

Needs the owner's firmware (`firmware/WALKTHROUGH.md`) on a logger enrolled on the shipment, and the app reading it
over Bluetooth.

| # | Do this | Expect | Film |
|---|---|---|---|
| B1 | Cooler with ice packs and the logger taped inside, lid closed, 2 hours | PASS; the receiver's phone reads the logger directly and it matches the server's copy | The receiver reading the logger |
| B2 | Open the lid in a warm room for 20 minutes | Heat excursion; FAIL | Thermometer next to the logger, then the verdict |
| B3 | Put the logger in direct contact with a frozen ice pack for 20 minutes | Freeze excursion; FAIL on the freeze-sensitive profile even though it was cold, not warm | Same |
| B4 | Leave the logger out of Bluetooth range for an hour, then come back | The courier's phone back-fills the missed records in order | The telemetry log catching up |
| B5 | Delete the server's last readings, or edit one, through the dashboard as owner | The receiver's logger check reports the difference and fails the handover | The handover screen |
| B6 | Set the courier's phone clock an hour ahead | Readings flagged as dated after they arrived | The clock flag on the handover screen |

What none of this proves: that the logger was inside the cargo the whole time. That is a matter of packing and
sealing, and of the receiver's own vial monitor and shake test (`docs/THREAT_MODEL.md`).
