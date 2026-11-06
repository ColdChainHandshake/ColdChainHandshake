// Step 2 of WALKTHROUGH.md: the ring buffer of readings.

#include <gtest/gtest.h>

#include "cch/hashing.hpp"
#include "cch/record_log.hpp"
#include "vectors.hpp"

namespace cch::test {
namespace {

TEST(Step2RecordLog, StartsEmpty) {
    RecordLog log("LOG-NEW", 8);
    EXPECT_EQ(log.logger_id(), "LOG-NEW");
    EXPECT_EQ(log.capacity(), 8u);
    EXPECT_EQ(log.size(), 0u);
    EXPECT_EQ(log.newest_seq(), 0);
    EXPECT_EQ(log.oldest_seq(), 1);
    EXPECT_TRUE(log.read(1, 10).empty());
    EXPECT_EQ(log.hash_at(0), genesis("LOG-NEW"));
    EXPECT_FALSE(log.hash_at(1).has_value());
}

TEST(Step2RecordLog, NumbersRecordsFromOneUpwards) {
    RecordLog log("LOG-A", 8);
    EXPECT_EQ(log.append(1000, 500, 0).seq, 1);
    EXPECT_EQ(log.append(2000, 510, 0).seq, 2);
    const Record third = log.append(3000, -20, flags::kPowerLost);

    EXPECT_EQ(third, (Record{3, 3000, -20, flags::kPowerLost}));
    EXPECT_EQ(log.size(), 3u);
    EXPECT_EQ(log.oldest_seq(), 1);
    EXPECT_EQ(log.newest_seq(), 3);
}

TEST(Step2RecordLog, KeepsTheRunningHashesOfTheVectors) {
    RecordLog log(vectors().logger_id, 16);
    for (const auto& v : vectors().records) {
        EXPECT_EQ(log.append(v.record.logger_ts, v.record.temp_centi_c, v.record.flags), v.record);
    }
    EXPECT_EQ(log.hash_at(0), vectors().genesis_hash);
    for (const auto& v : vectors().records) {
        EXPECT_EQ(log.hash_at(v.record.seq), v.running_hash) << "seq " << v.record.seq;
    }
}

TEST(Step2RecordLog, OverwritesTheOldestWhenFullAndKeepsCounting) {
    RecordLog log = vector_log(4);  // 7 records into room for 4

    EXPECT_EQ(log.size(), 4u);
    EXPECT_EQ(log.oldest_seq(), 4);
    EXPECT_EQ(log.newest_seq(), 7);
    EXPECT_EQ(log.append(9'999, 0, 0).seq, 8);
    EXPECT_EQ(log.oldest_seq(), 5);
}

TEST(Step2RecordLog, KnowsTheHashJustBeforeItsOldestRecord) {
    RecordLog log = vector_log(4);  // keeps 4..7

    EXPECT_EQ(log.hash_at(3), vectors().records[2].running_hash);
    EXPECT_EQ(log.hash_at(7), vectors().records[6].running_hash);
    EXPECT_FALSE(log.hash_at(2).has_value());
    EXPECT_FALSE(log.hash_at(8).has_value());
}

TEST(Step2RecordLog, ReadsAtMostMaxRecordsFromFromSeq) {
    RecordLog log = vector_log();

    std::vector<Record> two = log.read(2, 2);
    ASSERT_EQ(two.size(), 2u);
    EXPECT_EQ(two[0], vectors().records[1].record);
    EXPECT_EQ(two[1], vectors().records[2].record);

    EXPECT_EQ(log.read(1, 100).size(), 7u);
    EXPECT_TRUE(log.read(8, 10).empty());
    EXPECT_TRUE(log.read(1, 0).empty());
}

TEST(Step2RecordLog, ReadsFromTheOldestKeptWhenAskedForOverwrittenRecords) {
    RecordLog log = vector_log(4);

    std::vector<Record> records = log.read(1, 100);
    ASSERT_EQ(records.size(), 4u);
    EXPECT_EQ(records.front().seq, 4);
    EXPECT_EQ(records.back().seq, 7);
}

}  // namespace
}  // namespace cch::test
