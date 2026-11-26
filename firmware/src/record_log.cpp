#include "cch/record_log.hpp"

#include <algorithm>
#include <stdexcept>
#include <utility>

#include "cch/hashing.hpp"

namespace cch {

RecordLog::RecordLog(std::string logger_id, std::size_t capacity)
    : logger_id_(std::move(logger_id)), ring_(capacity) {
    if (capacity == 0) throw std::invalid_argument("A record log needs room for at least one record.");
    before_oldest_ = genesis(logger_id_);
    newest_hash_ = before_oldest_;
}

Record RecordLog::append(std::int64_t logger_ts, std::int32_t temp_centi_c, std::uint16_t flags) {
    const Record record{newest_seq_ + 1, logger_ts, temp_centi_c, flags};
    newest_hash_ = chain(newest_hash_, record);
    newest_seq_ = record.seq;

    if (count_ < ring_.size()) {
        ring_[(start_ + count_) % ring_.size()] = record;
        ++count_;
    } else {
        // The oldest record goes; its hash becomes the one just before the new oldest.
        before_oldest_ = chain(before_oldest_, ring_[start_]);
        ring_[start_] = record;
        start_ = (start_ + 1) % ring_.size();
    }
    return record;
}

const std::string& RecordLog::logger_id() const { return logger_id_; }

std::size_t RecordLog::capacity() const { return ring_.size(); }

std::size_t RecordLog::size() const { return count_; }

std::int64_t RecordLog::oldest_seq() const { return count_ == 0 ? newest_seq_ + 1 : ring_[start_].seq; }

std::int64_t RecordLog::newest_seq() const { return newest_seq_; }

const Record& RecordLog::at(std::int64_t seq) const {
    const auto offset = static_cast<std::size_t>(seq - oldest_seq());
    return ring_[(start_ + offset) % ring_.size()];
}

std::vector<Record> RecordLog::read(std::int64_t from_seq, std::size_t max_records) const {
    std::vector<Record> out;
    for (std::int64_t seq = std::max(from_seq, oldest_seq()); seq <= newest_seq_ && out.size() < max_records; ++seq) {
        out.push_back(at(seq));
    }
    return out;
}

std::optional<Hash> RecordLog::hash_at(std::int64_t seq) const {
    if (seq < oldest_seq() - 1 || seq > newest_seq_) return std::nullopt;
    if (seq == newest_seq_) return newest_hash_;
    Hash hash = before_oldest_;
    for (std::int64_t s = oldest_seq(); s <= seq; ++s) hash = chain(hash, at(s));
    return hash;
}

}  // namespace cch
