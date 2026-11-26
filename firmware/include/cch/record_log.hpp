// The logger's own history: a ring buffer of records that numbers them for life and keeps its running hash.
// LOGGER_PROTOCOL.md, "Running hash" and "Reading history".
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "cch/bytes.hpp"
#include "cch/record.hpp"

namespace cch {

/// The reference design's capacity: 5.7 days of one reading a minute.
inline constexpr std::size_t kDefaultCapacity = 8192;

class RecordLog {
public:
    RecordLog(std::string logger_id, std::size_t capacity = kDefaultCapacity);

    /// Stores the next reading and returns it. Its seq is newest_seq() + 1, so the first is 1. When the log is
    /// full the oldest record is overwritten, and the sequence keeps counting.
    Record append(std::int64_t logger_ts, std::int32_t temp_centi_c, std::uint16_t flags);

    const std::string& logger_id() const;
    std::size_t capacity() const;
    std::size_t size() const;

    /// The oldest record still kept. With nothing kept, newest_seq() + 1 (so 1 for a new logger).
    std::int64_t oldest_seq() const;

    /// The newest record, or 0 for a new logger.
    std::int64_t newest_seq() const;

    /// Up to max_records records in order, from from_seq or from the oldest kept if that is later. Empty when
    /// nothing is that new or max_records is 0.
    std::vector<Record> read(std::int64_t from_seq, std::size_t max_records) const;

    /// The running hash after record `seq`. Known for every kept record and for the one just before the oldest kept
    /// (a batch from the oldest needs it as its start), so `hash_at(0)` is the genesis hash until record 1 is
    /// overwritten. std::nullopt otherwise.
    std::optional<Hash> hash_at(std::int64_t seq) const;

private:
    // Records in a ring, and two hashes rather than one per record: the hash just before the oldest kept record,
    // from which any kept record's hash is recomputed, and the newest hash, which every append needs. 8,192
    // records take 176 KB; a hash each would add 256 KB, more than many boards have free (WALKTHROUGH.md, step 2).
    const Record& at(std::int64_t seq) const;

    std::string logger_id_;
    std::vector<Record> ring_;
    std::size_t start_ = 0;  ///< index of the oldest kept record
    std::size_t count_ = 0;
    std::int64_t newest_seq_ = 0;
    Hash before_oldest_{};
    Hash newest_hash_{};
};

}  // namespace cch
