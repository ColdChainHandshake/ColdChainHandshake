#include "cch/hashing.hpp"

#include "todo.hpp"

namespace cch {

Hash sha256(ByteView data) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("sha256, WALKTHROUGH.md step 1");
}

void append_lp(Bytes& out, std::string_view text) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("append_lp, WALKTHROUGH.md step 1");
}

Bytes genesis_payload(std::string_view logger_id) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("genesis_payload, WALKTHROUGH.md step 1");
}

Bytes chain_payload(const Hash& previous, const Record& record) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("chain_payload, WALKTHROUGH.md step 1");
}

Bytes batch_payload(std::string_view logger_id, std::int64_t first_seq, std::int64_t last_seq,
                    const Hash& start_hash, const Hash& end_hash) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("batch_payload, WALKTHROUGH.md step 1");
}

Bytes head_payload(std::string_view logger_id, std::int64_t head_seq, const Hash& head_hash, const Nonce& nonce) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("head_payload, WALKTHROUGH.md step 1");
}

Hash genesis(std::string_view logger_id) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("genesis, WALKTHROUGH.md step 1");
}

Hash chain(const Hash& previous, const Record& record) {
    // TODO(owner): step 1 of WALKTHROUGH.md.
    todo("chain, WALKTHROUGH.md step 1");
}

}  // namespace cch
