# Canonical encoding, chain version 2

Every implementation of the chain must produce the same bytes. That covers the app (Kotlin), the server (SQL) and
the logger firmware (C++). `chain_vectors.json` holds the shared test vectors; change nothing here without changing
them, and a change means a new chain version.

## Building blocks

| Notation | Bytes |
|---|---|
| `u8(x)` | One byte |
| `i32(x)` | Four bytes, big-endian, two's complement |
| `i64(x)` | Eight bytes, big-endian, two's complement |
| `lp(s)` | `i32(n)` followed by the `n` bytes of `s` in UTF-8. `n` counts bytes, not characters |
| `raw(h)` | The 32 bytes a 64-character lowercase hex hash stands for |

Length prefixes are the point. Version 1 joined fields with `|`, so different field values could produce the same
input. Here, `("AB", "C")` and `("A", "BC")` can't.

Hashes are written as lowercase hex wherever they are stored or sent.

Temperatures are whole hundredths of a degree Celsius, so a reading is never rounded a second time when verified.
Times are milliseconds since the Unix epoch.

## Genesis

The genesis hash commits a chain to one shipment, so readings can't be replayed into another.

```text
genesis = SHA-256(
    u8(2)
    lp(shipment_id)
    lp(sender_id)              the user's UUID, lowercase with hyphens
    lp(product_profile_id)
    lp(logger_id)
    i64(created_at_ms)
)
```

The server computes it when the shipment is created. Those columns can't change afterwards.

## Reading

```text
hash = SHA-256(
    u8(2)
    lp(shipment_id)
    lp(logger_id)
    i64(seq)                   starts at 1 and has no gaps
    i64(logger_ts)
    i32(temp_centi_c)
    raw(prev_hash)             the genesis hash for seq 1, otherwise the previous reading's hash
)
```

## Batch signature

A device signs each upload with its own key, using ECDSA over P-256 with SHA-256. On a phone the key lives in the
Android Keystore, and it is sent as X.509 SubjectPublicKeyInfo. Signatures are DER-encoded. Both travel as Base64.

```text
payload =
    u8(2)
    lp(shipment_id)
    i64(first_seq)
    i64(last_seq)
    raw(head_hash)             the hash of the reading at last_seq
```

The signature shows which phone uploaded the batch, not that the readings are true.

## What the chain does and doesn't prove

The hash alone proves nothing about who wrote a reading, because anyone holding the data can recompute it. Its value
comes from the server holding the head and count: a history shorter than the server's, or different from it, is
detected. Until a hardware logger signs its own history, the readings are only as true as the device that made them.
