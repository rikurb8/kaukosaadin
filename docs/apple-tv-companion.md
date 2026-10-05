# Apple TV Companion proof — GOO-27

## Current boundary

**Apple TV remote in the main app; pairing and Home/Menu verified on the real Apple TV.** Host
checks, Android packaging and lint pass, and the required S25 crypto gate **passed
6/6 on hardware** (re-run 2026-10-04 after the PIN fix below). Android NSD discovery
resolves the Apple TV's advertised host/port. Framing, OPACK, TLV8, PIN pair-setup,
pair-verify, Keystore credential storage, session startup and HID presses (Menu/Back,
Home/TV, arrows, Select) are implemented and pass against pinned pyatv's fake Apple TV,
and pairing + Home/Menu tap, double tap and hold were operator-confirmed on the real
Apple TV, from the host JVM and from the S25 app (2026-10-04, below); arrows, Select and
Play/Pause were operator-confirmed from the integrated main remote the same day. Phone-typed
text is mirrored to the Apple TV's on-screen keyboard with focus-driven auto-open; that path
passes the fake peer but is **not** real-device-verified. Wake is not implemented. The main remote's **LG TV / Apple TV**
switch drives the saved Apple TV; LG behavior is unchanged. The crypto gate never uses the
LAN; the separate discovery screen only scans services. Neither accesses saved LG data.

## Pinned reference and dependency decision

- [pyatv](https://github.com/postlund/pyatv/tree/b277a4c8222ecdcbaab8a24e3e713ca44765adb4):
  `b277a4c8222ecdcbaab8a24e3e713ca44765adb4`, release 0.18.0, commit dated
  2026-06-19. Active upstream with Companion implementation/tests; reverse-engineered,
  **not an Apple specification**. No protocol selection reopening or Python in the app.
- `org.bouncycastle:bcprov-jdk18on:1.86`: Java 8+ lightweight crypto API, MIT-style
  license. Maven Central metadata listed 1.86 as latest/release (updated 2026-09-11);
  release source/license reviewed under tag `r1rv86`. JCA has no SRP primitive; use
  BC SRP core and the same library's raw Ed25519/X25519, HKDF-SHA-512 and AEAD primitives.
  Do not implement custom cryptographic primitives or register/replace a global provider.
  Java `SecureRandom` supplies seeds and SRP exponent blinding.
- Host reference tools: Python 3.12, `srptools==1.0.1`, `cryptography==50.0.2`,
  `chacha20poly1305-reuseable==0.13.2`. Only vector generation uses Python.
- Full pyatv, srptools and BC notices ship in `app/src/main/assets/licenses/`.
  Android debug/release builds package BC without duplicate classes or packaging
  exceptions. **Packaging is not Android runtime compatibility evidence.**

Reusable crypto: `app/src/main/java/fi/goodconsulting/kaukosaadin/device/companion/CompanionCrypto.kt`.
Synthetic checks/runner/assets are under `app/src/debug/`; the one JUnit entry point
is under `app/src/testDebug/`. Release excludes both proof activities and fake-key vectors.

### Wire details and upstream traps

- SRP-6a: RFC 5054 3072-bit group, generator 5, SHA-512, identity `Pair-Setup`.
  The SRP password is the **displayed four digits, leading zeroes kept**: pyatv's
  `CompanionPairingHandler.pin()` zero-fills before `step1`. (An earlier revision
  normalized `0420` to `420`, which would fail every PIN starting with 0.) The vectors
  include displayed `0420`.
- BC's generic SRP M1/M2 and session-key methods are **not** HAP's formats. K hashes
  the minimal unsigned S; M1 uses the reference's H(N) XOR H(g), H(identity), salt,
  minimal A/B and K. A short-S and leading-zero-salt vector catch padding mistakes.
- Verify the **received** M4 server proof in constant time before exposing K; failure
  consumes the attempt and wipes its candidate key. The pinned Companion procedure
  reads the received proof but never verifies it; `hap_srp.step2` checks its own
  calculated proof instead. Do not port that behavior.
- Pinned `hap_srp.step4` also leaves the accessory setup signature unverified.
  The full handshake must verify the accessory-sign HKDF + accessory identifier +
  accessory public key before saving credentials. Signing/verification primitives and
  HKDF label vectors are present, but the handshake is not yet implemented.
- Pinned pair-verification ignores the final status. Check final sequence/error/status,
  peer identifier and signature before enabling encryption; do not treat a reply as success.
- Pairing uses four zero bytes followed by an 8-byte ASCII nonce (`PS-Msg05/06`,
  `PV-Msg02/03`). Companion transport instead uses a **12-byte little-endian counter**,
  starts each direction at zero, derives output with `ClientEncrypt-main`, input with
  `ServerEncrypt-main`, empty salt, and authenticates the four-byte frame header
  (length includes the 16-byte tag). Do not reuse HAP's 8-byte transport counter.
  Checks include counters 0/1/256 and a raw vector at 2^64.
- A session copies keys, keeps independent counters, rejects use after failure/close,
  and wipes its copies on close. A new verified connection needs fresh exchange keys.
  Authentication failures must never enable a plaintext fallback or replay navigation.

## Checks and results

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:assembleRelease
```

Original crypto-only host result: **PASS**, seven JUnit tests total, zero failures/errors. The single
Companion JUnit test executes the same six groups as the Android runner:

1. SRP public/M1/K/M2 reference bytes, short-S/leading zeroes, wrong PIN, invalid public
   keys and proofs, and no second attempt after acceptance/rejection.
2. Ed25519 raw public/signature bytes and invalid signatures/messages/lengths.
3. X25519 raw agreement and low-order/invalid-length public keys.
4. All setup/verify/direction HKDF salts and labels.
5. Pairing nonce padding and authenticated-ciphertext/nonce tampering rejection.
6. Transport AAD, 12-byte nonce encoding, opposite key directions, independent counters,
   truncated/tampered messages and unusable sessions after rejection/close.

JDK 21.0.12.1, Gradle 9.7.1, AGP 9.4.1; debug and unsigned release APK builds pass.
Built (not installed) debug APK SHA-256:
`cca5c4167cd78a6fc352ee43b6f6cc5368eed586e2b185f0792e7683223cf1da`.
Vector regeneration was checked for byte-identical output.
Lint passes with existing project warnings and no Companion-source warnings. Release archive inspection confirmed no
crypto activity/vector asset, and packaged license notices in both APKs.
`:app:testReleaseUnitTest` is not exposed by this project's AGP defaults; do not cite
it as passed. The crypto check is deliberately debug-only.

### Regenerate cross-language vectors

No real device identifiers, credentials or network traffic are involved. From repo root:

```bash
REF="$(mktemp -d)/pyatv"
git clone https://github.com/postlund/pyatv.git "$REF"
git -C "$REF" checkout --detach b277a4c8222ecdcbaab8a24e3e713ca44765adb4
VENV="$(mktemp -d)/vectors"
uv venv --python 3.12 "$VENV"
uv pip install --python "$VENV/bin/python" -e "$REF" \
  srptools==1.0.1 cryptography==50.0.2 chacha20poly1305-reuseable==0.13.2
"$VENV/bin/python" tools/generate_companion_vectors.py "$REF"
./gradlew :app:testDebugUnitTest
```

The generator checks the checkout SHA, import path and crypto tool versions, exercises
both reference SRP roles, and writes deterministic synthetic fixtures to
`app/src/debug/assets/companion-crypto-vectors.json`. It does not include Python in the APK.

## S25 gate (no TV actions) — PASSED 2026-10-03

Result on the attached S25: overall **6/6 PASS**, all six groups displayed PASS, Maestro
helper `companion-crypto.yaml` green. The 6/6 result proves these byte formats execute
correctly on Android 16/API 36, **not** pairing or TV control.

| Field | Value |
| --- | --- |
| Device | samsung SM-S931B (`R3GL204147Z`) |
| Android | 16 / API 36, build `BP4A.251205.006.S931BXXSCCZH1` |
| Installed APK SHA-256 | `cca5c4167cd78a6fc352ee43b6f6cc5368eed586e2b185f0792e7683223cf1da` (matches the host build above) |
| Groups | SRP-3072/SHA-512 + server proof, Ed25519, X25519, HKDF-SHA-512, ChaCha20-Poly1305 pairing nonces, transport 12-byte LE nonces/AAD/counters — all PASS |
| Install | `adb install -r`, data preserved |

Re-running it: connect the S25 over USB, authorize debugging, unlock it, then explicitly
select its serial. Preserve app data; do not reinstall with uninstall/clear-state.

```bash
adb devices -l
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n fi.goodconsulting.kaukosaadin/.CompanionCryptoActivity
# Tap “Run crypto checks”, or use the existing Maestro workspace:
"$HOME/.maestro/bin/maestro" test --device <serial> .maestro/helpers/companion-crypto.yaml
adb -s <serial> shell am start -n fi.goodconsulting.kaukosaadin/.MainActivity
```

Record APK hash, phone/model, Android build, each displayed group and overall result.
The optional Maestro helper assumes the gate activity has already been started; it
uses text selectors and a bounded wait, never coordinates. It **passed** on the run above.
A pass proves these byte formats on that phone, **not** pairing or TV control.
A failure blocks further client work: diagnose the failing crypto group first.

## Read-only Android discovery — implemented and checked on S25

`CompanionDiscovery.kt` uses native `NsdManager` for `_companion-link._tcp` without a
new dependency. A bounded six-second scan retains the resolved `InetAddress` (including
IPv6 scope) and actual advertised port; it never guesses port 49153. It serializes
native resolutions, limits services/events, removes lost services, ignores stale
resolutions and stops discovery on completion/cancellation. API 34+ also stops pending
resolution; API 29–33 cannot cancel native resolution, so late callbacks are discarded.
Service names are sanitized for display; wildcard, loopback and multicast endpoints
and invalid ports are rejected. Advertisements establish neither identity nor trust;
Companion services can belong to Macs and other devices, not just Apple TVs.

The debug-only `CompanionDiscoveryActivity` exposes Scan/Cancel and host/port results.
There is no automatic scan, TCP connection, credential access, pairing or TV command.
The main app's Apple TV setup screen uses the same scan.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n fi.goodconsulting.kaukosaadin/.CompanionDiscoveryActivity
"$HOME/.maestro/bin/maestro" test --device <serial> .maestro/helpers/companion-discovery.yaml
adb -s <serial> shell am start -n fi.goodconsulting.kaukosaadin/.MainActivity
```

Current discovery-build checks: **PASS**, eight JUnit tests, zero failures/errors,
including endpoint validation/name sanitization and preservation of a non-default port
and scoped IPv6 address. Debug/release builds and lint pass, with no discovery-source
warnings. Release manifest inspection contains neither proof activity.

Installed with `adb install -r` on the same S25/Android 16 device described above,
preserving app data. Debug APK SHA-256:
`10467d0e014b4747a20fcc1ca88250a6c0116bc50f1a2cf77bea5a0b67e43ac7`.
Maestro discovery helper **PASS**: cancel one scan, then complete two scans without
error. Crypto helper re-run on this APK **6/6 PASS**; existing main-remote smoke
**1/1 PASS**. Returned to MainActivity afterward; no TV commands were sent.
Final discovery UI showed three Companion services, including `Entertainment Room` at
192.168.1.40, advertised port 49153, matching the earlier LAN observation.
This is discovery evidence only, not authenticated Apple TV identity or control.
API 29–33 runtime behavior, absent-device/network-denial cases and service-loss
handling have not been exercised on hardware; no compatibility claim for those cases.

## Pairing, verify and proof remote — implemented, fake-peer checked

`Opack.kt`/`Tlv8.kt` encode byte-identically to pinned pyatv (codec vectors in the same
JSON). `CompanionLink.kt` is one blocking TCP connection: 4-byte frames (64 KiB cap,
deadline-bounded reads), PS M1–M6 and PV M1–M4, then `_systemInfo`, `_sessionStart`
and `_hidC` press/release (each awaits its `_x` response; `_em`
fails). Unlike the reference it verifies the M4 server proof, the M6 accessory
signature and identifier, and the PV M4 status. Any failure closes the link.
`CompanionClient.kt` stores pyatv-format credentials AES-GCM-wrapped under Keystore
alias `companion-pairing` (separate from LG) and reuses one verified Companion session.
Opening or returning to the Apple TV remote preconnects while the activity is resumed;
leaving the remote or backgrounding closes the connection after any in-flight operation.
Pairing/forgetting and command failures also discard the session. The next press can
connect again, but failed presses are never replayed or queued. There is no session setting.
Persistent-session lifecycle behavior still needs a real-device check. HID codes: Up 1, Down 2, Left 3, Right 4, **Menu (Back) 5**,
Select 6, **Home (TV) 7**, PlayPause 14 (pinned pyatv `play_pause` sends this HID press). Press actions follow pyatv `_press_button`: tap = down/up,
double tap = two down/up pairs on the same connection, hold = down, 1 s, up (release
always attempted). The debug screen maps tap/double-tap/long-press on Menu and Home to
these; operator-confirmed on the real Apple TV from the S25 (see below).

The main app's Apple TV setup (`ui/AppleTvSetupScreen.kt`) scans, pairs with the TV's PIN
and forgets; the main remote's Apple TV target exposes BACK (Menu), HOME (TV), Play/Pause,
arrows and OK (Select), with double tap/hold on BACK and HOME. Play/Pause has no
fake-peer test; it is operator-confirmed on the real Apple TV (below). It replaced the debug-only
`CompanionRemoteActivity` and reuses its `companion` prefs and Keystore alias, so an
existing pairing carries over.

Interop check against pyatv's fake Companion Apple TV (`tools/companion_fake_atv.py`,
which also verifies **our** M5/PV-M3 signatures like a real TV), using the venv from
"Regenerate cross-language vectors":

```bash
COMPANION_PYATV_PYTHON="$VENV/bin/python" COMPANION_PYATV_REF="$REF" \
  ./gradlew :app:testDebugUnitTest
```

Result 2026-10-04: **15 tests, 0 skipped, 0 failures** (pair → verify → Menu and Home
acknowledged; wrong PIN rejected and not retryable; wrong device/key rejected; revoked
pairing fails closed). Without the env vars the 3 interop tests are skipped.
S25 debug APK `d89bbf3fabd435f62c3ff2d11ce8e11c3c335a86b5a37ed4198fe6401e5ee46d`:
crypto gate 6/6 PASS, remote screen scan found `Entertainment Room` at 192.168.1.40.

### Real Apple TV check — 2026-10-04 (host JVM, operator-confirmed)

The S25 dropped off adb, so the same `CompanionLink` code ran from the Mac mini on the
same LAN through a temporary, since-deleted JUnit probe, against `Entertainment Room`
(Apple TV 4K `AppleTV6,2`, tvOS 26.6) at 192.168.1.40:49153:

| Step | Result |
| --- | --- |
| PS M1–M6 with the TV-displayed PIN | PASS: M4 proof and M6 accessory signature verified |
| Separate pair-verify | PASS |
| `_systemInfo` + `_sessionStart` + Home press/release | Acknowledged; ~130 ms connect → ack |
| Same for Menu | Acknowledged; ~60 ms connect → ack |
| Physical result | Operator confirmed: TV went to Home, then reacted to Menu |

The two-request session startup is sufficient for HID; `_touchStart`/`TVRCSessionStart`
were not needed.

Phone path, same day: debug APK
`f9ad143c7f7e61d1220d48a939708bdf5546991896e4e4e7e0d9eb24ca545926` installed on the S25
with `adb install -r`. Operator scanned, paired with the TV PIN in
`CompanionRemoteActivity` (Keystore-stored credentials), then tried tap, double tap and
long press on Menu (Back) and Home (TV): reported **working**. Per-gesture on-screen
results were not individually recorded.

Not yet exercised: sleep/wake, restart reconnect, revoked pairing on real
hardware. The probe's pairing keys were deleted; the
TV still lists that "Kaukosaadin" remote until removed in Settings › Remotes and Devices.

Main-app integration, 2026-10-04: debug APK installed on the S25 with `adb install -r`;
smoke flow PASS; the Apple TV target picked up the existing pairing and showed READY.
Operator then used the integrated remote on the real Apple TV (debug APK
`4987ad17f96ccf0a298ca09733a90b82e3ba42cb637661843adb12349175a8bd`): arrows, OK (Select)
and Play/Pause reported **working**. Per-key on-screen results were not individually recorded.

## RTI keyboard text input and focus — implemented, fake-peer checked

`CompanionLink` runs a background reader thread once pair-verify succeeds: responses are
correlated by `_x` and pushed OPACK events (`_t=1`) are no longer discarded. Session startup
also sends `_tiStart` (pinned pyatv `CompanionAPI._text_input_start`), and `_tiStop` is sent
best-effort on teardown (pyatv `CompanionAPI.disconnect`).

- `_tiStarted`/`_tiStopped` push the tvOS on-screen-keyboard focus. The archive in
  `_tiStarted._c._tiD` carries the RTI session UUID and the keyboard's current text; the
  `_tiStart` reply carries the same archive when the keyboard was already focused before we
  connected (pyatv's own comment: `_tiStarted` is not sent in that case). Both paths feed
  `CompanionClient.keyboard`, which drives `AppleTvKeyboardDialog` in `ui/AppleTvSetupScreen.kt`:
  the field and phone keyboard open on focus, edits are debounced, and dismissal follows
  `_tiStopped`.
- `NskArchiver.kt` reads and writes the NSKeyedArchiver binary plist that carries those
  payloads. Python's `plistlib` does not exist on Android, so it is a bounded from-scratch
  bplist00 codec: it follows `$top` UID paths like pyatv `read_archive_properties`, and writes
  pyatv's two fixed RTI templates (`rti_text_operations.py`). Unlike the crypto vectors, the
  written bytes need not be byte-identical to pyatv's, only plistlib-readable.
- Every edit is a *set*: one clear (`textToAssert: ''`) then one insert. `_tiC` is an event,
  so the TV sends no per-keystroke acknowledgment; the app reuses the UUID captured when
  focus arrived and never retries silently. `CompanionClient.sendText` waits for the session
  lock instead of dropping edits, and any link failure closes the session like every other
  operation. 1 s teardown timeouts keep `_tiStop`/`_sessionStop` from delaying a disconnect
  on a device that ignores them.

Checks: `./gradlew :app:testDebugUnitTest` with the fake-peer env vars — **26 tests, 0 skipped,
0 failures**. `CompanionArchiverTest` reads the Python-generated focus archive and asserts our
clear/insert payloads decode back to the same UUID, empty text and full text;
`CompanionInteropTest.keyboardFocusAndTextMirrorToPeer` drives the fake through `_tiStart`, a
typed edit and `_tiStopped`/`_tiStarted`, with pyatv's `keyed_archiver` reading our plist back
into the fake keyboard. `tools/generate_companion_vectors.py` emits the new `rti` vectors;
regeneration is byte-identical for the existing crypto vectors.

**Not verified:** nothing here has run against a real Apple TV. tvOS 26.6 may use a different
RTI payload version or rotate the session UUID mid-focus, and the fake only proves
pinned-pyatv compatibility. Auto-open, the clear+insert round trip and the focus events all
need a supervised real-device check before this is claimed working.

## Remaining proof sequence after the on-device gate

1. Record actual Apple TV model/tvOS, LG model/webOS, trusted home-LAN layout/router
   isolation and CEC/wake settings. Observed 2026-10-03 from the host LAN (mDNS +
   pyatv scan): Apple TV 4K (`AppleTV6,2`), tvOS 26.6, `Entertainment Room`,
   192.168.1.40, Companion on 49153 with **mandatory pairing and no stored
   credentials**; Mac mini on the same `192.168.1.0/24`. Companion drops off 49153 in
   deep sleep, so reachability checks are not stable evidence. LG model/webOS and
   CEC/wake settings are still unrecorded; old LG observations are not new Apple TV evidence.
2. Android NSD for `_companion-link._tcp` is implemented and passed S25 discovery,
   cancel and repeated-scan checks above, retaining resolved host **and advertised
   port**. Integrate it into the minimal pairing UI after the protocol is implemented.
   Follow target-36 LAN permissions in README; no target upgrade.
3. Add bounded codec/framing/correlation, fragmented/coalesced reads, timeouts and
   off-main-thread cleanup, checking malformed/oversized input against pinned fixtures.
4. Minimal proof UI: actual discovered devices, PIN and actionable errors, protected
   saved credentials, forget/re-pair, restart pair-verification. Reuse the LG Android
   Keystore wrapping pattern, with separate Apple TV storage/key alias and no backup,
   secret logging, plaintext fallback or endless PIN retry.
5. Follow reference system-info/session startup and press/release/Wake behavior; six
   buttons, no generic framework, bridge or final UI work. Never replay queued navigation.
6. Supervised hardware evidence: repeated sustained-sleep wake, restart reconnect,
   up/down/left/right/select/back, forget/re-pair, missing/revoked pairing and network
   failure, with timings and exact installed build.

### Safe hardware recipe to finish once controls exist

The current gate cannot execute this recipe. Ask the operator to prepare the Apple TV
Home screen, with a harmless tile selected, and supervise every physical action. Use
single reversible arrow pairs; only select/back inside an operator-chosen harmless app.
Never blindly select purchases, installs or settings. Prepare standby manually, record
sleep duration, issue one wake, time the physical result, then reconnect and repeat.
Do not add power-off to automate standby. Wrong-PIN/revocation/network-disruption cases
are separately assisted and must restore saved setup/network afterward.

For CEC, separately prepare LG standby, record CEC settings, wake Apple TV and observe
LG power/input physically. Distinguish request accepted, Apple TV visibly awake,
LG visibly awake and LG input selected. A Companion acknowledgment proves none of
those physical outcomes. Apple TV wake must remain independent of direct LG control.

## Integration recommendation

The client is now integrated into the main remote (see above). Remaining before closing
GOO-27: the supervised hardware evidence in step 6 (restart reconnect,
forget/re-pair, revoked pairing, network failure) from the integrated remote, plus wake.
Text input additionally needs a supervised real-device check: focus auto-open, typing,
backspace/paste and dismissal on tvOS 26.6.
