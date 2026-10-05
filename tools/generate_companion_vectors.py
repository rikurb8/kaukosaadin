#!/usr/bin/env python3
"""Generate synthetic (never real pairing) vectors from the pinned pyatv checkout.

Run with the environment and commands documented in docs/apple-tv-companion.md.
"""
import hashlib
import importlib.metadata
import json
from pathlib import Path
import plistlib
import subprocess
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from pyatv.auth.hap_srp import SRPAuthHandler, hkdf_expand
from pyatv.protocols.companion import keyed_archiver
from pyatv.protocols.companion.plist_payloads import get_rti_clear_text_payload, get_rti_input_text_payload
from pyatv.support.chacha20 import Chacha20Cipher, Chacha20Cipher8byteNonce
from pyatv.auth.hap_tlv8 import TlvValue, write_tlv
from pyatv.support import opack
import pyatv
from srptools import SRPContext, SRPServerSession, constants

REVISION = "b277a4c8222ecdcbaab8a24e3e713ca44765adb4"
reference = Path(sys.argv[1]).resolve()
assert subprocess.check_output(["git", "-C", str(reference), "rev-parse", "HEAD"], text=True).strip() == REVISION
assert Path(pyatv.__file__).resolve().is_relative_to(reference)
assert importlib.metadata.version("srptools") == "1.0.1"
assert importlib.metadata.version("cryptography") == "50.0.2"
assert importlib.metadata.version("chacha20poly1305-reuseable") == "0.13.2"


def raw_public(key):
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def srp_case(seed, salt, pin):
    context = SRPContext("Pair-Setup", pin, prime=constants.PRIME_3072,
                         generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
    verifier = context.get_common_password_verifier(context.get_common_password_hash(salt))
    server = SRPServerSession(context, format(verifier, "x"), private=bytes(range(32, 64)).hex())
    handler = SRPAuthHandler()
    handler._auth_private = seed  # deterministic test seed only; never use with a real device
    handler.step1(pin)  # CompanionPairingHandler.pin() zero-fills to the displayed digits
    public, proof = handler.step2(bytes.fromhex(server.public), salt)
    server.process(public.hex(), salt.hex())
    assert server.verify_proof(proof.hex().encode())
    assert handler._session.verify_proof(server.key_proof_hash)
    assert handler._session.key == server.key
    return {"pin": pin, "seed": seed.hex(), "salt": salt.hex(), "serverPublic": server.public,
            "public": public.hex(), "key": handler._session.key.decode(), "proof": proof.hex(),
            "serverProof": server.key_proof_hash.decode()}


srp = [srp_case(bytes(range(1, 33)), bytes(range(16)), "1234")]
# Exercise minimal-integer S encoding: find a fixed seed whose S needs <384 bytes.
for index in range(1, 2000):
    seed = hashlib.sha256(f"short-S-{index}".encode()).digest()
    case = srp_case(seed, bytes.fromhex("00112233445566778899aabbccddeeff"), "0420")
    # Recover the premaster solely to select the regression vector, not to publish it.
    context = SRPContext("Pair-Setup", "0420", prime=constants.PRIME_3072,
                         generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
    secret = context.get_client_premaster_secret(context.get_common_password_hash(bytes.fromhex(case["salt"])),
        int(case["serverPublic"], 16), int.from_bytes(seed),
        context.get_common_secret(int(case["serverPublic"], 16), int(case["public"], 16)))
    if secret.bit_length() <= 3064:
        srp.append(case)
        break
else:
    raise AssertionError("No short-S vector found")

client = X25519PrivateKey.from_private_bytes(bytes(range(32)))
server = X25519PrivateKey.from_private_bytes(bytes(range(32, 64)))
shared = client.exchange(server.public_key())
assert shared == server.exchange(client.public_key())
labels = [("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", bytes.fromhex(srp[0]["key"])),
          ("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", bytes.fromhex(srp[0]["key"])),
          ("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", bytes.fromhex(srp[0]["key"])),
          ("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared),
          ("", "ClientEncrypt-main", shared), ("", "ServerEncrypt-main", shared)]
hkdf = [{"salt": salt, "info": info, "secret": secret.hex(),
         "key": hkdf_expand(salt, info, secret).hex()} for salt, info, secret in labels]

signing = Ed25519PrivateKey.from_private_bytes(bytes(range(1, 33)))
message = raw_public(server) + b"synthetic-apple-tv" + raw_public(client)
ed = {"seed": bytes(range(1, 33)).hex(), "public": raw_public(signing).hex(),
      "message": message.hex(), "signature": signing.sign(message).hex()}

out_key, in_key = bytes.fromhex(hkdf[-2]["key"]), bytes.fromhex(hkdf[-1]["key"])
payload = opack.pack({"_i": "_hidC", "_x": 42, "_t": 2, "_c": {"_hBtS": 1, "_hID": 1}})
header = b"\x08" + (len(payload) + 16).to_bytes(3, "big")
transport = []
for counter in (0, 1, 256, 2**64):
    cipher = Chacha20Cipher(out_key, in_key, nonce_length=12)
    cipher._out_counter = counter
    cipher._in_counter = counter
    peer = Chacha20Cipher(in_key, out_key, nonce_length=12)
    peer._out_counter = counter
    encrypted = cipher.encrypt(payload, aad=header)
    incoming = peer.encrypt(payload, aad=header)
    assert cipher.decrypt(incoming, aad=header) == payload
    transport.append({"nonce": counter.to_bytes(12, "little").hex(), "ciphertext": encrypted.hex(),
                      "incoming": incoming.hex()})

pairing = []
for label, key in (("PS-Msg05", bytes.fromhex(hkdf[2]["key"])),
                   ("PS-Msg06", bytes.fromhex(hkdf[2]["key"])),
                   ("PV-Msg02", bytes.fromhex(hkdf[3]["key"])),
                   ("PV-Msg03", bytes.fromhex(hkdf[3]["key"]))):
    cipher = Chacha20Cipher8byteNonce(key, key)
    encrypted = cipher.encrypt(message, nonce=label.encode())
    assert cipher.decrypt(encrypted, nonce=label.encode()) == message
    pairing.append({"nonce": label.encode().hex(), "key": key.hex(), "ciphertext": encrypted.hex()})

# Codec vectors: the Kotlin test rebuilds these exact values and compares bytes both ways.
codec_values = {
    "hid": {"_i": "_hidC", "_t": 2, "_c": {"_hBtS": 1, "_hidC": 5}, "_x": 12345},
    "systemInfo": {"_i": "_systemInfo", "_t": 2, "_c": {
        "_bf": 0, "_cf": 512, "_clFl": 128, "_i": "cafecafecafe", "_idsID": b"4d797fd3-3538-427e-a47b-a32fc6cf3a6a",
        "_pubID": "AA:BB:CC:DD:EE:FF", "_sf": 256, "_sv": "170.18", "model": "iPhone10,6", "name": "Kaukosaadin"},
        "_x": 65536},
    "references": {"a": "same-string", "b": "same-string", "c": ["same-string", bytes(40), bytes(40)], "d": {"_hidC": 7}},
    "endless": {f"k{i:02d}": i for i in range(16)},
    "scalars": [None, True, False, 0x27, 0x28, 0xFF, 0x100, 0x10000, 0x100000000, 1.5, "x" * 40, bytes(range(256)) + bytes(44)],
    "sessionStop": {"_sid": (0xFFFFFFFF << 32) | 0x12345678},
}
codec = {"opack": {name: opack.pack(value).hex() for name, value in codec_values.items()},
         "tlv8": write_tlv({TlvValue.SeqNo: b"\x05", TlvValue.EncryptedData: bytes(range(256)) + bytes(44),
                            TlvValue.Name: opack.pack({"name": "Kaukosaadin"})}).hex()}
for name, value in codec_values.items():
    assert opack.unpack(bytes.fromhex(codec["opack"][name]))[0] == value

# RTI keyboard: the Kotlin codec must read pyatv's focus archive and write payloads pyatv reads back.
rti_uuid = bytes(range(16))
rti_text = "Hello, tvOS! \u00e4"
rti_focus = plistlib.dumps(
    {"$top": {"sessionUUID": plistlib.UID(1), "documentState": plistlib.UID(2)},
     "$objects": ["$null", rti_uuid, {"docSt": plistlib.UID(3)},
                  {"contextBeforeInput": plistlib.UID(4)}, rti_text]},
    fmt=plistlib.PlistFormat.FMT_BINARY, sort_keys=False)
assert keyed_archiver.read_archive_properties(
    rti_focus, ["sessionUUID"], ["documentState", "docSt", "contextBeforeInput"]) == (rti_uuid, rti_text)
rti = {"uuid": rti_uuid.hex(), "text": rti_text, "focus": rti_focus.hex(),
       "clear": get_rti_clear_text_payload(rti_uuid).hex(),
       "input": get_rti_input_text_payload(rti_uuid, rti_text).hex()}

result = {"revision": REVISION, "srpPrime": constants.PRIME_3072, "srpGenerator": constants.PRIME_3072_GEN,
          "srp": srp, "ed25519": ed,
          "x25519": {"seed": bytes(range(32)).hex(), "public": raw_public(client).hex(),
                     "peer": raw_public(server).hex(), "shared": shared.hex()},
          "hkdf": hkdf, "pairing": pairing,
          "codec": codec, "rti": rti,
          "transport": {"outKey": out_key.hex(), "inKey": in_key.hex(), "header": header.hex(),
                        "payload": payload.hex(), "frames": transport}}
output = Path(__file__).resolve().parents[1] / "app/src/debug/assets/companion-crypto-vectors.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, indent=2) + "\n")
print(f"Wrote {output}")
