// libFuzzer target: decoding any bytes must not crash, and whatever decodes must encode back to the same bytes.
// Build with the "fuzz" preset (clang on Linux or WSL only), then: ./build/fuzz/fuzz_record_decode -max_total_time=60

#include <algorithm>
#include <cstddef>
#include <cstdint>

#include "cch/record.hpp"

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    auto record = cch::decode(cch::ByteView(data, size));
    if (record) {
        const cch::RecordBytes again = cch::encode(*record);
        if (size != again.size() || !std::equal(again.begin(), again.end(), data)) __builtin_trap();
    }
    return 0;
}
