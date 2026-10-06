# Hue Bridge v2 — physical verification and operator handoff (#13)

## Status: nothing here has been verified on hardware

This is the operator handoff for the **square Hue Bridge v2** (`BSB002`) integration added by
spec #4. It lists the checks to run against a real bridge, real lights and real remotes, and a
results section to record them in.

**No physical verification has been performed.** The bridge was not available during this
implementation run, no light was toggled through the app, and no certificate was read from a
real bridge. Every result below is intentionally blank; nothing here is inferred from unit
tests, from the research notes, or from another device's results. Treat everything described
as *planned behaviour*, not as observed.

What the app does **without** a bridge — the pairing envelope, the command bodies, SPKI pins,
resource decoding, the lighting controller and the live-subscription mechanics — is exercised
by the automated checks listed in "Automated checks", which are kept separate from this
handoff as spec #4 requires. Those checks do not touch a socket and prove nothing about a real
bridge.

## Automated checks (mock/unit coverage, separate from this document)

Run from the repo root:

```bash
./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:detekt
```

The Hue-focused checks live in `app/src/test/java/fi/goodconsulting/kaukosaadin/device/hue/`
and `app/src/test/java/fi/goodconsulting/kaukosaadin/ui/LightingControllerTest.kt`:

| Area | What is checked without a bridge |
| --- | --- |
| Pairing envelope | `devicetype` body, app key/case, link-button error 101, other types by number, malformed replies, opaque key format |
| Command bodies | `{"on":{"on":…}}` and brightness-only `{"dimming":{"brightness":N}}` (asserts no `on` field), one `execute()` per press |
| Failures | v2 error envelope, HTTP status text, transport/TLS text, out-of-range brightness, a failed command sent once and never replayed |
| Trust | SPKI SHA-256 against a known cert, TOFU record/accept/reject, empty chain, changed pin |
| Discovery parsing | resolved address/port kept as advertised, name sanitisation, unusable endpoints dropped, optional/unverified TXT keys |
| Resources | light/room/zone/grouped-light decode, missing metadata, error envelope without peer text |
| Event stream | SSE framing, `Accept: text/event-stream`, `If-None-Match` resume, refusal, close on cancel, resume-from-last-frame-id |
| Lighting screen | listing, per-light and per-room/zone toggles, brightness disabled while off, slider-release sends once, favorites first without hiding/repeating, restart persistence, forget clears them, live event applies, leaving disconnects and returning refreshes, failed stream surfaces |

**Not covered by any test, by construction:** `HueDiscovery` and the `HueClient`/`HueTls`
network paths need a bridge, and Compose screens and `HueSetup` need a device. There is no
`androidTest` source set, no Compose UI-test dependency and no Robolectric in this project; the
physical checks below are the operator's route to verifying those paths instead.

## The `UNVERIFIED-UNTIL-PHYSICAL-BRIDGE` items

These nine items were marked `UNVERIFIED-UNTIL-PHYSICAL-BRIDGE` by the research ticket (#5) and
have not been closed. Each maps to a check below.

| # | Item | Checked by |
| --- | --- | --- |
| 1 | Self-signed certificate issuer/CN/SAN and historical SHA-1 fingerprint | TLS-1 |
| 2 | What firmware `1971060010` (2025-05-22, "proper HTTPS certificates") actually serves on a LAN IP | TLS-2, TLS-3 |
| 3 | **Whether writing only `dimming.brightness` to an off light turns it on** | LIGHT-6 |
| 4 | Link-button press window duration (assumed ~30 s) | PAIR-1 |
| 5 | Event-stream heartbeat/keepalive interval and reconnect semantics | LIVE-2 |
| 6 | Exact mDNS TXT key names (`bridgeid`, `modelid`) and SRV payload | DISC-1 |
| 7 | Full v2 numeric error-`type` table and concrete rate-limit figures | ERR-1 |
| 8 | App-key character set in production | PAIR-3 |
| 9 | Firmware gate for the v2 API being exactly `1948086000` | PAIR-4 |

Two further items from #7 are recorded on ticket #13 rather than in that table: the TLS
hostname-verifier risk (TLS-3) and the trust-on-first-use window (TLS-4).

## TLS and trust

The app has no trust-all path. `HueTls` offers the bridge's chain to the platform trust manager
first and only falls back to a stored SPKI pin when that rejects it, so a bridge with a
CA-signed certificate is verified as ordinary HTTPS and a self-signed bridge is
trust-on-first-use pinned. The default hostname verifier is still consulted first in both
cases.

- **TLS-1 — read the presented certificate.** From a machine on the same LAN:
  ```bash
  BRIDGE=192.168.1.42   # the bridge's LAN IPv4
  openssl s_client -connect "$BRIDGE:443" -servername "$BRIDGE" </dev/null 2>/dev/null \
    | openssl x509 -noout -issuer -subject -ext subjectAltName -fingerprint -sha256
  ```
  Record issuer, CN, SAN and fingerprint. This closes item 1 and half of item 2.
- **TLS-2 — classify the path.** Record the bridge firmware version (Hue app, or the bridge's
  built-in web page) and whether the certificate chains to a public CA or is self-signed. If it
  is CA-signed, record the CA and the SANs. Test what the system trust store accepts for the
  bridge's IP:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' "https://$BRIDGE/api/config"   # no -k
  ```
  A failure here with a CA-signed certificate means the SAN does not cover the bridge's IP.
  This is item 2.
- **TLS-3 — the hostname-verifier risk (ticket #13).** The app consults the default verifier
  first, then the stored pin. On the CA-signed path **no pin is ever stored**. So if the
  certificate has no `iPAddress` SAN matching the bridge's IP, the app is expected to **reject**
  the connection even though curl might accept the same certificate over a hostname. On the
  real bridge, record which path it takes and, if it is rejected, the exact on-screen message
  (expected: the "certificate changed or was rejected … Forget and pair again" text) and
  whether Device settings still shows "Certificate: pinned to the paired bridge" (a CA-signed
  acceptance never stores a pin). This decides whether the
  integration works at all on current firmware.
- **TLS-4 — trust-on-first-use window.** The pin is captured at first pairing. An active
  attacker on the LAN during that one window could substitute their key; every later connection
  is pinned to whatever was captured. Record whether this residual risk is acceptable to the
  operator, or whether pairing should require an explicit fingerprint confirmation (a product
  decision, not a defect). Also record that a bridge whose key then changes is rejected with a
  visible message until it is forgotten and re-paired.
- **TLS-5 — keypair rotation.** After a factory reset or a firmware certificate/key rotation,
  confirm: existing pairing fails with a visible message, no command is sent, and **Forget
  device** followed by re-pairing restores control. This includes the SPKI pin and the app key.

## Discovery, pairing and the firmware gate

`HueDiscovery` uses NsdManager for `_hue._tcp`, the same pattern as Companion discovery, so it
needs no multicast lock. Advertisements are treated as untrusted hints; the operator still
presses the bridge's link button. Manual entry accepts a numeric LAN IPv4 only.

- **DISC-1 — mDNS discovery (item 6).** On the same subnet, **Find devices** should list the
  bridge with its advertised name and address. Record the advertised service name, host, port,
  the TXT keys and their values, and the SRV payload (use `dns-sd -B _hue._tcp` /
  `dns-sd -L "<name>" _hue._tcp` or `avahi-browse -r _hue._tcp`). Confirm whether `bridgeid`
  and `modelid` are the actual keys, what case they are in, and whether the model shown beside
  the name (`… · BSB002`) matches the bridge. The app reads only `modelid`, as optional display
  metadata; it does not store or use the advertised port or `bridgeid`.
- **DISC-2 — manual address.** With discovery producing nothing (e.g. scan on a network where
  mDNS is filtered), enter the bridge's IPv4 in **Enter Hue Bridge address** and tap **Add**.
  Confirm a URL or hostname is refused with "Enter the bridge's numeric IPv4 address, not a
  URL.", and that a valid address starts pairing.
- **PAIR-1 — link-button pairing (item 4).** Select the bridge, press the bridge's link button,
  tap **Pair again**. Record the result and the **link-button window duration**: time from
  pressing the button to a successful pair, and the longest delay that still succeeds (assumed
  ~30 s; no timer is assumed in the code). Confirm the device is saved and shows under the
  picker.
- **PAIR-2 — no button pressed.** Before the button is pressed, pairing shows the link-button
  message (bridge error type 101) and nothing retries on its own. Confirm the candidate stays
  on screen so **Pair again** can be tapped.
- **PAIR-3 — app-key format (item 8).** After pairing, confirm the app appears in the Hue app's
  paired-app list, and record the *shape* of the key the bridge minted (expected: a 36-char
  hyphenated UUID). **Never record or commit the key itself.** Note whether a `clientkey` was
  returned; the app requests one but ignores it, because it uses no entertainment API.
- **PAIR-4 — firmware gate (item 9).** Record the bridge firmware version. Confirm the v2 API
  answers on that firmware, e.g.
  ```bash
  curl -sk -H "hue-application-key: $KEY" "https://$BRIDGE/clip/v2/resource/light"
  ```
  The app itself does not read the firmware version and does not gate on `1948086000`; this
  check only confirms whether that number is really the v2 floor.

## Restart, storage and forget

- **STATE-1 — restart survival.** Force-stop the app **without clearing data** and reopen it:
  the bridge is still saved, the lighting screen lists its lights, rooms and zones with no
  re-pairing, and the app key still works. Check `adb logcat -s Kaukosaadin` across the whole flow
  and confirm **no app key, pin or peer response text appears**. This closes the last two bullets of
  the #7 list (restart survival and logcat absence) on hardware.
- **STATE-2 — forget.** **Device settings → Forget device** removes the saved bridge and
  reports what it clears (app key, certificate pin, favorites). Confirm the mock-only behaviour
  on hardware: the bridge can no longer be controlled without pairing again, the lighting
  screen is gone, and re-adding the bridge starts from discovery. Note that forgetting on the
  phone does **not** delete the key from the bridge; the bridge's paired-app list is the
  operator's to clean up.
- **STATE-3 — re-pair after forget.** Add the bridge again through the link button and confirm
  control returns.

## Lights, rooms, zones, brightness and favorites

- **LIGHT-1 — listing.** The lighting screen lists the bridge's individual lights and its
  existing rooms and zones with the names shown in the Hue app; rooms and zones are listed under
  their own headings so the two can be told apart. A room or zone is controlled through its grouped
  light, so one the bridge reports no group for is listed but not controllable.
- **LIGHT-2 — on/off per light.** Toggle a light and confirm the physical light changes; toggle
  it back.
- **LIGHT-3 — on/off per room and zone.** Toggle a room and confirm every light in it changes
  together; repeat for a zone, which can span rooms. Confirm a group with some lights already on
  reports on.
- **LIGHT-4 — brightness disabled while off.** With a light off, the brightness slider is
  disabled and dragging it produces no command. This is the app-side half of item 3.
- **LIGHT-5 — slider release.** Dragging the slider sends nothing; releasing it sends exactly
  one brightness change (observed as one change on the light, not a continuous sweep). A second
  release while one is in flight is dropped.
- **LIGHT-6 — the off-state assumption (item 3, spec-critical).** The app only ever writes
  `{"dimming":{"brightness":N}}` with no `on` field, because the research notes could not
  confirm whether that turns an off light on. With a light **off**, write brightness alone to it
  and observe:
  ```bash
  LIGHT=<light id>   # from /clip/v2/resource/light
  curl -sk -X PUT -H "hue-application-key: $KEY" -H 'Content-Type: application/json' \
    -d '{"dimming":{"brightness":40}}' "https://$BRIDGE/clip/v2/resource/light/$LIGHT"
  ```
  Record whether the light stays off or turns on. If the bridge turns it on, note the agreed
  fallback in the comment on `HueCommands.brightnessBody`: sending `on` and `dimming` together
  (and re-thinking the "disabled while off" rule). This is the single most important item here,
  because the spec's "brightness must not implicitly turn lights on" rule rests on it.
  Repeat once for a grouped light if the operator wants the room or zone case covered.
- **LIGHT-7 — favorites ordering.** Favorite one light, one room and one zone; confirm they appear
  first, the rest of each list stays visible below in the bridge's own order, and nothing is
  duplicated or hidden. Restart the app and confirm the favorites are still first. Un-favoriting
  restores the bridge's order. Forgetting the bridge clears the favorites with the rest of its file.

## Live updates and lifecycle

- **LIVE-1 — another controller.** With the lighting screen visible, change a light from a
  physical wall switch or from the Hue app. The phone's screen should follow without a manual
  refresh, and the app must never send a command in response.
- **LIVE-2 — heartbeat and reconnect (item 5).** Record the event-stream heartbeat/keepalive
  interval and the reconnect semantics: how long an idle stream stays open, whether the bridge
  sends keepalives, and what the bridge does on resume (the app sends
  `If-None-Match: <last frame id>`). Confirm the app does **not** reconnect on its own: a stream
  that drops stays failed until the screen is left and re-entered.
- **LIVE-3 — backgrounding.** Send the app to the background (or leave the lighting screen) and
  confirm the live subscription is released; return and confirm the state is read fresh from the
  bridge. A change made while the app was away should be visible on return.
- **LIVE-4 — failed stream stays visible.** Make the stream fail (unplug the bridge, or block
  port 443 for the phone). Confirm the failure is shown on screen, the app does not crash, and
  commands fail visibly rather than being queued or replayed. Restore the network and confirm the
  screen recovers on re-entry.

## Errors and rate limits

- **ERR-1 — the full error and rate-limit picture (item 7).** Record the v2 numeric `type` values
  seen for common failures and the concrete request-rate limits. Exercise at least: a wrong or
  removed app key (expect visible "app key" text), a light deleted on the bridge (expect the
  "no longer has that light" text), a burst of rapid commands (expect rate-limit behaviour and
  the "rate-limiting" text), and a bridge-side 5xx if reproducible. Never copy bridge-supplied
  `description` text into the app — the app shows its own text keyed on the number/status.
- **ERR-2 — no replay.** After any failed command, confirm the app sends nothing more on its own
  and a wake of the phone/bridge does not replay it.

## LG and Apple TV regression after the #6 refactor

Test #6's integration-seam refactor (`ui/DeviceIntegrations.kt`, `device/SavedDevices.kt`) on
hardware. The detailed safe sequences and current evidence live in
[LG verification](lg-g3.md) and [Apple TV Companion](apple-tv-companion.md); this
document only adds the regression checks, and does not repeat or supersede them.

Precondition: install the same debug APK over the operator's existing data (`adb install -r`)
so the saved devices and pairings already in place are the ones under test.

- **LG-1 — setup.** The LG TV is still listed and **Connect TV** reuses the saved pairing (no
  new PIN). Discovery still finds it.
- **LG-2 — controls.** Arrows, **OK** and **BACK** send real commands; disabled while not
  ready/busy.
- **LG-3 — re-pair, wake, forget.** **Re-pair** works; **Wake TV** still sends with saved
  MAC/broadcast; **Forget device** drops the TV and its pairing.
- **LG-4 — saved data after the refactor.** A pairing saved before #6 still connects. Device
  settings still rename and forget. Nothing re-prompts for trust or PIN because of the refactor.
- **ATV-1 — setup.** The saved Apple TV is still listed and opens **READY** with its existing
  pairing (no new PIN).
- **ATV-2 — controls.** Arrows and **OK**; **BACK** (Menu) and **HOME** (TV) tap, double tap and
  hold; **Play/Pause**; **Volume -/+**.
- **ATV-3 — apps.** **Apps** lists the TV's launchable apps and a tap launches one.
- **ATV-4 — Sleep, reconnect, re-pair, forget.** The power key sleeps the TV; a later press
  reconnects; forget removes the Apple TV. Verify the pre-seam pairing is not invalidated by the
  refactor.
- **ATV-5 — saved pairings after the refactor.** With an LG TV, an Apple TV and a Hue bridge all
  saved, switch between them from the picker and confirm each kind keeps its own pairing and
  storage.

## Hardware/run record (operator: fill in)

| Field | Value |
| --- | --- |
| Date | |
| Bridge model / firmware | |
| Bridge LAN IPv4 (do not commit) | |
| Lights, rooms and zones used | |
| Phone model / serial / Android | |
| APK SHA-256 (debug) | |
| Install method / data preserved | |
| Network topology (router, isolation, VPN) | |

## Results (operator: fill in)

No row below has been run. Enter PASS / FAIL / BLOCKED, the date and a short note for each.
Record exact off/on outcomes for LIGHT-6 and the certificate details for TLS-1/TLS-2. Keep
addresses, MACs, app keys, pins and screenshots out of source control.

| Check | Result | Date | Notes |
| --- | --- | --- | --- |
| TLS-1 certificate issuer/CN/SAN/fingerprint | | | |
| TLS-2 CA-signed vs self-signed path | | | |
| TLS-3 hostname-verifier risk (SAN vs IP) | | | |
| TLS-4 trust-on-first-use window | | | |
| TLS-5 key rotation → visible failure → re-pair | | | |
| DISC-1 mDNS discovery and TXT/SRV keys | | | |
| DISC-2 manual IPv4 address and rejection text | | | |
| PAIR-1 link-button pairing + window duration | | | |
| PAIR-2 link-button not pressed (error 101) | | | |
| PAIR-3 app-key format (record shape only) | | | |
| PAIR-4 firmware gate / v2 reachable | | | |
| STATE-1 restart survival + clean logcat | | | |
| STATE-2 forget clears credentials/pin/favorites | | | |
| STATE-3 re-pair after forget | | | |
| LIGHT-1 lights, rooms and zones listing | | | |
| LIGHT-2 per-light on/off | | | |
| LIGHT-3 per-room/zone on/off | | | |
| LIGHT-4 brightness disabled while off | | | |
| LIGHT-5 slider release sends once | | | |
| LIGHT-6 brightness-only write to an off light | | | |
| LIGHT-7 favorites first, persisted, rest visible | | | |
| LIVE-1 external switch/app change appears | | | |
| LIVE-2 heartbeat/reconnect semantics | | | |
| LIVE-3 backgrounding releases, return refreshes | | | |
| LIVE-4 failed stream stays visible | | | |
| ERR-1 error types and rate limits | | | |
| ERR-2 no replay after failure | | | |
| LG-1..LG-4 regression | | | |
| ATV-1..ATV-5 regression | | | |

Free-form notes, surprises and defects found (route any defect to a new ticket rather than
fixing it here):

```text

```
