# Firmware walkthrough

The logger's code is yours to write (`FIRMWARE_OWNER = me`). Claude wrote only the frame around it: the CMake
project, the header interfaces in `include/cch/`, a harness that loads the shared test vectors, the failing tests,
and this file. Every function in `src/` is a `TODO(owner)` stub that throws, so each test fails with a message naming
its step until you write that function.

The rules come from `docs/protocol/LOGGER_PROTOCOL.md`. Where the protocol and a test disagree, the protocol is right
and the vectors in `docs/protocol/logger_vectors.json` decide what it means. The tests were written from the protocol
and the vectors and have never run against a working implementation, so a test can be wrong. If you think one is,
say so in `docs/agent/HANDOFF.md` and Claude will check it against the Kotlin and Python versions.

The protocol was signed off on 26 Nov 2026, including its minimum MTU of 148.

## Build and run on Windows

You need CMake 3.25 or later, Ninja and a C++20 compiler. This PC has CMake 4.4, Ninja 1.13 and MSYS2's g++ 13.2
(`C:\msys64\ucrt64\bin`). From Git Bash:

```bash
cd /d/ColdChainHandshake/firmware
cmake --preset host-tests -DCMAKE_CXX_COMPILER=g++     # once; downloads GoogleTest and nlohmann/json
cmake --build --preset host-tests
ctest --preset host-tests                              # all 38 tests
./build/host-tests/cch_logger_tests.exe --gtest_filter='Step1*'   # one step at a time
```

Today 37 of the 38 fail. The one that passes, `Step3DeviceKey.TheBuiltInTestKeyIsTheVectorKey`, only checks that the
header holds the right key.

## Sanitizers and fuzzing (clang on Linux or WSL only)

MinGW has no AddressSanitizer, and libFuzzer needs clang, so these presets are hidden on Windows. In WSL or Linux,
with clang installed:

```bash
cmake --preset asan-ubsan && cmake --build --preset asan-ubsan && ctest --preset asan-ubsan
cmake --preset fuzz && cmake --build --preset fuzz
./build/fuzz/fuzz_record_decode -max_total_time=60
./build/fuzz/fuzz_history_request -max_total_time=60
```

Run them once all four steps are green. The fuzz targets call your code, so before then they stop at the first
`TODO(owner)`.

## The test key

`docs/protocol/logger_vectors.json` publishes a private key so that every implementation can check its signatures
exactly. Because it is published, anyone can sign as it. So:

- The tests sign with it through `ecdsa_sign()` directly (`VectorSigner` in `tests/vectors.hpp`).
- `DeviceSigner`, the only signer a logger uses, must refuse it and its mirror. Step 3 tests that.
- Never put it in code that runs on a board. The app refuses a logger holding it, in every build.

The mirror is the point with the same X and the other Y. Its private key is `n - d`, which anyone holding `d` can
work out, so comparing only X catches both.

## Step 1: encode and decode frames

Files: `src/record.cpp`, `src/hashing.cpp`. Turns green: every `Step1*` test (10).

1. `encode` and `decode` a record: `i64(seq) i64(logger_ts) i32(temp_centi_c) u16(flags)`, big-endian, 22 bytes.
   `decode` refuses any other length and keeps unknown flag bits. Watch the signed fields: shift through
   `std::uint64_t` and `std::uint32_t`, not the signed types, or a negative temperature comes back wrong.
2. `append_lp`: a 4-byte big-endian length, then the bytes.
3. `sha256`: follow FIPS 180-4 if you want to learn it, or wrap `mbedtls_sha256()`. The three known answers include
   a 56-byte message, which is the one that tests padding into a second block.
4. The four payloads, then `genesis` and `chain`, which are SHA-256 of two of them. Compare each with
   `LOGGER_PROTOCOL.md` byte by byte. The version byte `u8(1)` comes first in every payload.

## Step 2: the ring buffer of readings

File: `src/record_log.cpp`, plus the private members in `include/cch/record_log.hpp`. Turns green: every `Step2*`
test (7).

- The sequence number starts at 1 and goes up by exactly 1 for the logger's whole life. When the buffer is full the
  oldest record goes, and the sequence keeps counting.
- `read` starts from the oldest kept record when asked for one already overwritten. That is how the phone sees a gap
  instead of a silent hole.
- `hash_at` must know the hash of every kept record and of the one just before the oldest, because a batch that starts
  at the oldest record signs that hash as its start.

The design choice is yours. Storing a hash per record is simplest, but 8,192 records take 176 KB, and a hash for each
adds 256 KB, which is more than many boards have free. Keeping only the hash before the oldest record and recomputing
forward when signing costs about 8,000 SHA-256 calls of 55 bytes at most per batch. On the board the records and the
sequence number must also survive a power cut; the host tests don't cover that, so write it down when you get there.

## Step 3: signing

File: `src/signing.cpp`, plus the private members of `DeviceSigner`. Turns green: every `Step3*` test (11).

- `ecdsa_sign` must reproduce the vectors exactly, so it needs RFC 6979 deterministic nonces and must not change `s`
  to its low form. mbedTLS's `mbedtls_ecdsa_sign_det_ext()` does both. Writing your own big-number code is a long
  but good exercise; if you do, keep it out of the board build unless it is constant-time.
- `ecdsa_verify` accepts high and low `s`.
- `public_key_of` refuses 0 and anything not below the group order n.
- `check_device_key` returns `kInvalid` for anything that isn't an uncompressed point on the curve, and `kTestKey`
  when X equals `kTestKeyX`.
- `DeviceSigner::create` refuses an invalid key and the test key (through `check_device_key` on its public key).

To use mbedTLS on the host, add it to `CMakeLists.txt` with `FetchContent`, pinned to a release with its SHA-256, as
GoogleTest is. Choose a current long-term-support release and record the version in `docs/agent/DECISIONS.md`.

## Step 4: history export

File: `src/history.cpp`. Turns green: every `Step4*` test (10).

- `parse_history_request` takes exactly 10 bytes.
- `sign_batch` refuses a range the log doesn't fully hold.
- `history_notifications` sends pages of `u8(1) u16(count)` and as many whole records as fit in `att_mtu - 3`
  bytes, then one batch header of 145 bytes covering exactly the records sent. Below an MTU of 148 the header can't
  fit, so it sends nothing (the protocol's minimum MTU rule).
- `challenge_response`, `status_value` and `device_information` are short layouts from the protocol's Bluetooth
  table. A new logger answers a challenge with sequence 0 and the genesis hash.

## After step 4

- Run the sanitizer and fuzz presets on Linux or WSL.
- The board project (PlatformIO) waits until a board is chosen (`HARDWARE` in `docs/agent/MASTER_PROMPT.md`).
- Gate 4 needs the virtual logger and this library to pass the same protocol tests. The vectors are the shared part.
