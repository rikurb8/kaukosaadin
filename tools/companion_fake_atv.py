#!/usr/bin/env python3
"""Host-only interop peer: pinned pyatv's fake Companion Apple TV on 127.0.0.1.

Usage (same venv as generate_companion_vectors.py): companion_fake_atv.py <pyatv checkout>
Prints "PORT <n>", then "PAIRED", "SESSION <srvT>" and "BUTTON <name>" as the client acts.
The fake's PIN is 1111. Exits when stdin closes. No real device or LAN traffic.
"""
import asyncio
from pathlib import Path
import subprocess
import sys

REVISION = "b277a4c8222ecdcbaab8a24e3e713ca44765adb4"
reference = Path(sys.argv[1]).resolve()
assert subprocess.check_output(["git", "-C", str(reference), "rev-parse", "HEAD"], text=True).strip() == REVISION
sys.path.insert(0, str(reference))

import binascii  # noqa: E402

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PublicKey  # noqa: E402
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat  # noqa: E402
from pyatv.auth.hap_srp import hkdf_expand  # noqa: E402
from pyatv.auth.hap_tlv8 import ErrorCode, TlvValue, read_tlv, write_tlv  # noqa: E402
from pyatv.const import KeyboardFocusState  # noqa: E402
from pyatv.protocols.companion.connection import FrameType  # noqa: E402
from pyatv.support import chacha20  # noqa: E402
from tests.fake_device.companion import (  # noqa: E402
    FakeCompanionService,
    FakeCompanionState,
    FakeCompanionUseCases,
)


def report(line):
    print(line, flush=True)


class ReportingService(FakeCompanionService):
    """Adds the client-signature checks a real Apple TV performs but the upstream fake skips."""

    def _m5_setup(self, pairing_data):
        key = binascii.unhexlify(self.session.key)
        session_key = hkdf_expand("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", key)
        controller_x = hkdf_expand("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", key)
        tlv = read_tlv(chacha20.Chacha20Cipher(session_key, session_key).decrypt(
            pairing_data[TlvValue.EncryptedData], nonce=b"PS-Msg05"))
        client_id, client_ltpk = tlv[TlvValue.Identifier], tlv[TlvValue.PublicKey]
        Ed25519PublicKey.from_public_bytes(client_ltpk).verify(
            tlv[TlvValue.Signature], controller_x + client_id + client_ltpk)
        self.state.client = (client_id, client_ltpk)
        super()._m5_setup(pairing_data)

    def _m1_verify(self, pairing_data):
        self._client_pub = pairing_data[TlvValue.PublicKey]
        shared = self.keys.verify.exchange(X25519PublicKey.from_public_bytes(self._client_pub))
        self._pv_key = hkdf_expand("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        super()._m1_verify(pairing_data)

    def _m3_verify(self, pairing_data):
        server_pub = self.keys.verify_pub.public_bytes(encoding=Encoding.Raw, format=PublicFormat.Raw)
        try:
            tlv = read_tlv(chacha20.Chacha20Cipher(self._pv_key, self._pv_key).decrypt(
                pairing_data[TlvValue.EncryptedData], nonce=b"PV-Msg03"))
            client_id, client_ltpk = getattr(self.state, "client", (None, None))
            assert tlv[TlvValue.Identifier] == client_id
            Ed25519PublicKey.from_public_bytes(client_ltpk).verify(
                tlv[TlvValue.Signature], self._client_pub + client_id + server_pub)
        except Exception:
            report("VERIFY_REJECTED")
            self.send_to_client(FrameType.PV_Next, {"_pd": write_tlv(
                {TlvValue.Error: bytes([ErrorCode.Authentication]), TlvValue.SeqNo: b"\x04"})})
            return
        report("VERIFIED")
        super()._m3_verify(pairing_data)

    def has_paired(self):
        super().has_paired()
        report("PAIRED")

    def handle__sessionstart(self, message):
        super().handle__sessionstart(message)
        report(f"SESSION {self.state.service_type}")

    def handle__hidc(self, message):
        self.state.latest_button = None
        super().handle__hidc(message)
        if self.state.latest_button:
            report(f"BUTTON {self.state.latest_button}")

    def handle__tistart(self, message):
        super().handle__tistart(message)
        if self.state.rti_session_uuid is not None:
            report("TEXT_SESSION")

    def handle__tic(self, message):
        super().handle__tic(message)
        if message["_t"] == 1:
            report(f"TEXT {self.state.rti_text}")


async def read_commands(usecases):
    """Drive focus changes from stdin so the test can exercise the pushed RTI events."""
    loop = asyncio.get_running_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            return
        command = line.strip()
        if command == "focus on":
            usecases.set_rti_focus_state(KeyboardFocusState.Focused)
            report("FOCUS on")
        elif command == "focus off":
            usecases.set_rti_focus_state(KeyboardFocusState.Unfocused)
            report("FOCUS off")


async def main():
    state = FakeCompanionState()
    usecases = FakeCompanionUseCases(state)
    loop = asyncio.get_running_loop()
    server = await loop.create_server(lambda: ReportingService(state), "127.0.0.1", 0)
    report(f"PORT {server.sockets[0].getsockname()[1]}")
    await read_commands(usecases)
    server.close()


asyncio.run(main())
