#include "cch/signing.hpp"

#include "todo.hpp"

namespace cch {

Signature ecdsa_sign(const PrivateKey& key, ByteView payload) {
    // TODO(owner): step 3 of WALKTHROUGH.md.
    todo("ecdsa_sign, WALKTHROUGH.md step 3");
}

bool ecdsa_verify(const PublicKey& key, ByteView payload, const Signature& signature) {
    // TODO(owner): step 3 of WALKTHROUGH.md.
    todo("ecdsa_verify, WALKTHROUGH.md step 3");
}

std::optional<PublicKey> public_key_of(const PrivateKey& key) {
    // TODO(owner): step 3 of WALKTHROUGH.md.
    todo("public_key_of, WALKTHROUGH.md step 3");
}

KeyCheck check_device_key(const PublicKey& key) {
    // TODO(owner): step 3 of WALKTHROUGH.md.
    todo("check_device_key, WALKTHROUGH.md step 3");
}

std::optional<DeviceSigner> DeviceSigner::create(const PrivateKey& key) {
    // TODO(owner): step 3 of WALKTHROUGH.md.
    todo("DeviceSigner::create, WALKTHROUGH.md step 3");
}

PublicKey DeviceSigner::public_key() const {
    // TODO(owner): step 3 of WALKTHROUGH.md.
    todo("DeviceSigner::public_key, WALKTHROUGH.md step 3");
}

Signature DeviceSigner::sign(ByteView payload) {
    // TODO(owner): step 3 of WALKTHROUGH.md.
    todo("DeviceSigner::sign, WALKTHROUGH.md step 3");
}

}  // namespace cch
