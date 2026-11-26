// ECDSA P-256 with SHA-256, and the device key a logger signs with. LOGGER_PROTOCOL.md, "Signatures".
#pragma once

#include <array>
#include <cstdint>
#include <optional>

#include "cch/bytes.hpp"

namespace cch {

/// Signs SHA-256(payload) with an RFC 6979 deterministic nonce, so the vectors come out exactly. A logger may use
/// any secure nonce; deterministic is the safe choice on a board with a weak random source.
/// mbedtls_ecdsa_sign_det_ext() does this. Returns raw `r || s`; don't change `s` to its low form.
Signature ecdsa_sign(const PrivateKey& key, ByteView payload);

/// Verifies a raw `r || s` signature over SHA-256(payload). Accepts high and low `s`.
bool ecdsa_verify(const PublicKey& key, ByteView payload, const Signature& signature);

/// The public point for `key`, or std::nullopt when `key` is 0 or not below the curve order n.
std::optional<PublicKey> public_key_of(const PrivateKey& key);

/// The X coordinate of the key whose private half docs/protocol/logger_vectors.json publishes. Anyone can sign as
/// it, so no logger may use it outside the host tests.
inline constexpr std::array<std::uint8_t, 32> kTestKeyX = {
    0x6f, 0x56, 0xe3, 0x53, 0xe8, 0x7d, 0x63, 0xe9, 0x76, 0xbf, 0x24, 0xb0, 0x9e, 0x68, 0x06, 0x33,
    0x78, 0xc5, 0xd5, 0x61, 0x13, 0x85, 0xf9, 0x06, 0x97, 0x00, 0xc4, 0x38, 0x83, 0x4d, 0x6e, 0xf7,
};

enum class KeyCheck {
    kOk,
    kTestKey,  ///< the published test key, or its mirror (same X, other Y), whose private key is `n - d`
    kInvalid,  ///< not an uncompressed point on P-256
};

/// Checks a key before a logger signs with it. Compare X only: the point (X, -Y) belongs to `n - d`, which
/// anyone holding the test key can work out. Check the point is on the curve too.
KeyCheck check_device_key(const PublicKey& key);

/// Something that signs on the logger's behalf. The history code only sees this, so tests can sign with the
/// vector key while the device can't.
class Signer {
public:
    virtual ~Signer() = default;
    virtual PublicKey public_key() const = 0;
    virtual Signature sign(ByteView payload) = 0;
};

/// The logger's own key. It refuses to exist for an invalid key, or for the test key and its mirror.
class DeviceSigner final : public Signer {
public:
    static std::optional<DeviceSigner> create(const PrivateKey& key);

    PublicKey public_key() const override;
    Signature sign(ByteView payload) override;

    DeviceSigner(const DeviceSigner&) = default;
    DeviceSigner& operator=(const DeviceSigner&) = default;
    ~DeviceSigner() override;  ///< wipes the key from memory

private:
    DeviceSigner(const PrivateKey& key, const PublicKey& public_key);
    // On the board the key lives in the chip's protected storage and never in a flash dump; here it is held in
    // memory and wiped when the signer goes.
    PrivateKey key_{};
    PublicKey public_key_{};
};

}  // namespace cch
