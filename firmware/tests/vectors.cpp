#include "vectors.hpp"

#include <fstream>
#include <sstream>
#include <stdexcept>

#include <nlohmann/json.hpp>

namespace cch::test {

namespace {

Vectors load() {
    std::ifstream file(CCH_VECTORS_PATH);
    if (!file) throw std::runtime_error(std::string("cannot open ") + CCH_VECTORS_PATH);
    nlohmann::json json = nlohmann::json::parse(file);

    Vectors v;
    v.logger_id = json.at("logger_id").get<std::string>();
    v.private_key = fixed_from_hex<32>(json.at("private_key").get<std::string>());
    v.public_key = fixed_from_hex<65>(json.at("public_key_uncompressed").get<std::string>());
    v.public_key_spki_base64 = json.at("public_key_spki_base64").get<std::string>();
    v.genesis_hash = fixed_from_hex<32>(json.at("genesis_hash").get<std::string>());

    for (const auto& r : json.at("records")) {
        VectorRecord record;
        record.record.seq = r.at("seq").get<std::int64_t>();
        record.record.logger_ts = r.at("logger_ts").get<std::int64_t>();
        record.record.temp_centi_c = r.at("temp_centi_c").get<std::int32_t>();
        record.record.flags = r.at("flags").get<std::uint16_t>();
        record.bytes = from_hex(r.at("bytes").get<std::string>());
        record.running_hash = fixed_from_hex<32>(r.at("running_hash").get<std::string>());
        v.records.push_back(record);
    }

    for (const auto& b : json.at("batches")) {
        VectorBatch batch;
        batch.first_seq = b.at("first_seq").get<std::int64_t>();
        batch.last_seq = b.at("last_seq").get<std::int64_t>();
        batch.start_hash = fixed_from_hex<32>(b.at("start_hash").get<std::string>());
        batch.end_hash = fixed_from_hex<32>(b.at("end_hash").get<std::string>());
        batch.payload = from_hex(b.at("payload").get<std::string>());
        batch.signature = fixed_from_hex<64>(b.at("signature_raw").get<std::string>());
        v.batches.push_back(batch);
    }

    const auto& h = json.at("head");
    v.head.nonce = fixed_from_hex<16>(h.at("nonce").get<std::string>());
    v.head.head_seq = h.at("head_seq").get<std::int64_t>();
    v.head.head_hash = fixed_from_hex<32>(h.at("head_hash").get<std::string>());
    v.head.payload = from_hex(h.at("payload").get<std::string>());
    v.head.signature = fixed_from_hex<64>(h.at("signature_raw").get<std::string>());

    const auto& g = json.at("gatt");
    v.gatt.firmware_version = g.at("firmware_version").get<std::string>();
    v.gatt.device_information = from_hex(g.at("device_information").get<std::string>());
    v.gatt.status = from_hex(g.at("status").get<std::string>());
    v.gatt.challenge_response = from_hex(g.at("challenge").at("response").get<std::string>());
    for (const auto& r : g.at("history_requests")) {
        VectorHistoryRequest request;
        request.from_seq = r.at("from_seq").get<std::int64_t>();
        request.max_records = r.at("max_records").get<std::uint16_t>();
        request.att_mtu = r.at("att_mtu").get<std::size_t>();
        request.request = from_hex(r.at("request").get<std::string>());
        for (const auto& n : r.at("notifications")) request.notifications.push_back(from_hex(n.get<std::string>()));
        v.gatt.history_requests.push_back(request);
    }
    return v;
}

}  // namespace

const Vectors& vectors() {
    static const Vectors loaded = load();
    return loaded;
}

Bytes from_hex(std::string_view hex) {
    if (hex.size() % 2 != 0) throw std::runtime_error("odd-length hex");
    auto nibble = [](char c) -> std::uint8_t {
        if (c >= '0' && c <= '9') return static_cast<std::uint8_t>(c - '0');
        if (c >= 'a' && c <= 'f') return static_cast<std::uint8_t>(c - 'a' + 10);
        if (c >= 'A' && c <= 'F') return static_cast<std::uint8_t>(c - 'A' + 10);
        throw std::runtime_error("not hex");
    };
    Bytes out;
    out.reserve(hex.size() / 2);
    for (std::size_t i = 0; i < hex.size(); i += 2) {
        out.push_back(static_cast<std::uint8_t>((nibble(hex[i]) << 4) | nibble(hex[i + 1])));
    }
    return out;
}

std::string to_hex(ByteView bytes) {
    static constexpr char kDigits[] = "0123456789abcdef";
    std::string out;
    out.reserve(bytes.size() * 2);
    for (std::uint8_t b : bytes) {
        out.push_back(kDigits[b >> 4]);
        out.push_back(kDigits[b & 0x0F]);
    }
    return out;
}

std::array<std::uint8_t, 32> subtract(const std::array<std::uint8_t, 32>& a, const std::array<std::uint8_t, 32>& b) {
    std::array<std::uint8_t, 32> out{};
    int borrow = 0;
    for (int i = 31; i >= 0; --i) {
        int digit = a[i] - b[i] - borrow;
        borrow = digit < 0 ? 1 : 0;
        out[i] = static_cast<std::uint8_t>(digit + (borrow << 8));
    }
    if (borrow != 0) throw std::runtime_error("subtract: a < b");
    return out;
}

const std::array<std::uint8_t, 32> kP256Prime =
    fixed_from_hex<32>("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff");
const std::array<std::uint8_t, 32> kP256Order =
    fixed_from_hex<32>("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");

PublicKey mirrored_test_public_key() {
    PublicKey key = vectors().public_key;
    std::array<std::uint8_t, 32> y{};
    std::copy(key.begin() + 33, key.end(), y.begin());
    std::array<std::uint8_t, 32> mirrored_y = subtract(kP256Prime, y);
    std::copy(mirrored_y.begin(), mirrored_y.end(), key.begin() + 33);
    return key;
}

PrivateKey mirrored_test_private_key() { return subtract(kP256Order, vectors().private_key); }

RecordLog vector_log(std::size_t capacity) {
    RecordLog log(vectors().logger_id, capacity);
    for (const auto& v : vectors().records) {
        log.append(v.record.logger_ts, v.record.temp_centi_c, v.record.flags);
    }
    return log;
}

}  // namespace cch::test
