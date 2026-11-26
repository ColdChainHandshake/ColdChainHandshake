// What the logger sends over Bluetooth: history pages, signed batch headers, the challenge response, status and
// device information. LOGGER_PROTOCOL.md, "Bluetooth Low Energy".
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

#include "cch/bytes.hpp"
#include "cch/record_log.hpp"
#include "cch/signing.hpp"

namespace cch {

inline constexpr std::uint8_t kProtocolVersion = 1;
inline constexpr std::uint8_t kPageRecords = 1;      ///< `u8(1) u16(count)` then count records
inline constexpr std::uint8_t kPageBatchHeader = 2;  ///< `u8(2) i64 i64 raw(32) raw(32) raw(64)`, 145 bytes

/// LOGGER_PROTOCOL.md, minimum MTU: the batch header is 145 bytes and a notification carries at most
/// ATT MTU - 3, so below an MTU of 148 the logger sends nothing and the phone must ask for a larger MTU.
inline constexpr std::size_t kMinAttMtu = 148;

struct HistoryRequest {
    std::int64_t from_seq = 0;
    std::uint16_t max_records = 0;
};

struct BatchHeader {
    std::int64_t first_seq = 0;
    std::int64_t last_seq = 0;
    Hash start_hash{};  ///< h(first_seq - 1)
    Hash end_hash{};    ///< h(last_seq)
    Signature signature{};
};

/// The history request characteristic: `i64(from_seq) u16(max_records)`, exactly 10 bytes, or std::nullopt.
std::optional<HistoryRequest> parse_history_request(ByteView bytes);

/// Signs records first_seq..last_seq. std::nullopt when the log doesn't hold all of them, or first > last.
std::optional<BatchHeader> sign_batch(const RecordLog& log, std::int64_t first_seq, std::int64_t last_seq,
                                      Signer& signer);

/// Every notification for one request, in order: record pages, each as full as the MTU allows and at most
/// att_mtu - 3 bytes, then one batch header covering exactly the records sent. Starts from the oldest kept record
/// if from_seq is older, so an overwrite shows up as a gap at the phone. Empty when nothing is that new,
/// max_records is 0, or att_mtu is below kMinAttMtu.
std::vector<Bytes> history_notifications(const RecordLog& log, const HistoryRequest& request, std::size_t att_mtu,
                                         Signer& signer);

/// The challenge response: `i64(head_seq) raw(h(head_seq)) raw(signature over head_payload)`, 104 bytes.
/// A new logger answers with head_seq 0 and the genesis hash.
Bytes challenge_response(const RecordLog& log, const Nonce& nonce, Signer& signer);

/// The status characteristic: `i64(oldest_seq) i64(newest_seq) u16(flags)`.
Bytes status_value(const RecordLog& log, std::uint16_t flags);

/// The device information characteristic: `lp(logger_id) u8(protocol=1) lp(firmware_version)`.
Bytes device_information(std::string_view logger_id, std::string_view firmware_version);

}  // namespace cch
