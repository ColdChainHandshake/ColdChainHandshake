#include "cch/signing.hpp"

#include <algorithm>
#include <random>
#include <stdexcept>

#include "cch/hashing.hpp"
#include "mbedtls/ecdsa.h"
#include "mbedtls/ecp.h"
#include "mbedtls/platform_util.h"

namespace cch {
namespace {

// mbedTLS 3.x blinds every scalar multiplication with random values. They change how the result is computed,
// never the result: a deterministic signature comes out the same whatever this returns.
int blinding_rng(void*, unsigned char* out, size_t size) {
    static thread_local std::random_device device;
    for (size_t i = 0; i < size; ++i) out[i] = static_cast<unsigned char>(device());
    return 0;
}

// The curve and the numbers mbedTLS works with, freed however the function leaves.
struct P256 {
    mbedtls_ecp_group group;
    P256() {
        mbedtls_ecp_group_init(&group);
        if (mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) != 0) {
            mbedtls_ecp_group_free(&group);
            throw std::runtime_error("P-256 could not be loaded.");
        }
    }
    ~P256() { mbedtls_ecp_group_free(&group); }
    P256(const P256&) = delete;
    P256& operator=(const P256&) = delete;
};

struct Mpi {
    mbedtls_mpi value;
    Mpi() { mbedtls_mpi_init(&value); }
    ~Mpi() { mbedtls_mpi_free(&value); }
    Mpi(const Mpi&) = delete;
    Mpi& operator=(const Mpi&) = delete;
};

struct Point {
    mbedtls_ecp_point value;
    Point() { mbedtls_ecp_point_init(&value); }
    ~Point() { mbedtls_ecp_point_free(&value); }
    Point(const Point&) = delete;
    Point& operator=(const Point&) = delete;
};

void check(int result, const char* what) {
    if (result != 0) throw std::runtime_error(what);
}

/// Reads an uncompressed point and checks it lies on the curve; false for anything else.
bool read_point(const P256& curve, const PublicKey& key, Point& point) {
    if (key[0] != 0x04) return false;
    if (mbedtls_ecp_point_read_binary(&curve.group, &point.value, key.data(), key.size()) != 0) return false;
    return mbedtls_ecp_check_pubkey(&curve.group, &point.value) == 0;
}

}  // namespace

Signature ecdsa_sign(const PrivateKey& key, ByteView payload) {
    const Hash digest = sha256(payload);
    P256 curve;
    Mpi d, r, s;
    check(mbedtls_mpi_read_binary(&d.value, key.data(), key.size()), "The private key could not be read.");
    check(mbedtls_ecp_check_privkey(&curve.group, &d.value), "The private key is not in the group.");
    // RFC 6979 nonce from HMAC-SHA-256; s is left as computed, high or low.
    check(mbedtls_ecdsa_sign_det_ext(&curve.group, &r.value, &s.value, &d.value, digest.data(), digest.size(),
                                     MBEDTLS_MD_SHA256, blinding_rng, nullptr),
          "Signing failed.");
    Signature out{};
    check(mbedtls_mpi_write_binary(&r.value, out.data(), 32), "r does not fit 32 bytes.");
    check(mbedtls_mpi_write_binary(&s.value, out.data() + 32, 32), "s does not fit 32 bytes.");
    return out;
}

bool ecdsa_verify(const PublicKey& key, ByteView payload, const Signature& signature) {
    P256 curve;
    Point q;
    if (!read_point(curve, key, q)) return false;
    Mpi r, s;
    if (mbedtls_mpi_read_binary(&r.value, signature.data(), 32) != 0) return false;
    if (mbedtls_mpi_read_binary(&s.value, signature.data() + 32, 32) != 0) return false;
    const Hash digest = sha256(payload);
    // mbedTLS refuses r or s outside 1..n-1, and accepts both forms of s.
    return mbedtls_ecdsa_verify(&curve.group, digest.data(), digest.size(), &q.value, &r.value, &s.value) == 0;
}

std::optional<PublicKey> public_key_of(const PrivateKey& key) {
    P256 curve;
    Mpi d;
    if (mbedtls_mpi_read_binary(&d.value, key.data(), key.size()) != 0) return std::nullopt;
    if (mbedtls_ecp_check_privkey(&curve.group, &d.value) != 0) return std::nullopt;  // 0, or not below n
    Point q;
    check(mbedtls_ecp_mul(&curve.group, &q.value, &d.value, &curve.group.G, blinding_rng, nullptr),
          "The public point could not be computed.");
    PublicKey out{};
    size_t written = 0;
    check(mbedtls_ecp_point_write_binary(&curve.group, &q.value, MBEDTLS_ECP_PF_UNCOMPRESSED, &written, out.data(),
                                         out.size()),
          "The public point could not be written.");
    if (written != out.size()) throw std::runtime_error("The public point has the wrong size.");
    return out;
}

KeyCheck check_device_key(const PublicKey& key) {
    P256 curve;
    Point q;
    if (!read_point(curve, key, q)) return KeyCheck::kInvalid;
    // X only: the mirrored point (X, -Y) belongs to n - d, as public as d itself.
    if (std::equal(kTestKeyX.begin(), kTestKeyX.end(), key.begin() + 1)) return KeyCheck::kTestKey;
    return KeyCheck::kOk;
}

std::optional<DeviceSigner> DeviceSigner::create(const PrivateKey& key) {
    const std::optional<PublicKey> public_key = public_key_of(key);
    if (!public_key || check_device_key(*public_key) != KeyCheck::kOk) return std::nullopt;
    return DeviceSigner(key, *public_key);
}

DeviceSigner::DeviceSigner(const PrivateKey& key, const PublicKey& public_key) : key_(key), public_key_(public_key) {}

DeviceSigner::~DeviceSigner() { mbedtls_platform_zeroize(key_.data(), key_.size()); }

PublicKey DeviceSigner::public_key() const { return public_key_; }

Signature DeviceSigner::sign(ByteView payload) { return ecdsa_sign(key_, payload); }

}  // namespace cch
