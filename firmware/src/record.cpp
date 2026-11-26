#include "cch/record.hpp"

namespace cch {
namespace {

// Signed fields go through their unsigned twins, so a negative value keeps its two's-complement bits.
void put_u64(std::uint8_t* out, std::uint64_t value) {
    for (int i = 7; i >= 0; --i) {
        out[i] = static_cast<std::uint8_t>(value & 0xFFu);
        value >>= 8;
    }
}

void put_u32(std::uint8_t* out, std::uint32_t value) {
    for (int i = 3; i >= 0; --i) {
        out[i] = static_cast<std::uint8_t>(value & 0xFFu);
        value >>= 8;
    }
}

std::uint64_t get_u64(const std::uint8_t* in) {
    std::uint64_t value = 0;
    for (int i = 0; i < 8; ++i) value = (value << 8) | in[i];
    return value;
}

std::uint32_t get_u32(const std::uint8_t* in) {
    std::uint32_t value = 0;
    for (int i = 0; i < 4; ++i) value = (value << 8) | in[i];
    return value;
}

}  // namespace

RecordBytes encode(const Record& record) {
    RecordBytes out{};
    put_u64(out.data(), static_cast<std::uint64_t>(record.seq));
    put_u64(out.data() + 8, static_cast<std::uint64_t>(record.logger_ts));
    put_u32(out.data() + 16, static_cast<std::uint32_t>(record.temp_centi_c));
    out[20] = static_cast<std::uint8_t>(record.flags >> 8);
    out[21] = static_cast<std::uint8_t>(record.flags & 0xFFu);
    return out;
}

std::optional<Record> decode(ByteView bytes) {
    if (bytes.size() != kRecordSize) return std::nullopt;
    const std::uint8_t* in = bytes.data();
    Record record;
    record.seq = static_cast<std::int64_t>(get_u64(in));
    record.logger_ts = static_cast<std::int64_t>(get_u64(in + 8));
    record.temp_centi_c = static_cast<std::int32_t>(get_u32(in + 16));
    record.flags = static_cast<std::uint16_t>((in[20] << 8) | in[21]);
    return record;
}

}  // namespace cch
