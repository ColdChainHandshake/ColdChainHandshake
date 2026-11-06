// libFuzzer target: a phone can write anything to the history request characteristic. Parsing it and answering
// must never crash, read out of bounds, or send a page larger than the MTU allows.
// Build with the "fuzz" preset (clang on Linux or WSL only), then: ./build/fuzz/fuzz_history_request -max_total_time=60

#include <cstddef>
#include <cstdint>

#include "cch/history.hpp"

namespace {

// A throwaway key for fuzzing, not the test key: DeviceSigner refuses that one.
cch::PrivateKey fuzz_key() {
    cch::PrivateKey key{};
    key.back() = 7;
    return key;
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    static cch::RecordLog log = [] {
        cch::RecordLog filled("LOG-FUZZ", 64);
        for (int i = 0; i < 100; ++i) filled.append(1'700'000'000'000LL + i * 60'000LL, 500 + i, 0);
        return filled;
    }();
    static auto signer = cch::DeviceSigner::create(fuzz_key());
    if (!signer) __builtin_trap();

    // The first 10 bytes are the request as written; one more byte, if present, picks the MTU.
    auto request = cch::parse_history_request(cch::ByteView(data, size < 10 ? size : 10));
    if (!request) return 0;
    request->max_records = static_cast<std::uint16_t>(request->max_records % 256);  // caps the work per input
    const std::size_t mtu = size > 10 ? 23 + data[10] * 2 : cch::kMinAttMtu;

    for (const cch::Bytes& notification : cch::history_notifications(log, *request, mtu, *signer)) {
        if (notification.size() > mtu - 3) __builtin_trap();
    }
    return 0;
}
