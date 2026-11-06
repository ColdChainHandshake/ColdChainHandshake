#include "cch/record_log.hpp"

#include "todo.hpp"

namespace cch {

RecordLog::RecordLog(std::string logger_id, std::size_t capacity) {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog, WALKTHROUGH.md step 2");
}

Record RecordLog::append(std::int64_t logger_ts, std::int32_t temp_centi_c, std::uint16_t flags) {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::append, WALKTHROUGH.md step 2");
}

const std::string& RecordLog::logger_id() const {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::logger_id, WALKTHROUGH.md step 2");
}

std::size_t RecordLog::capacity() const {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::capacity, WALKTHROUGH.md step 2");
}

std::size_t RecordLog::size() const {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::size, WALKTHROUGH.md step 2");
}

std::int64_t RecordLog::oldest_seq() const {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::oldest_seq, WALKTHROUGH.md step 2");
}

std::int64_t RecordLog::newest_seq() const {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::newest_seq, WALKTHROUGH.md step 2");
}

std::vector<Record> RecordLog::read(std::int64_t from_seq, std::size_t max_records) const {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::read, WALKTHROUGH.md step 2");
}

std::optional<Hash> RecordLog::hash_at(std::int64_t seq) const {
    // TODO(owner): step 2 of WALKTHROUGH.md.
    todo("RecordLog::hash_at, WALKTHROUGH.md step 2");
}

}  // namespace cch
