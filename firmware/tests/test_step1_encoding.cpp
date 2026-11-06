// Step 1 of WALKTHROUGH.md: records, length prefixes, payloads, SHA-256 and the running hash.

#include <gtest/gtest.h>

#include <string>

#include "cch/hashing.hpp"
#include "cch/record.hpp"
#include "vectors.hpp"

namespace cch::test {
namespace {

Bytes text(std::string_view s) { return Bytes(s.begin(), s.end()); }

TEST(Step1Record, EncodesEachVectorRecord) {
    for (const auto& v : vectors().records) {
        EXPECT_EQ(to_hex(encode(v.record)), to_hex(v.bytes)) << "seq " << v.record.seq;
    }
}

TEST(Step1Record, DecodesWhatItEncodes) {
    for (const auto& v : vectors().records) {
        auto decoded = decode(v.bytes);
        ASSERT_TRUE(decoded.has_value()) << "seq " << v.record.seq;
        EXPECT_EQ(*decoded, v.record);
    }
}

TEST(Step1Record, RefusesAnyOtherLength) {
    Bytes bytes = vectors().records.front().bytes;
    ASSERT_TRUE(decode(bytes).has_value());

    Bytes longer = bytes;
    longer.push_back(0);
    Bytes shorter(bytes.begin(), bytes.end() - 1);

    EXPECT_FALSE(decode(longer).has_value());
    EXPECT_FALSE(decode(shorter).has_value());
    EXPECT_FALSE(decode(Bytes{}).has_value());
}

TEST(Step1Record, KeepsNegativeValuesAndUnknownFlagBits) {
    const Record record{9'000'000'000LL, -1, -4321, 0x8001};
    auto decoded = decode(encode(record));
    ASSERT_TRUE(decoded.has_value());
    EXPECT_EQ(*decoded, record);
}

TEST(Step1Sha256, MatchesTheFipsExamples) {
    EXPECT_EQ(to_hex(sha256(text(""))), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    EXPECT_EQ(to_hex(sha256(text("abc"))), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    // 56 bytes: the padding spills into a second block.
    EXPECT_EQ(to_hex(sha256(text("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))),
              "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
}

TEST(Step1LengthPrefix, IsFourBytesBigEndianThenTheText) {
    Bytes out;
    append_lp(out, "ab");
    append_lp(out, "");
    EXPECT_EQ(to_hex(out), "00000002616200000000");
}

TEST(Step1RunningHash, GenesisMatchesTheVectors) {
    EXPECT_EQ(to_hex(genesis(vectors().logger_id)), to_hex(vectors().genesis_hash));
}

TEST(Step1RunningHash, EachRecordAdvancesTheHashAsInTheVectors) {
    Hash hash = genesis(vectors().logger_id);
    for (const auto& v : vectors().records) {
        hash = chain(hash, v.record);
        EXPECT_EQ(to_hex(hash), to_hex(v.running_hash)) << "seq " << v.record.seq;
    }
}

TEST(Step1Payloads, BatchPayloadsMatchTheVectors) {
    for (const auto& b : vectors().batches) {
        EXPECT_EQ(to_hex(batch_payload(vectors().logger_id, b.first_seq, b.last_seq, b.start_hash, b.end_hash)),
                  to_hex(b.payload));
    }
}

TEST(Step1Payloads, HeadPayloadMatchesTheVectors) {
    const auto& h = vectors().head;
    EXPECT_EQ(to_hex(head_payload(vectors().logger_id, h.head_seq, h.head_hash, h.nonce)), to_hex(h.payload));
}

}  // namespace
}  // namespace cch::test
