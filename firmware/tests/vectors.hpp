// Loads docs/protocol/logger_vectors.json, the same vectors the Kotlin tests use. They come from
// tools/protocol/gen_logger_vectors.py, a separate implementation in plain Python.
//
// The private key in them is published, so it is for these tests only. A DeviceSigner refuses it.
#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "cch/bytes.hpp"
#include "cch/history.hpp"
#include "cch/record.hpp"
#include "cch/record_log.hpp"
#include "cch/signing.hpp"

namespace cch::test {

struct VectorRecord {
    Record record;
    Bytes bytes;
    Hash running_hash{};
};

struct VectorBatch {
    std::int64_t first_seq = 0;
    std::int64_t last_seq = 0;
    Hash start_hash{};
    Hash end_hash{};
    Bytes payload;
    Signature signature{};
};

struct VectorHead {
    Nonce nonce{};
    std::int64_t head_seq = 0;
    Hash head_hash{};
    Bytes payload;
    Signature signature{};
};

struct Vectors {
    std::string logger_id;
    PrivateKey private_key{};
    PublicKey public_key{};
    std::string public_key_spki_base64;
    Hash genesis_hash{};
    std::vector<VectorRecord> records;
    std::vector<VectorBatch> batches;
    VectorHead head;
};

/// Loaded once, from the path CMake passes as CCH_VECTORS_PATH. Throws if the file is missing or malformed.
const Vectors& vectors();

Bytes from_hex(std::string_view hex);
std::string to_hex(ByteView bytes);

template <std::size_t N>
std::array<std::uint8_t, N> fixed_from_hex(std::string_view hex) {
    Bytes bytes = from_hex(hex);
    std::array<std::uint8_t, N> out{};
    if (bytes.size() != N) throw std::runtime_error("expected " + std::to_string(N) + " bytes: " + std::string(hex));
    std::copy(bytes.begin(), bytes.end(), out.begin());
    return out;
}

template <std::size_t N>
std::string to_hex(const std::array<std::uint8_t, N>& bytes) {
    return to_hex(ByteView(bytes.data(), bytes.size()));
}

/// `a - b` for 256-bit big-endian numbers with a >= b. Used to build the mirrored test key.
std::array<std::uint8_t, 32> subtract(const std::array<std::uint8_t, 32>& a, const std::array<std::uint8_t, 32>& b);

/// P-256's field prime p and group order n, big-endian.
extern const std::array<std::uint8_t, 32> kP256Prime;
extern const std::array<std::uint8_t, 32> kP256Order;

/// The vector key's mirror: same X, Y replaced by p - Y. Its private key is n - d.
PublicKey mirrored_test_public_key();
PrivateKey mirrored_test_private_key();

/// A log holding the vector records, in order.
RecordLog vector_log(std::size_t capacity = 16);

/// Signs with the vector key through the plain ecdsa_sign function. Test code only: the device can't do this,
/// because DeviceSigner refuses the key.
class VectorSigner final : public Signer {
public:
    PublicKey public_key() const override { return vectors().public_key; }
    Signature sign(ByteView payload) override { return ecdsa_sign(vectors().private_key, payload); }
};

}  // namespace cch::test
