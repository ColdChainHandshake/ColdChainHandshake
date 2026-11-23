// Step 4 of WALKTHROUGH.md: what the logger sends over Bluetooth.

#include <gtest/gtest.h>

#include "cch/hashing.hpp"
#include "cch/history.hpp"
#include "vectors.hpp"

namespace cch::test {
namespace {

std::int64_t read_i64(const Bytes& bytes, std::size_t at) {
    std::int64_t value = 0;
    for (std::size_t i = 0; i < 8; ++i) value = static_cast<std::int64_t>((static_cast<std::uint64_t>(value) << 8) | bytes[at + i]);
    return value;
}

std::uint16_t read_u16(const Bytes& bytes, std::size_t at) {
    return static_cast<std::uint16_t>((bytes[at] << 8) | bytes[at + 1]);
}

TEST(Step4Request, ParsesFromSeqAndMaxRecords) {
    auto request = parse_history_request(from_hex("0000000000000005" "0064"));
    ASSERT_TRUE(request.has_value());
    EXPECT_EQ(request->from_seq, 5);
    EXPECT_EQ(request->max_records, 100);

    EXPECT_FALSE(parse_history_request(from_hex("0000000000000005" "00")).has_value());
    EXPECT_FALSE(parse_history_request(from_hex("0000000000000005" "006400")).has_value());
}

TEST(Step4Batch, SignsTheVectorBatches) {
    RecordLog log = vector_log();
    VectorSigner signer;
    for (const auto& b : vectors().batches) {
        auto header = sign_batch(log, b.first_seq, b.last_seq, signer);
        ASSERT_TRUE(header.has_value()) << "batch " << b.first_seq << ".." << b.last_seq;
        EXPECT_EQ(header->start_hash, b.start_hash);
        EXPECT_EQ(header->end_hash, b.end_hash);
        EXPECT_EQ(to_hex(header->signature), to_hex(b.signature));
    }
}

TEST(Step4Batch, RefusesRecordsItDoesNotHold) {
    RecordLog log = vector_log(4);  // keeps 4..7
    VectorSigner signer;

    ASSERT_TRUE(sign_batch(log, 4, 7, signer).has_value());
    EXPECT_FALSE(sign_batch(log, 3, 7, signer).has_value());
    EXPECT_FALSE(sign_batch(log, 5, 8, signer).has_value());
    EXPECT_FALSE(sign_batch(log, 6, 5, signer).has_value());
}

TEST(Step4Notifications, SendWholeRecordsInPagesThatFitThenOneHeader) {
    RecordLog log = vector_log();
    VectorSigner signer;
    const std::size_t mtu = 150;  // 147 bytes per notification: a 3-byte page header and 6 records

    std::vector<Bytes> sent = history_notifications(log, {1, 100}, mtu, signer);

    ASSERT_EQ(sent.size(), 3u);
    std::int64_t expected_seq = 1;
    for (std::size_t page = 0; page < 2; ++page) {
        const Bytes& p = sent[page];
        ASSERT_LE(p.size(), mtu - 3);
        ASSERT_EQ(p[0], kPageRecords);
        const std::uint16_t count = read_u16(p, 1);
        ASSERT_EQ(p.size(), 3 + count * kRecordSize);
        for (std::uint16_t i = 0; i < count; ++i) {
            auto record = decode(ByteView(p.data() + 3 + i * kRecordSize, kRecordSize));
            ASSERT_TRUE(record.has_value());
            EXPECT_EQ(record->seq, expected_seq++);
        }
    }
    EXPECT_EQ(read_u16(sent[0], 1), 6);
    EXPECT_EQ(expected_seq, 8);

    const Bytes& header = sent.back();
    ASSERT_EQ(header.size(), 145u);
    EXPECT_EQ(header[0], kPageBatchHeader);
    EXPECT_EQ(read_i64(header, 1), 1);
    EXPECT_EQ(read_i64(header, 9), 7);
    EXPECT_EQ(to_hex(ByteView(header.data() + 17, 32)), to_hex(vectors().genesis_hash));
    EXPECT_EQ(to_hex(ByteView(header.data() + 49, 32)), to_hex(vectors().records.back().running_hash));
}

TEST(Step4Notifications, StartFromTheOldestKeptRecordAfterAnOverwrite) {
    RecordLog log = vector_log(4);
    VectorSigner signer;

    std::vector<Bytes> sent = history_notifications(log, {1, 100}, 512, signer);

    ASSERT_EQ(sent.size(), 2u);
    EXPECT_EQ(read_u16(sent[0], 1), 4);
    EXPECT_EQ(read_i64(sent[0], 3), 4);
    EXPECT_EQ(read_i64(sent.back(), 1), 4);
    EXPECT_EQ(to_hex(ByteView(sent.back().data() + 17, 32)), to_hex(vectors().records[2].running_hash));
}

TEST(Step4Notifications, HonourMaxRecords) {
    RecordLog log = vector_log();
    VectorSigner signer;

    std::vector<Bytes> sent = history_notifications(log, {2, 3}, 512, signer);

    ASSERT_EQ(sent.size(), 2u);
    EXPECT_EQ(read_u16(sent[0], 1), 3);
    EXPECT_EQ(read_i64(sent.back(), 1), 2);
    EXPECT_EQ(read_i64(sent.back(), 9), 4);
}

TEST(Step4Notifications, SendNothingWhenThereIsNothingToSend) {
    RecordLog log = vector_log();
    VectorSigner signer;

    ASSERT_FALSE(history_notifications(log, {1, 100}, kMinAttMtu, signer).empty());
    EXPECT_TRUE(history_notifications(log, {8, 100}, 512, signer).empty());
    EXPECT_TRUE(history_notifications(log, {1, 0}, 512, signer).empty());
    EXPECT_TRUE(history_notifications(log, {1, 100}, kMinAttMtu - 1, signer).empty());
}

TEST(Step4Challenge, AnswersAsTheVectors) {
    RecordLog log = vector_log();
    VectorSigner signer;
    const auto& h = vectors().head;

    Bytes response = challenge_response(log, h.nonce, signer);

    ASSERT_EQ(response.size(), 104u);
    EXPECT_EQ(read_i64(response, 0), h.head_seq);
    EXPECT_EQ(to_hex(ByteView(response.data() + 8, 32)), to_hex(h.head_hash));
    EXPECT_EQ(to_hex(ByteView(response.data() + 40, 64)), to_hex(h.signature));
}

TEST(Step4Status, ReportsOldestAndNewest) {
    EXPECT_EQ(to_hex(status_value(vector_log(4), flags::kBatteryLow)), "0000000000000004" "0000000000000007" "0004");
    EXPECT_EQ(to_hex(status_value(RecordLog("LOG-NEW", 4), 0)), "0000000000000001" "0000000000000000" "0000");
}

TEST(Step4DeviceInformation, IsIdProtocolAndFirmware) {
    EXPECT_EQ(to_hex(device_information("LOG-1", "fw-2")),
              "00000005" "4c4f472d31" "01" "00000004" "66772d32");
}

// The phone's Bluetooth code (core-domain, logger/ble) is tested against these same bytes, so matching them
// byte for byte means a real logger and the app agree on the wire, not only on the hashes.
TEST(Step4Vectors, EveryCharacteristicMatchesThePythonBytes) {
    RecordLog log = vector_log();
    VectorSigner signer;
    const VectorGatt& g = vectors().gatt;

    EXPECT_EQ(to_hex(device_information(vectors().logger_id, g.firmware_version)), to_hex(g.device_information));
    EXPECT_EQ(to_hex(status_value(log, 0)), to_hex(g.status));
    EXPECT_EQ(to_hex(challenge_response(log, vectors().head.nonce, signer)), to_hex(g.challenge_response));

    ASSERT_EQ(g.history_requests.size(), 5u);
    for (const auto& r : g.history_requests) {
        auto parsed = parse_history_request(r.request);
        ASSERT_TRUE(parsed.has_value());
        EXPECT_EQ(parsed->from_seq, r.from_seq);
        EXPECT_EQ(parsed->max_records, r.max_records);

        std::vector<Bytes> sent = history_notifications(log, *parsed, r.att_mtu, signer);
        ASSERT_EQ(sent.size(), r.notifications.size()) << "from " << r.from_seq << " at MTU " << r.att_mtu;
        for (std::size_t i = 0; i < sent.size(); ++i) {
            EXPECT_EQ(to_hex(sent[i]), to_hex(r.notifications[i])) << "notification " << i << " from " << r.from_seq;
        }
    }
}

}  // namespace
}  // namespace cch::test
