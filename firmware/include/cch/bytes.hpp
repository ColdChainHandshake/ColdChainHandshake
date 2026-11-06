// Byte types shared by the logger library. All multi-byte integers on the wire are big-endian
// (docs/protocol/LOGGER_PROTOCOL.md, "Encoding").
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace cch {

using Bytes = std::vector<std::uint8_t>;
using ByteView = std::span<const std::uint8_t>;

/// A SHA-256 digest, 32 raw bytes.
using Hash = std::array<std::uint8_t, 32>;

/// The phone's challenge: 16 random bytes.
using Nonce = std::array<std::uint8_t, 16>;

/// An ECDSA P-256 signature as raw `r || s`, each half 32 bytes, big-endian.
using Signature = std::array<std::uint8_t, 64>;

/// A P-256 private key: the scalar `d`, 32 bytes, big-endian.
using PrivateKey = std::array<std::uint8_t, 32>;

/// A P-256 public key as an uncompressed point: `04 || X || Y`, 65 bytes.
using PublicKey = std::array<std::uint8_t, 65>;

}  // namespace cch
