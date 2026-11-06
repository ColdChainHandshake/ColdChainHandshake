#include "cch/history.hpp"

#include "todo.hpp"

namespace cch {

std::optional<HistoryRequest> parse_history_request(ByteView bytes) {
    // TODO(owner): step 4 of WALKTHROUGH.md.
    todo("parse_history_request, WALKTHROUGH.md step 4");
}

std::optional<BatchHeader> sign_batch(const RecordLog& log, std::int64_t first_seq, std::int64_t last_seq,
                                      Signer& signer) {
    // TODO(owner): step 4 of WALKTHROUGH.md.
    todo("sign_batch, WALKTHROUGH.md step 4");
}

std::vector<Bytes> history_notifications(const RecordLog& log, const HistoryRequest& request, std::size_t att_mtu,
                                         Signer& signer) {
    // TODO(owner): step 4 of WALKTHROUGH.md.
    todo("history_notifications, WALKTHROUGH.md step 4");
}

Bytes challenge_response(const RecordLog& log, const Nonce& nonce, Signer& signer) {
    // TODO(owner): step 4 of WALKTHROUGH.md.
    todo("challenge_response, WALKTHROUGH.md step 4");
}

Bytes status_value(const RecordLog& log, std::uint16_t flags) {
    // TODO(owner): step 4 of WALKTHROUGH.md.
    todo("status_value, WALKTHROUGH.md step 4");
}

Bytes device_information(std::string_view logger_id, std::string_view firmware_version) {
    // TODO(owner): step 4 of WALKTHROUGH.md.
    todo("device_information, WALKTHROUGH.md step 4");
}

}  // namespace cch
