#include "cch/history.hpp"

#include <algorithm>

#include "cch/hashing.hpp"

namespace cch {
namespace {

constexpr std::size_t kPageHeaderSize = 3;  ///< `u8(1) u16(count)`
constexpr std::size_t kNotificationOverhead = 3;  ///< an ATT notification carries att_mtu - 3 bytes

void append_u64(Bytes& out, std::uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8) out.push_back(static_cast<std::uint8_t>(value >> shift));
}

void append_u16(Bytes& out, std::uint16_t value) {
    out.push_back(static_cast<std::uint8_t>(value >> 8));
    out.push_back(static_cast<std::uint8_t>(value & 0xFFu));
}

void append_raw(Bytes& out, ByteView bytes) { out.insert(out.end(), bytes.begin(), bytes.end()); }

Bytes header_notification(const BatchHeader& header) {
    Bytes out{kPageBatchHeader};
    append_u64(out, static_cast<std::uint64_t>(header.first_seq));
    append_u64(out, static_cast<std::uint64_t>(header.last_seq));
    append_raw(out, header.start_hash);
    append_raw(out, header.end_hash);
    append_raw(out, header.signature);
    return out;
}

}  // namespace

std::optional<HistoryRequest> parse_history_request(ByteView bytes) {
    if (bytes.size() != 10) return std::nullopt;
    std::uint64_t from = 0;
    for (std::size_t i = 0; i < 8; ++i) from = (from << 8) | bytes[i];
    HistoryRequest request;
    request.from_seq = static_cast<std::int64_t>(from);
    request.max_records = static_cast<std::uint16_t>((bytes[8] << 8) | bytes[9]);
    return request;
}

std::optional<BatchHeader> sign_batch(const RecordLog& log, std::int64_t first_seq, std::int64_t last_seq,
                                      Signer& signer) {
    if (first_seq > last_seq || first_seq < log.oldest_seq() || last_seq > log.newest_seq()) return std::nullopt;
    const std::optional<Hash> start = log.hash_at(first_seq - 1);
    const std::optional<Hash> end = log.hash_at(last_seq);
    if (!start || !end) return std::nullopt;

    BatchHeader header;
    header.first_seq = first_seq;
    header.last_seq = last_seq;
    header.start_hash = *start;
    header.end_hash = *end;
    header.signature = signer.sign(batch_payload(log.logger_id(), first_seq, last_seq, *start, *end));
    return header;
}

std::vector<Bytes> history_notifications(const RecordLog& log, const HistoryRequest& request, std::size_t att_mtu,
                                         Signer& signer) {
    // Below the minimum MTU the 145-byte header can't fit one notification, and it is never split.
    if (att_mtu < kMinAttMtu || request.max_records == 0) return {};
    const std::vector<Record> records = log.read(request.from_seq, request.max_records);
    if (records.empty()) return {};

    const std::size_t per_page = (att_mtu - kNotificationOverhead - kPageHeaderSize) / kRecordSize;
    std::vector<Bytes> out;
    for (std::size_t first = 0; first < records.size(); first += per_page) {
        const std::size_t count = std::min(per_page, records.size() - first);
        Bytes page{kPageRecords};
        append_u16(page, static_cast<std::uint16_t>(count));
        for (std::size_t i = first; i < first + count; ++i) append_raw(page, encode(records[i]));
        out.push_back(std::move(page));
    }

    // The header covers exactly the records sent, so pages that don't add up to it show a lost notification.
    const std::optional<BatchHeader> header = sign_batch(log, records.front().seq, records.back().seq, signer);
    if (!header) return {};
    out.push_back(header_notification(*header));
    return out;
}

Bytes challenge_response(const RecordLog& log, const Nonce& nonce, Signer& signer) {
    const std::int64_t head_seq = log.newest_seq();
    // The newest hash is always known; for a new logger, head 0 is the genesis hash.
    const Hash head_hash = *log.hash_at(head_seq);
    Bytes out;
    append_u64(out, static_cast<std::uint64_t>(head_seq));
    append_raw(out, head_hash);
    append_raw(out, signer.sign(head_payload(log.logger_id(), head_seq, head_hash, nonce)));
    return out;
}

Bytes status_value(const RecordLog& log, std::uint16_t flags) {
    Bytes out;
    append_u64(out, static_cast<std::uint64_t>(log.oldest_seq()));
    append_u64(out, static_cast<std::uint64_t>(log.newest_seq()));
    append_u16(out, flags);
    return out;
}

Bytes device_information(std::string_view logger_id, std::string_view firmware_version) {
    Bytes out;
    append_lp(out, logger_id);
    out.push_back(kProtocolVersion);
    append_lp(out, firmware_version);
    return out;
}

}  // namespace cch
