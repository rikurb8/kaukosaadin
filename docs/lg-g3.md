# GOO-26 — LG G3 client and verification

## Saved devices

LG TVs are now saved devices alongside Apple TVs (`device/SavedDevices.kt`): any number of
either, each with its own `lg-<id>` prefs. **Find devices** / **Add device** scans SSDP and Companion
together; tapping an LG TV inspects its certificate and shows one **Trust this TV** dialog
(name, SHA-256, **Trust & pair**), then the TV's PIN. **Device settings** holds rename, **Re-pair**,
wake settings and **Remove device**. The pre-rework single `lg` prefs are deleted, not migrated.
The sections below record the original single-TV build and its verification.

## Main remote integration

The operator has confirmed that the main LG remote works. Exact tested actions and
standby/recovery observations were not provided; build/UI tests do not prove TV movement.

Fresh installs show **No TVs added** and **Add TV**, with no remote or fixed device slots.
Setup retains discovery, manual IPv4, certificate approval, PIN entry and optional wake
settings. Selecting a discovery result fills **TV name** from its advertised name (or
“LG TV” if absent); the editable name is saved with approved setup. **Save TV name** updates
metadata without clearing trust/pairing. Existing installations keep their credentials
and use “LG TV” until a name is saved. Names are bounded/sanitized labels, not trusted identity.

Successful **Connect / pair LG** returns to the named main remote automatically. **Done**
also returns without connecting. **Connect TV** reconnects with saved trust/pairing, while
**TV settings** permits changing setup. **Remove TV** confirms before clearing the TV,
name, pairing and wake settings and returning to the empty state; Forget only clears
pairing/certificate. One TV is saved at a time; there is no speculative device framework.
The main arrows, OK (`ENTER`) and BACK use the same client, not simulated state.

Navigation requires successful registration in this app run and is disabled while busy,
after failed navigation, after changing/forgetting saved trust, and after Wake until
explicit Connect. No navigation is queued or replayed. READY and the LG LED indicate
registration, not monitored power or a persistent connection. The LG power key only
sends Wake-on-LAN when wake settings are saved; it does not toggle standby. Apple TV
controls and the hard-coded source selector have been removed. No Apple TV connection
or HDMI-CEC is used by LG wake.

Smoke checks disabled controls and setup/back navigation without changing pairing or
sending TV commands. The discovery helper checks setup scanning and manual fallback.

Integration validation: unit tests, debug APK build, lint, updated Maestro smoke and
LG discovery helper all PASS. Installed on the connected Galaxy S25 with `adb install -r`,
preserving app data. Confirmed main-page layout and Android Back returning from setup.
No PIN was submitted, saved trust changed, wake sent or navigation sent during these
checks. The operator subsequently reported that the main controls work; exact action
observations and wake/recovery behavior are still not recorded.

Named-TV validation: build, unit tests (including name sanitization), lint, updated smoke
and discovery/name-entry helper PASS on the Galaxy S25. An isolated disposable application
ID verified the fresh empty state, saved-name display, rename persistence across restart,
and Remove cancellation/confirmation followed by the persistent empty state. Synthetic
pairing/certificate/address/wake values were unchanged by rename and cleared only by
confirmed removal. The test app was uninstalled; the normal APK was rebuilt and installed
with `adb install -r`, preserving the operator's real pairing/setup. No TV commands or
pairing requests were sent. APK SHA-256:
`02ebacd93cb514a5c4ab14f1cabe4bd1f1bdab2a1a364102ab666b8a393bb5e9`.

## Client behavior

`device/LgClient.kt` (one instance per saved TV id) exposes `status: StateFlow<Result>` and suspend
`save`, `inspect`, `pair`, `connect`, `send(Action)`, `forget` methods. `ready: StateFlow<Boolean>` tracks successful
registration for the main control gate (not TV power). Names live in `DeviceStore`, not the client;
`delete()` clears all of this TV's settings. `save(address, fingerprint)` is independent of wake; `saveWake(address, mac, broadcast)` configures only Wake-on-LAN. PIN input uses
`awaitingPin`, `submitPin(code)` and `cancelPairing()` on the same registration connection. `Result.ok` means the operation completed,
NOT that the TV woke or moved selection. Call from a lifecycle-owned coroutine.
Networking runs on Dispatchers.IO. Concurrent operations are refused, not queued;
navigation is never automatically retried. A fresh bounded TLS session is used for
each navigation operation, preserving ordering without replaying stale input.
Connections are cancelled/closed on every path. Explicit Connect pairing gets 30 seconds
after the WebSocket opens; navigation registration, connect, pointer response and pointer
close waits each get 5 seconds. If PIN is requested, entry gets 90 seconds and registration
gets another 5 seconds after submission. A pairing challenge during navigation discards
the command; use Connect to pair, then press the intended action again.

Pairing material is AES-GCM encrypted with an Android Keystore key in app-private
preferences. Backups and shared-preference device transfer are disabled. Pairing keys
and server error bodies are never displayed or logged. Failed decrypt requires Forget,
not silent fresh pairing. Host/certificate changes clear the saved key. An address
change needs rediscovery/selection and re-pairing; use a router DHCP reservation.
The picker does not overwrite saved setup or pairing merely because a scan finds a TV.
Changing the draft target clears its draft wake/trust details and disables commands
until that target's setup is explicitly saved.

## Dependency evaluation

Evaluated [audev482/lgtv-kotlin](https://github.com/audev482/lgtv-kotlin) source:
`LGTVRemote.pressButton`, `SSAPSession`, `WebSocketClient`, and its README. Its
pointer mapping and WSS/UDP split informed this client. Did **not** adopt it: its
WebSocket client accepts every certificate, persistence defaults to a desktop
`~/.config` directory, and its broad manifest asks for settings/service permissions
outside this scope. Its Android/G3 compatibility claim is not physical evidence.
The full client has not been tried on the G3. Follow-up reuses only its discovery source
(see below), not its TLS, persistence, HTTP enrichment or broad command surface.
Use Android-compatible OkHttp 4.12.0 rather than a custom WebSocket stack,
a minimal unsigned `CONTROL_MOUSE_AND_KEYBOARD` manifest, and PIN pairing.
Whether the real G3 accepts this minimal manifest/PIN method remains a validation risk.

PIN registration requests `pairingType: PIN`, including a saved client key when present.
A PIN challenge opens a masked code-entry dialog; the code is sent on the SAME WSS session
as a request to `ssap://pairing/setPin`. The client waits for `registered` with a client key
before declaring registration successful: `returnValue: true` alone is not pairing success.
The PIN is transient, never persisted/logged. Cancel, leaving the screen and timeout clear
the pending code and close the session; wrong PIN requires a fresh Connect attempt.
Saved pairing is reused without forcing a new PIN. A returned PROMPT method is explicitly
refused, not silently accepted as fallback.
Protocol reference: [ConnectSDK WebOSTVServiceSocketClient.sendPairingKey](https://github.com/ConnectSDK/Connect-SDK-Android-Core/blob/master/src/com/connectsdk/service/webos/WebOSTVServiceSocketClient.java).
The evaluated lgtv-kotlin revision requests PIN but throws `SSAPNeedsPinException` when
entry is needed; it does not implement code submission. We did not copy that incomplete flow.

TLS: only WSS port 3001, no fallback to port 3000/cleartext. Inspect performs a TLS
handshake that captures the leaf SHA-256 fingerprint then rejects it before sending
application credentials. The user must explicitly approve it. First trust on a LAN
cannot independently prove identity: verify the TV/router address and fingerprint
through a separate trusted source, not merely the same potentially hostile network.
The approved leaf pin substitutes for the TV's self-signed CA/hostname validation.
Certificate changes fail closed and require inspection/approval again. Pointer URLs
must be WSS, same configured host, port 3001, and have no user info/fragment. A TV
returning a WS pointer URL is refused; do not solve that by relaxing security.

## Upstream discovery source

`app/src/main/java/com/lgtvremote/discovery/TVDiscovery.kt` is adapted from
[audev482/lgtv-kotlin TVDiscovery.kt at 3bd6f623487b5e91fc4953666d41a32485101e4b](https://github.com/audev482/lgtv-kotlin/blob/3bd6f623487b5e91fc4953666d41a32485101e4b/src/main/kotlin/com/lgtvremote/discovery/TVDiscovery.kt).
Copied only `DiscoveredTV` / SSDP scanning and its response parser. The advertised
Maven artifact returns 404; JitPack reports no build artifacts for this revision.
The upstream source builds locally. No LICENSE file is present in that snapshot;
confirm upstream redistribution permission before distributing copied code externally.

Local changes: extract a testable parser; require a successful response with the exact
webOS second-screen service (not merely the substring `lge`); bound/sanitize display
names; use UTF-8; close the socket with `use`; honor a monotonic overall timeout; surface
network errors rather than silently treating them as an empty scan. `MulticastSocket`
TTL API works on our Android 29 floor, unlike upstream `DatagramSocket.setOption`.
TTL/MX are 1 for same-subnet discovery, keeping the three upstream search rounds.
A Wi-Fi multicast lock exists only during scanning. The normal scan deadline is 6 seconds.

Opening **Add device** automatically scans for awake TVs (`scanLg`); **Scan again** retries.
Selecting an untrusted advertisement only inspects its certificate for the trust dialog. Discovery cannot
prove identity and never pairs, approves a certificate or sends a TV command. Untrusted
LOCATION URLs are retained as metadata but never fetched. Sleeping devices may not
answer, so saved setup persists and **Enter LG TV address** remains available.
SSDP provides no MAC: the active-interface MAC/subnet broadcast remain operator input;
we did not copy insecure HTTP/WebSocket enrichment to eliminate those fields.

## Operator setup (record actual values privately)

1. Connect phone and powered TV to the same trusted IPv4 subnet. Disable client/AP
   isolation; check VPN routing, UDP broadcast filtering and TCP 3001 firewall rules.
2. Record G3 model/firmware/webOS version and phone model/Android API. None has been
   observed in this run. Record wired versus Wi-Fi and subnet mask; obtain numeric TV
   IPv4, active interface MAC and **subnet broadcast** from router/network settings.
   Use automatic discovery/device selection for IPv4, or manual fallback if needed.
   Hostnames are not accepted. Do not commit values.
3. On the LG, locate **TV On With Mobile / Turn on via Wi-Fi** (often under General →
   External Devices); record the exact menu path and actual enabled state. Check
   **LG Connect Apps** or equivalent network-remote authorization if present. Record
   **Quick Start+** / **Always Ready** state and verify whether each is required on this
   firmware/interface. These are prerequisites to investigate, not verified G3 requirements.
   Settings changes are operator-assisted and must be restored after the test.
4. With TV awake, select the discovered TV. Inspect, independently verify fingerprint,
   check certificate approval, **Save approved setup**, then **Connect / pair LG**. Enter
   the TV's displayed PIN in the phone dialog and tap **Submit PIN**. If no PIN method is
   offered, record the refusal; no physical-remote PROMPT fallback is attempted.
   MAC/broadcast are NOT needed for pairing/navigation. For wake separately enter
   MAC/subnet broadcast and tap **Save wake settings**. Save/Connect and wake each display
   operation feedback beside the relevant controls; invalid wake settings do not block pairing.
   If rejected/expired, retry Connect; for revoked credentials use Forget, inspect,
   approve, Save and Connect. If permission is blocked, restore it in Android settings.
5. Current targetSdk 36 requires INTERNET only under normal Android 16/17 behavior.
   Do not enable Android 16's opt-in RESTRICT_LOCAL_NETWORK flag for routine tests.
   Target 37 requires a separate manifest/runtime permission upgrade before release.

## Safe real-TV sequence

Use the physical LG remote to prepare Home with focus on a known inert tile, not a
purchase, install, confirmation, or settings action. Human supervision is required.

- Connect: record PIN display, code entry and registration result separately from physical TV state.
  Also record cancel, wrong-code and expiry behavior. Never include the PIN in evidence.
- Right, Left: observe one move each and return to original focus.
- Down, Up: pick a row where both are safe/reversible; record actual focus changes.
- Select: only on an agreed harmless, already-installed app/tile; confirm it opens.
- Back: confirm return; reset to Home using the physical remote if needed.
- Force-stop/relaunch Android app **without clearing data**; reconnect and repeat a
  reversible Right/Left pair. Record whether pairing survived without a new PIN.
- Operator puts TV into standby with its physical remote. Wait until standby stabilizes;
  record elapsed time. Tap LG Wake while Apple TV is asleep/disconnected from control.
  Record physical screen/power LED and network reachability, then Connect. Repeat
  immediate standby and long standby if practical; record duration, interface and settings.
- Separately test pairing denial/revocation, TV unplugged/unreachable, Wi-Fi interruption
  and DHCP address change. Restore network/settings, correct address or re-pair explicitly.
  Never retry/replay an old navigation automatically. Record the displayed recovery result.

Wake sends a standard 102-byte magic packet to configured subnet broadcast UDP port 9.
"Packet sent" is a local send result only; there is no UDP acknowledgment. Broadcast
usually stays in one subnet; sleeping Wi-Fi adapters, deep standby, energy-saving
settings and router filtering can prevent wake. Disconnected mains cannot wake.
The client does not poll or claim power-on success. After observing wake, explicitly
Connect; if that fails check settings/address/network rather than Apple TV CEC.

SSAP `registered` acknowledges pairing; the matching `pointer` response supplies the
input socket. Pointer button text has no per-command acknowledgment. A normal WebSocket
close only bounds flushing, not physical navigation. If the socket/close fails, delivery
may be uncertain: inspect TV before issuing any further command.

## Evidence from this implementation run

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: PASS on JDK 21.0.12.1.
  Lint has no errors; warnings include the deliberately custom exact-certificate trust
  manager, older dependency versions and existing project/toolchain warnings.
- `git diff --check`: PASS.
- Runnable `LgProtocolTest.mappingAndErrors`: six exact button mappings, Wake separation,
  magic packet bytes, invalid IPv4/MAC, unsafe pointer URL rejection, SSAP rejection,
  refusal results and registration key handling. These are simulated protocol checks.
- Initial implementation: `adb devices -l` had no attached devices; no APK, Maestro or
  physical client validation was run at that time.
- Discovery follow-up: rebuilt and installed with `adb install -r` on Galaxy S25
  (`SM-S931B`, Android 16), preserving app data. Installed version 0.1.0 (code 1), APK SHA-256
  `f63697c750e4bac76dd5bde4e7cf8b05d9f44da1bf55e7a7b819e5e70db188e0`.
  LG screen automatically reported
  **Found 1 LG TV(s)**. This verifies an LG SSDP advertisement was received, NOT that
  the advertiser was the G3 or that any TV action succeeded.
- `maestro test --device R3GL204147Z .maestro/helpers/lg-discovery.yaml`: PASS for automatic
  scan/result display, device selection and manual fallback; no pairing or TV commands.
  `maestro test --device R3GL204147Z .maestro --include-tags smoke`: PASS (1/1).
- `TVDiscoveryTest`: PASS for response/service/name parsing and bounded scan/error behavior.
  Desktop UDP routes were unavailable during the check; the scanner surfaced IOException
  within the deadline, not an invented successful discovery result.
- Existing `.maestro/` remains the only UI harness; the helper is outside suite discovery.
  GOO-33 owns suite integration. Android TLS/Keystore/pairing validation remains pending.
- Setup-flow fix: pairing save no longer validates/requires wake settings; navigation
  readiness depends only on saved host/certificate, while Wake has its own settings gate.
  Added `pairingDoesNotRequireWakeDetails` regression check. Build, unit tests, lint,
  discovery helper and smoke all PASS. Reinstalled preserving data; latest APK SHA-256
  `95bbb723a58810810a6a5b4081f39a755aef25ba29d15c00c54f403db39f96c0`.
  No certificate approval, saved-trust mutation, pairing or TV command was automated.
- PIN update: protocol regression checks cover PIN registration, exact setPin request,
  leading zeros, invalid codes, successful response versus registered distinction, PIN
  rejection, refusal of PROMPT, and no PIN challenge handling during navigation.
  Build/unit tests/lint, discovery helper and smoke PASS. Reinstalled preserving data;
  current APK SHA-256 `77ff6bc8ac518c0c907ca8c7bfaffef0804894e094da92d5d54ba50cbb93631d`.
  G3 PIN challenge/entry/registration, cancel/timeout and wrong-code behavior are NOT yet
  physically verified; the UI checks did not submit a code or mutate saved trust.
- Operator-reported G3 results: PIN pairing succeeded. After restarting the Android app,
  tapping **Connect / pair LG** reconnected without requesting another PIN. This confirms
  saved pairing survives app restart; it was reported by the operator, not independently
  observed by automation. Exact TV/webOS version and tested action list were not provided.
- Seven actual action observations, denial/revocation, standby reconnect and unreachable
  recovery remain **UNVERIFIED**. Do not infer them from the operator's pairing result.
- Required help: supervise the connected phone and G3 for pairing and physical observations. GOO-26 must stay In Progress until these observations are recorded.

For the hardware run record APK version/hash, installation time, phone/Android, TV/webOS,
network topology/interface, settings before/after, elapsed standby time, each UI result,
each physical observation, restoration steps and remaining failures. Keep addresses,
MACs, pairing material, generated screenshots and reports out of source control.
