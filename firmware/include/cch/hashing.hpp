// SHA-256, the payloads the logger hashes and signs, and its running hash.
// LOGGER_PROTOCOL.md, "Running hash", "Batch" and "Head".
#pragma once

#include <cstdint>
#include <string_view>

#include "cch/bytes.hpp"
#include "cch/record.hpp"

namespace cch {

/// SHA-256 (FIPS 180-4). Write your own, or wrap mbedtls_sha256() on the board.
Hash sha256(ByteView data);

/// Appends `lp(text)`: a 4-byte big-endian length, then the UTF-8 bytes.
void append_lp(Bytes& out, std::string_view text);

/// `u8(1) lp("cch-logger-genesis") lp(logger_id)`
Bytes genesis_payload(std::string_view logger_id);

/// `u8(1) raw(previous) record`
Bytes chain_payload(const Hash& previous, const Record& record);

/// `u8(1) lp("cch-logger-batch") lp(logger_id) i64(first_seq) i64(last_seq) raw(start_hash) raw(end_hash)`
Bytes batch_payload(std::string_view logger_id, std::int64_t first_seq, std::int64_t last_seq,
                    const Hash& start_hash, const Hash& end_hash);

/// `u8(1) lp("cch-logger-head") lp(logger_id) i64(head_seq) raw(head_hash) raw(nonce)`
Bytes head_payload(std::string_view logger_id, std::int64_t head_seq, const Hash& head_hash, const Nonce& nonce);

/// `h0 = SHA-256(genesis_payload(logger_id))`
Hash genesis(std::string_view logger_id);

/// `hn = SHA-256(chain_payload(h(n-1), record_n))`
Hash chain(const Hash& previous, const Record& record);

}  // namespace cch
