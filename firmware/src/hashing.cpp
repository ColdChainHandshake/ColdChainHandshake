#include "cch/hashing.hpp"

#include <array>

namespace cch {
namespace {

// FIPS 180-4, section 4.2.2: the first 32 bits of the fractional parts of the cube roots of the first 64 primes.
constexpr std::array<std::uint32_t, 64> kRound = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

// Section 5.3.3: the first 32 bits of the fractional parts of the square roots of the first 8 primes.
constexpr std::array<std::uint32_t, 8> kInitial = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                                                   0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

constexpr std::uint32_t rotr(std::uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

void compress(std::array<std::uint32_t, 8>& state, const std::uint8_t* block) {
    std::array<std::uint32_t, 64> w{};
    for (int t = 0; t < 16; ++t) {
        w[t] = (std::uint32_t{block[4 * t]} << 24) | (std::uint32_t{block[4 * t + 1]} << 16) |
               (std::uint32_t{block[4 * t + 2]} << 8) | std::uint32_t{block[4 * t + 3]};
    }
    for (int t = 16; t < 64; ++t) {
        const std::uint32_t s0 = rotr(w[t - 15], 7) ^ rotr(w[t - 15], 18) ^ (w[t - 15] >> 3);
        const std::uint32_t s1 = rotr(w[t - 2], 17) ^ rotr(w[t - 2], 19) ^ (w[t - 2] >> 10);
        w[t] = w[t - 16] + s0 + w[t - 7] + s1;
    }

    std::uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
    std::uint32_t e = state[4], f = state[5], g = state[6], h = state[7];
    for (int t = 0; t < 64; ++t) {
        const std::uint32_t big1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        const std::uint32_t choose = (e & f) ^ (~e & g);
        const std::uint32_t t1 = h + big1 + choose + kRound[t] + w[t];
        const std::uint32_t big0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        const std::uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
        const std::uint32_t t2 = big0 + majority;
        h = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }
    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
    state[5] += f;
    state[6] += g;
    state[7] += h;
}

void append_u64(Bytes& out, std::uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8) out.push_back(static_cast<std::uint8_t>(value >> shift));
}

void append_raw(Bytes& out, ByteView bytes) { out.insert(out.end(), bytes.begin(), bytes.end()); }

constexpr std::uint8_t kVersion = 1;

}  // namespace

Hash sha256(ByteView data) {
    std::array<std::uint32_t, 8> state = kInitial;

    std::size_t whole = data.size() / 64;
    for (std::size_t i = 0; i < whole; ++i) compress(state, data.data() + 64 * i);

    // Section 5.1.1: a 1 bit, zeros, then the length in bits as 64 bits, filling one or two final blocks.
    std::array<std::uint8_t, 128> tail{};
    const std::size_t rest = data.size() - 64 * whole;
    for (std::size_t i = 0; i < rest; ++i) tail[i] = data[64 * whole + i];
    tail[rest] = 0x80;
    const std::size_t tail_blocks = rest + 1 + 8 <= 64 ? 1 : 2;
    const std::uint64_t bits = static_cast<std::uint64_t>(data.size()) * 8;
    for (int i = 0; i < 8; ++i) tail[64 * tail_blocks - 1 - i] = static_cast<std::uint8_t>(bits >> (8 * i));
    for (std::size_t i = 0; i < tail_blocks; ++i) compress(state, tail.data() + 64 * i);

    Hash out{};
    for (int i = 0; i < 8; ++i) {
        out[4 * i] = static_cast<std::uint8_t>(state[i] >> 24);
        out[4 * i + 1] = static_cast<std::uint8_t>(state[i] >> 16);
        out[4 * i + 2] = static_cast<std::uint8_t>(state[i] >> 8);
        out[4 * i + 3] = static_cast<std::uint8_t>(state[i]);
    }
    return out;
}

void append_lp(Bytes& out, std::string_view text) {
    const auto size = static_cast<std::uint32_t>(text.size());
    for (int shift = 24; shift >= 0; shift -= 8) out.push_back(static_cast<std::uint8_t>(size >> shift));
    out.insert(out.end(), text.begin(), text.end());
}

Bytes genesis_payload(std::string_view logger_id) {
    Bytes out{kVersion};
    append_lp(out, "cch-logger-genesis");
    append_lp(out, logger_id);
    return out;
}

Bytes chain_payload(const Hash& previous, const Record& record) {
    Bytes out{kVersion};
    append_raw(out, previous);
    append_raw(out, encode(record));
    return out;
}

Bytes batch_payload(std::string_view logger_id, std::int64_t first_seq, std::int64_t last_seq,
                    const Hash& start_hash, const Hash& end_hash) {
    Bytes out{kVersion};
    append_lp(out, "cch-logger-batch");
    append_lp(out, logger_id);
    append_u64(out, static_cast<std::uint64_t>(first_seq));
    append_u64(out, static_cast<std::uint64_t>(last_seq));
    append_raw(out, start_hash);
    append_raw(out, end_hash);
    return out;
}

Bytes head_payload(std::string_view logger_id, std::int64_t head_seq, const Hash& head_hash, const Nonce& nonce) {
    Bytes out{kVersion};
    append_lp(out, "cch-logger-head");
    append_lp(out, logger_id);
    append_u64(out, static_cast<std::uint64_t>(head_seq));
    append_raw(out, head_hash);
    append_raw(out, nonce);
    return out;
}

Hash genesis(std::string_view logger_id) { return sha256(genesis_payload(logger_id)); }

Hash chain(const Hash& previous, const Record& record) { return sha256(chain_payload(previous, record)); }

}  // namespace cch
