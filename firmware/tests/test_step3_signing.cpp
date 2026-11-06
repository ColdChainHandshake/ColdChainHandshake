// Step 3 of WALKTHROUGH.md: ECDSA P-256, and refusing the published test key.

#include <gtest/gtest.h>

#include <algorithm>

#include "cch/signing.hpp"
#include "vectors.hpp"

namespace cch::test {
namespace {

// P-256's generator point G: a valid public key that isn't the test key.
PublicKey generator() {
    return fixed_from_hex<65>(
        "04"
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");
}

PrivateKey one() {
    PrivateKey key{};
    key.back() = 1;
    return key;
}

TEST(Step3Ecdsa, DerivesTheVectorPublicKey) {
    auto key = public_key_of(vectors().private_key);
    ASSERT_TRUE(key.has_value());
    EXPECT_EQ(to_hex(*key), to_hex(vectors().public_key));
    EXPECT_EQ(public_key_of(one()), generator());
}

TEST(Step3Ecdsa, RefusesAPrivateKeyOutsideTheGroup) {
    ASSERT_TRUE(public_key_of(one()).has_value());
    EXPECT_FALSE(public_key_of(PrivateKey{}).has_value());
    EXPECT_FALSE(public_key_of(kP256Order).has_value());
}

TEST(Step3Ecdsa, SignsEachBatchExactlyAsTheVectors) {
    for (const auto& b : vectors().batches) {
        EXPECT_EQ(to_hex(ecdsa_sign(vectors().private_key, b.payload)), to_hex(b.signature))
            << "batch " << b.first_seq << ".." << b.last_seq;
    }
}

TEST(Step3Ecdsa, SignsTheHeadExactlyAsTheVectors) {
    EXPECT_EQ(to_hex(ecdsa_sign(vectors().private_key, vectors().head.payload)), to_hex(vectors().head.signature));
}

TEST(Step3Ecdsa, VerifiesGoodSignaturesAndRefusesChangedOnes) {
    const auto& b = vectors().batches.front();
    EXPECT_TRUE(ecdsa_verify(vectors().public_key, b.payload, b.signature));

    Bytes payload = b.payload;
    payload.back() ^= 0x01;
    EXPECT_FALSE(ecdsa_verify(vectors().public_key, payload, b.signature));

    Signature signature = b.signature;
    signature[40] ^= 0x01;
    EXPECT_FALSE(ecdsa_verify(vectors().public_key, b.payload, signature));

    EXPECT_FALSE(ecdsa_verify(generator(), b.payload, b.signature));
}

TEST(Step3DeviceKey, TheBuiltInTestKeyIsTheVectorKey) {
    EXPECT_TRUE(std::equal(kTestKeyX.begin(), kTestKeyX.end(), vectors().public_key.begin() + 1));
}

TEST(Step3DeviceKey, RefusesTheTestKey) {
    EXPECT_EQ(check_device_key(vectors().public_key), KeyCheck::kTestKey);
}

TEST(Step3DeviceKey, RefusesTheMirroredTestKeyBecauseItsPrivateKeyIsKnownToo) {
    EXPECT_EQ(public_key_of(mirrored_test_private_key()), mirrored_test_public_key());
    EXPECT_EQ(check_device_key(mirrored_test_public_key()), KeyCheck::kTestKey);
}

TEST(Step3DeviceKey, AcceptsAnotherKeyAndRefusesAPointNotOnTheCurve) {
    EXPECT_EQ(check_device_key(generator()), KeyCheck::kOk);

    PublicKey off_curve = generator();
    off_curve.back() ^= 0x01;
    EXPECT_EQ(check_device_key(off_curve), KeyCheck::kInvalid);
    EXPECT_EQ(check_device_key(PublicKey{}), KeyCheck::kInvalid);
}

TEST(Step3DeviceSigner, WillNotHoldTheTestKeyOrItsMirror) {
    ASSERT_TRUE(DeviceSigner::create(one()).has_value());
    EXPECT_FALSE(DeviceSigner::create(vectors().private_key).has_value());
    EXPECT_FALSE(DeviceSigner::create(mirrored_test_private_key()).has_value());
    EXPECT_FALSE(DeviceSigner::create(PrivateKey{}).has_value());
}

TEST(Step3DeviceSigner, SignsWithItsOwnKey) {
    auto signer = DeviceSigner::create(one());
    ASSERT_TRUE(signer.has_value());
    const Bytes payload = from_hex("01020304");

    EXPECT_EQ(signer->public_key(), generator());
    EXPECT_TRUE(ecdsa_verify(signer->public_key(), payload, signer->sign(payload)));
}

}  // namespace
}  // namespace cch::test
