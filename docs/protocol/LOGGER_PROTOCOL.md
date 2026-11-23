# Logger protocol

How a temperature logger keeps its own history and proves it to the phone that reads it. The Kotlin
code in `core-domain/.../logger/`, the virtual logger, the C++ host library in `firmware/` and the
vectors in `logger_vectors.json` all follow this file. When they disagree, this file is right and the
vectors decide what it means.

This is a prototype. Nothing here has run on logger hardware yet (`HARDWARE = none`).

## What the logger proves and what it doesn't

A logger signs its own readings with a key that never leaves it. A phone can relay those readings but
can't change, drop, reorder or invent one without the signature check failing. That is only as good as
the logger: a logger kept cold while the product warms, or one whose key has been extracted, proves
nothing. The virtual logger keeps its key inside the courier's own app, so it proves nothing about
temperature. It exists to exercise this protocol end to end.

## Identity

- A logger has a text ID, printed on its case, and an ECDSA P-256 key pair it generates itself.
- The public key travels as an uncompressed point (65 bytes, `04 || X || Y`) over Bluetooth, and as
  X.509 SubjectPublicKeyInfo in Base64 to the app and the server.
- The sender records the logger's ID and public key when creating a shipment, so a receiver checks
  against a key the courier didn't supply.

## Encoding

All integers are big-endian. `lp(s)` is a 4-byte length followed by the UTF-8 bytes of `s`, as in
`CANONICAL_ENCODING.md`. Hashes are SHA-256, 32 raw bytes, written as lower-case hex in text.

### Record

One reading, 22 bytes:

```text
i64(seq)            starts at 1 and goes up by exactly 1, for the logger's whole life, across power cycles
i64(logger_ts)      milliseconds since the Unix epoch, from the logger's own clock
i32(temp_centi_c)   hundredths of a degree Celsius
u16(flags)
```

Flags:

| Bit | Meaning |
|---|---|
| 0 | The sensor reported a fault. `temp_centi_c` is not a reading |
| 1 | The clock was set or adjusted since the previous record |
| 2 | The battery is low |
| 3 | Power was lost since the previous record |

Other bits are 0. A verifier treats a set unknown bit as a fault.

### Running hash

The logger keeps one running hash over everything it has ever recorded:

```text
h0 = SHA-256( u8(1) lp("cch-logger-genesis") lp(logger_id) )
hn = SHA-256( u8(1) raw(h(n-1)) record_n )
```

It only has to store the latest hash, which suits a microcontroller. Any change to any earlier record
changes every later hash.

### Batch

The logger signs each range of records it hands over:

```text
payload = u8(1) lp("cch-logger-batch") lp(logger_id) i64(first_seq) i64(last_seq) raw(h(first_seq - 1)) raw(h(last_seq))
```

A verifier recomputes the running hash from `h(first_seq - 1)` over the records and must reach
`h(last_seq)`, and the signature must verify. Consecutive batches must join: one batch's `h(last_seq)`
is the next one's starting hash. A missing, edited, repeated or reordered record breaks that.

### Head

The phone proves the logger is present now, and isn't a replay of an earlier read, by sending a fresh
random nonce of 16 bytes. The logger answers with its newest record's hash, signed together with the
nonce:

```text
payload = u8(1) lp("cch-logger-head") lp(logger_id) i64(head_seq) raw(h(head_seq)) raw(nonce)
```

The reader checks that the head joins the history it was given and that the nonce is its own.

### Signatures

ECDSA over P-256 with SHA-256 of the payload. On the wire and in the vectors a signature is raw
`r || s`, 64 bytes, each half big-endian and padded to 32 bytes. Java and Android need DER, so the app
converts. Verifiers accept any valid `s`, high or low. The vectors were signed with RFC 6979
deterministic nonces so they can be regenerated exactly, but a logger may use any secure nonce.

Why P-256 and not Ed25519: Android has verified P-256 since long before API 26, the app's lowest level,
while Ed25519 only arrived in API 33. mbedTLS, the usual microcontroller library, supports P-256. The
app already signs uploads with it.

## Bluetooth Low Energy

One primary service. UUIDs share a random base, `6f1c0000-8e4a-4c43-9b3a-2d7c5e1f0a00`, with the
third and fourth bytes changing:

| UUID | Characteristic | Properties | Content |
|---|---|---|---|
| `6f1c0001-…` | Service | | |
| `6f1c0002-…` | Device information | read | `lp(logger_id) u8(protocol=1) lp(firmware_version)` |
| `6f1c0003-…` | Public key | read | 65-byte uncompressed point |
| `6f1c0004-…` | History request | write | `i64(from_seq) u16(max_records)` |
| `6f1c0005-…` | History data | notify | pages, then one batch header |
| `6f1c0006-…` | Challenge | write | 16-byte nonce |
| `6f1c0007-…` | Challenge response | notify | `i64(head_seq) raw(h(head_seq)) raw(signature)` |
| `6f1c0008-…` | Status | read | `i64(oldest_seq) i64(newest_seq) u16(flags)` |

### Reading history

1. The phone reads the status. If `oldest_seq` is past the phone's last record plus 1, the logger has
   overwritten readings the phone never saw. That gap stays visible: the verifier reports it.
2. The phone writes a history request from its last record plus 1.
3. The logger sends pages. Each notification holds `u8(page_kind=1) u16(count)` followed by `count` whole
   records, as many as fit in the connection's ATT MTU minus 3. It ends with one batch header notification:
   `u8(page_kind=2) i64(first_seq) i64(last_seq) raw(h(first_seq - 1)) raw(h(last_seq)) raw(signature)`.
4. The phone verifies before storing anything. A failed batch is kept apart as evidence and shown.

A logger with nothing at or after `from_seq` sends no notifications at all, so the phone compares `newest_seq`
from step 1 with its request and doesn't ask when there is nothing new. If the pages and the header disagree, as
when a notification is lost, the phone treats the read as failed and asks again; it never stores the part that
arrived as if it were the whole answer.

Draft, waiting on the owner's sign-off (`HANDOFF.md` Q4, item 11): the 145-byte header must fit one notification,
so a logger sends no history below an ATT MTU of 148, and the 104-byte challenge response needs at least 107. The
phone asks for 517 (Android's largest) before either, and gives up with "MTU too small" if it gets less than 148.

The phone asks again from where it got to after a dropped connection, so an outage only delays the
back-fill. A logger should keep at least five days of minutely records. The ring buffer of the reference
design holds 8,192, which is 5.7 days.

### At handover

The receiver's phone reads the logger directly, checks every batch and the head against a fresh nonce,
then compares the logger's records with the server's copy of the shipment. Any record the server lacks,
any difference, and any reading the logger never made is reported.

## Mapping to a shipment

A logger's sequence runs across shipments. When custody starts, the server records the logger's newest
sequence number, so shipment reading `n` is logger record `start_seq + n`. The shipment's own chain,
version 2 in `CANONICAL_ENCODING.md`, is built over the same readings, so the server can keep checking
order and continuity without verifying ECDSA, which Postgres can't do.

## Test vectors

`logger_vectors.json` comes from `tools/protocol/gen_logger_vectors.py`, a separate implementation in plain
Python. It holds a test key, records, running hashes, batch and head payloads, and RFC 6979 signatures in
raw and DER form. The private key in it is for tests only.

Its `gatt` section holds the bytes on each characteristic: device information, status, the challenge response,
and five history requests with every notification the logger sends for them (two pages then a header at MTU 148,
one page at 185, a later start, nothing that new, and an MTU too small). The phone's Bluetooth code
(`core-domain/.../logger/ble/`) and the firmware's `Step4Vectors` test both check against these bytes.
