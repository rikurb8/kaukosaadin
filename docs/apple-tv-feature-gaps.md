# Apple TV feature gaps against pyatv

## Baseline and sources

This comparison assumes “atv” means pyatv. Kaukosäädin implements Companion directly in Kotlin; pyatv is a protocol reference, not an Android dependency.

Local baseline: `README.md` (Apple TV section), `device/companion/CompanionLink.kt` and `CompanionClient.kt` under `app/src/main/java/fi/goodconsulting/kaukosaadin/`. Existing features: pairing, navigation, Home/Menu tap/double tap/hold, Play/Pause, volume steps, capability-gated ±10-second skipping, Sleep, launchable-app list and bundle-ID launch, and focus-driven keyboard text replacement. Keyboard support is fake-peer verified, not yet hardware-verified.

Primary references:

- [Pinned Companion implementation](https://github.com/postlund/pyatv/blob/b277a4c8222ecdcbaab8a24e3e713ca44765adb4/pyatv/protocols/companion/__init__.py): supported features, availability flags, remote control, power, audio, accounts and gestures.
- [Pinned Companion API](https://github.com/postlund/pyatv/blob/b277a4c8222ecdcbaab8a24e3e713ca44765adb4/pyatv/protocols/companion/api.py): commands and payloads.
- [pyatv supported features](https://pyatv.dev/documentation/supported_features/): protocol boundaries, MRP tunneling, metadata and streaming. This documentation acknowledges inconsistencies; pinned code is preferred for Companion claims.

## Additions within our existing Companion protocol

Effort is relative implementation scope, not a delivery estimate. Every addition needs fake-peer coverage and real-device checks before claiming hardware support.

| Missing feature | pyatv reference | Relative scope / caveat |
| --- | --- | --- |
| Wake Apple TV | `CompanionPower.turn_on`: release-only HID Wake, code 13 | Small protocol change matching our Sleep path. Connecting to a sleeping device and physical wake need testing; TV/AVR CEC wake is a separate outcome. |
| Control Center | `control_center`: HID PageDown, code 19 | Small; reuse press handling. |
| Start screensaver | `screensaver`: HID code 11 | Small; reuse press handling. |
| Channel +/- and guide | HID codes 15, 16, 17 | Small; utility depends on the current app. |
| Explicit Play/Pause and next/previous | `_mcc`: Play 1, Pause 2, NextTrack 3, PreviousTrack 4 | Moderate; the skip implementation now provides the `_iMC` subscription path, but these actions still need their own capability flags and UI. |
| Report awake/asleep state | `FetchAttentionState`; `SystemStatus` and `TVSystemStatus` subscriptions via `_interest` | Moderate; initial fetch can fail on newer tvOS, so retain Unknown and subscribe independently. Session readiness is not power state. |
| List/switch tvOS user accounts | `FetchUserAccountsEvent`; `SwitchUserAccountEvent` with `SwitchAccountID` | Moderate; analogous to app list/launch. Not Apple ID login or account provisioning. |
| Launch URL/deep-link shortcuts | `_launchApp` with `_urlS` instead of `_bundleID` | Small protocol extension, additional shortcut UI/validation. Target apps determine whether a link works. Not arbitrary video casting. |
| Touchpad/swipes/click gestures | `_touchStart`, `_hidT`, `_touchStop`; coordinates 0–1000 and press/hold/release phases | Larger UI and lifecycle change; gesture timing and cancellation need real-device checks. |
| Absolute volume slider/readout | `_mcc` GetVolume 5, SetVolume 6; `_iMC` Volume flag | Moderate, conditional. Volume steps working through CEC does not prove that absolute volume is available. |

Keyboard is already implemented: clear/append/read APIs could be exposed separately, but are not a major missing user feature compared with existing mirrored text replacement.

## Larger protocol expansions

The supported-features documentation attributes currently-playing metadata, artwork, playback state, active playback app, shuffle/repeat and richer playback control to MRP. On tvOS 15+, MRP is tunneled through AirPlay 2. These require another protocol stack and authentication/session integration, not more Companion HID codes.

AirPlay URL/file playback and RAOP audio streaming are also possible reference directions, but are substantial new scope. pyatv does not establish general Android screen mirroring support.

Do not advertise Siri voice or caption control from enum constants alone: the pinned API contains Siri and caption command values, but the Companion supported-feature implementation does not expose complete corresponding features.

## Recommended order

1. Wake and Control Center: high everyday value with little protocol work.
2. Explicit playback commands, gated by media capability updates (±10-second skipping is now implemented and fake-peer verified).
3. Power status and account switching.
4. Touchpad if users prefer swiping over arrows.
5. MRP/AirPlay only when a now-playing screen becomes a concrete requirement.

The original comparison sent no commands and changed no implementation. Subsequent work added ±10-second skip buttons with live capability gating and fake-peer checks; real-device playback remains unverified.
