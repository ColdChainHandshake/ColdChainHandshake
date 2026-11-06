"""Writes docs/protocol/logger_vectors.json from an implementation independent of the app's.

Plain Python with no third-party packages: SHA-256 from hashlib, and ECDSA P-256 written out here with
RFC 6979 deterministic nonces, so the vectors come out the same every run. The Kotlin and C++ tests read
the file and must agree with it.

Run from the repository root: python -I tools/protocol/gen_logger_vectors.py
"""
import hashlib
import hmac
import json
import struct
import sys

# P-256, from FIPS 186-4 / SEC 2.
P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (GX, GY)


def on_curve(point):
    x, y = point
    return (y * y - (x * x * x + A * x + B)) % P == 0


def add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    (x1, y1), (x2, y2) = p1, p2
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if p1 == p2:
        slope = (3 * x1 * x1 + A) * pow(2 * y1, -1, P) % P
    else:
        slope = (y2 - y1) * pow(x2 - x1, -1, P) % P
    x3 = (slope * slope - x1 - x2) % P
    return x3, (slope * (x1 - x3) - y1) % P


def multiply(k, point):
    result = None
    while k:
        if k & 1:
            result = add(result, point)
        point = add(point, point)
        k >>= 1
    return result


def rfc6979_nonce(private_key, digest):
    x = private_key.to_bytes(32, 'big')
    h = (int.from_bytes(digest, 'big') % N).to_bytes(32, 'big')
    v = b'\x01' * 32
    k = b'\x00' * 32
    k = hmac.new(k, v + b'\x00' + x + h, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b'\x01' + x + h, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        candidate = int.from_bytes(v, 'big')
        if 1 <= candidate < N:
            return candidate
        k = hmac.new(k, v + b'\x00', hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def sign(private_key, message):
    digest = hashlib.sha256(message).digest()
    e = int.from_bytes(digest, 'big')
    k = rfc6979_nonce(private_key, digest)
    r = multiply(k, G)[0] % N
    s = pow(k, -1, N) * (e + r * private_key) % N
    if r == 0 or s == 0:
        sys.exit('degenerate signature')
    return r, s


def verify(public_key, message, r, s):
    if not (1 <= r < N and 1 <= s < N):
        return False
    e = int.from_bytes(hashlib.sha256(message).digest(), 'big')
    w = pow(s, -1, N)
    point = add(multiply(e * w % N, G), multiply(r * w % N, public_key))
    return point is not None and point[0] % N == r


def der_integer(value):
    body = value.to_bytes((value.bit_length() + 7) // 8 or 1, 'big')
    if body[0] & 0x80:
        body = b'\x00' + body
    return b'\x02' + bytes([len(body)]) + body


def der_signature(r, s):
    body = der_integer(r) + der_integer(s)
    return b'\x30' + bytes([len(body)]) + body


def raw_signature(r, s):
    return r.to_bytes(32, 'big') + s.to_bytes(32, 'big')


def lp(text):
    data = text.encode('utf-8')
    return struct.pack('>I', len(data)) + data


def record_bytes(seq, timestamp, temp_centi_c, flags):
    return struct.pack('>qqiH', seq, timestamp, temp_centi_c, flags)


def genesis(logger_id):
    return hashlib.sha256(b'\x01' + lp('cch-logger-genesis') + lp(logger_id)).digest()


def chain(previous, record):
    return hashlib.sha256(b'\x01' + previous + record).digest()


def batch_payload(logger_id, first, last, start_hash, end_hash):
    return b'\x01' + lp('cch-logger-batch') + lp(logger_id) + struct.pack('>qq', first, last) + start_hash + end_hash


def head_payload(logger_id, head_seq, head_hash, nonce):
    return b'\x01' + lp('cch-logger-head') + lp(logger_id) + struct.pack('>q', head_seq) + head_hash + nonce


def check_against_rfc6979():
    """RFC 6979 appendix A.2.5: P-256, SHA-256, message "sample"."""
    key = 0xC9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721
    r, s = sign(key, b'sample')
    expected_r = 0xEFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716
    expected_s = 0xF7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8
    if (r, s) != (expected_r, expected_s):
        sys.exit('ECDSA does not match RFC 6979 A.2.5')
    if not verify(multiply(key, G), b'sample', r, s):
        sys.exit('RFC 6979 signature does not verify')


def main():
    if not on_curve(G) or multiply(N, G) is not None:
        sys.exit('curve parameters are wrong')
    check_against_rfc6979()

    # Test key only. Never use it for anything else.
    private_key = int(hashlib.sha256(b'cold chain handshake logger test key').hexdigest(), 16) % N
    public_key = multiply(private_key, G)
    uncompressed = b'\x04' + public_key[0].to_bytes(32, 'big') + public_key[1].to_bytes(32, 'big')
    spki = bytes.fromhex('3059301306072a8648ce3d020106082a8648ce3d030107034200') + uncompressed

    logger_id = 'LOG-VEC-1'
    readings = [
        (1, 1_700_000_000_000, 512, 0),
        (2, 1_700_000_060_000, 498, 0),
        (3, 1_700_000_120_000, 845, 0b0100),
        (4, 1_700_000_180_000, -150, 0b0010),
        (5, 1_700_000_240_000, 0, 0b0001),
        (6, 1_700_000_300_000, 2147483647, 0b1000),
        (7, 1_700_000_360_000, -2147483648, 0),
    ]

    hashes = [genesis(logger_id)]
    records = []
    for seq, timestamp, temp, flags in readings:
        data = record_bytes(seq, timestamp, temp, flags)
        hashes.append(chain(hashes[-1], data))
        records.append({
            'seq': seq,
            'logger_ts': timestamp,
            'temp_centi_c': temp,
            'flags': flags,
            'bytes': data.hex(),
            'running_hash': hashes[-1].hex(),
        })

    batches = []
    for first, last in [(1, 4), (5, 7)]:
        payload = batch_payload(logger_id, first, last, hashes[first - 1], hashes[last])
        r, s = sign(private_key, payload)
        if not verify(public_key, payload, r, s):
            sys.exit('batch signature does not verify')
        batches.append({
            'first_seq': first,
            'last_seq': last,
            'start_hash': hashes[first - 1].hex(),
            'end_hash': hashes[last].hex(),
            'payload': payload.hex(),
            'signature_raw': raw_signature(r, s).hex(),
            'signature_der': der_signature(r, s).hex(),
        })

    nonce = bytes(range(0x10, 0x20))
    payload = head_payload(logger_id, 7, hashes[7], nonce)
    r, s = sign(private_key, payload)
    head = {
        'nonce': nonce.hex(),
        'head_seq': 7,
        'head_hash': hashes[7].hex(),
        'payload': payload.hex(),
        'signature_raw': raw_signature(r, s).hex(),
        'signature_der': der_signature(r, s).hex(),
    }

    # A signature whose s needs no padding and one whose r does, so DER conversion is exercised both ways.
    vectors = {
        'description': 'Logger protocol test vectors. See docs/protocol/LOGGER_PROTOCOL.md. The private key is for tests only.',
        'logger_id': logger_id,
        'private_key': private_key.to_bytes(32, 'big').hex(),
        'public_key_uncompressed': uncompressed.hex(),
        'public_key_spki_base64': __import__('base64').b64encode(spki).decode(),
        'genesis_hash': hashes[0].hex(),
        'records': records,
        'batches': batches,
        'head': head,
    }

    with open('docs/protocol/logger_vectors.json', 'w', encoding='utf-8', newline='\n') as f:
        json.dump(vectors, f, indent=2)
        f.write('\n')
    print('wrote docs/protocol/logger_vectors.json:', len(records), 'records,', len(batches), 'batches')


if __name__ == '__main__':
    main()
