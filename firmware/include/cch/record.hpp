// One reading as the logger stores and sends it. LOGGER_PROTOCOL.md, "Record".
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>

#include "cch/bytes.hpp"

namespace cch {

inline constexpr std::size_t kRecordSize = 22;

namespace flags {
inline constexpr std::uint16_t kSensorFault = 1u << 0;    ///< temp_centi_c is not a reading
inline constexpr std::uint16_t kClockAdjusted = 1u << 1;  ///< the clock was set since the previous record
inline constexpr std::uint16_t kBatteryLow = 1u << 2;
inline constexpr std::uint16_t kPowerLost = 1u << 3;      ///< power was lost since the previous record
inline constexpr std::uint16_t kKnown = 0x000F;           ///< every other bit is 0 when the logger writes it
}  // namespace flags

struct Record {
    std::int64_t seq = 0;        ///< starts at 1 and goes up by exactly 1 for the logger's whole life
    std::int64_t logger_ts = 0;  ///< milliseconds since the Unix epoch, from the logger's own clock
    std::int32_t temp_centi_c = 0;
    std::uint16_t flags = 0;

    bool operator==(const Record&) const = default;
};

using RecordBytes = std::array<std::uint8_t, kRecordSize>;

/// `i64(seq) i64(logger_ts) i32(temp_centi_c) u16(flags)`, big-endian, 22 bytes.
RecordBytes encode(const Record& record);

/// The reverse of encode. Exactly kRecordSize bytes, or std::nullopt. Keeps unknown flag bits as they are:
/// deciding what they mean is the verifier's job, not the decoder's.
std::optional<Record> decode(ByteView bytes);

}  // namespace cch
