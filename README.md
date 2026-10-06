# Kaukosäädin

Your Android phone becomes the remote for the TVs in your
living room — **Apple TV** and **LG webOS TVs** — and controls **Philips Hue** lights through a
local Hue Bridge. It talks to everything directly over your home Wi-Fi, with no cloud account:
the TVs are reached on the LAN, and Hue through the bridge on the same network.

|  | Apple TV | LG webOS TV | Hue Bridge |
| --- | --- | --- | --- |
| **Find** | Wi-Fi scan (Companion mDNS) | Wi-Fi scan (SSDP) or address by hand | Wi-Fi scan (Hue mDNS) or address by hand |
| **Pair** | PIN shown on the TV | Pinned TLS certificate, then PIN | The bridge's physical link button |
| **Control** | Arrows, OK, BACK (Menu), HOME (TV), Play/Pause, Volume -/+ | Arrows, OK, BACK | Lights, rooms and zones: on/off and brightness |
| **Extras** | App launcher; type on the phone, text appears on the TV | Wake-on-LAN | Favorites first; live updates from other controllers |
| **Tested on** | A real Apple TV, from a Galaxy S25 | LG G3 | Not yet on hardware — see the [bridge handoff](docs/hue-bridge-v2.md) |

Save as many devices as you like and flip between them from the picker. Pick the **Classic**
look, or **Hacker man** if you want green-on-black.

**Try it** with one phone attached over USB (see [prerequisites](#prerequisites)):

```bash
./gradlew :app:installDebug
adb shell am start -n fi.goodconsulting.kaukosaadin/.MainActivity
```

## Using the app

### Adding devices

Fresh installs show **No devices added**. Tap **Find devices** to scan your Wi-Fi: LG TVs,
Apple TVs and Hue Bridges are found together and listed with their advertised name and address, or
you can **Enter LG TV address** by hand (a bridge takes its own numeric IPv4 the same way). Tap a
device to pair it, and it is saved:

- **Apple TV** pairs with the PIN it shows.
- **LG TV** shows one **Trust this TV?** dialog with an editable name and the TV's certificate
  fingerprint; **Trust & pair** pins that certificate and the TV then asks for its PIN.
- **Hue Bridge** pairs when you press the bridge's physical link button, then tap **Pair again**.

Any number of devices of any kind can be saved. The picker at the top of the remote switches
between them and has **Add device…**. The remote opens on the device you last used; until you pick
one, an Apple TV is the default if one is saved. **Device settings** renames the device and
**Forget device** (with confirmation) clears it and its pairing; for LG it also has **Re-pair**
and the optional Wake-on-LAN settings, and for a Hue Bridge it also clears the stored app key,
certificate pin and favorites. Upgrading from the single-slot build drops the old LG/Apple TV
pairings: pair them again from **Find devices**.

### Apple TV

The remote has arrows, **OK**, **BACK** (Menu), **HOME** (TV), **Play/Pause** and
**Volume -/+** keys; BACK/HOME also take double tap and a 1 s hold.

- **Volume -/+** steps the volume once per tap, like the Siri Remote's side buttons. The Apple
  TV only forwards it to the TV/AVR when **Settings › Remotes and Devices › Volume Control** is
  set to control the TV; with that off, the press is acknowledged and nothing audible happens.
  Operator-confirmed on the real Apple TV (2026-10-06).
- **Apps** lists what the Apple TV reports as launchable and starts the one you tap (36 apps on
  the real Apple TV; list and launch both operator-confirmed, and the startup request they need
  was found on the real TV).
- When the Apple TV's on-screen keyboard appears, the app opens a text field and mirrors what
  you type to the TV (fake-peer verified, not yet checked on a real Apple TV).
- There is no Apple TV wake.

Opening the remote connects once and reuses the verified session while the screen is visible,
including while the apps list is open; leaving, switching device or backgrounding it closes the
connection, and failed presses are never replayed or queued. Pairing and Home/Menu tap, double
tap and hold, arrows, OK and Play/Pause were confirmed on the real Apple TV from the S25, and volume
was confirmed the same way on 2026-10-06. See
[Apple TV Companion](docs/apple-tv-companion.md); a debug-only crypto gate and discovery screen
remain there.

### LG TV

**Connect TV** reuses saved pairing. The arrows, **OK** and **BACK** send real commands;
navigation is disabled before connecting, while busy and after a failed command. Commands are
never replayed automatically. **Wake TV** is wake-only, enabled with saved MAC/broadcast
settings. After waking, wait for the TV and tap Connect. READY/LEDs indicate verified
registration, not TV power or a persistent socket.

PIN pairing and saved-pairing reconnect have been operator-verified on the LG G3.
See [LG verification and operator handoff](docs/lg-g3.md) for physical-action checks.

### Settings

**General settings** is available from both the empty screen and the remote, separately
from device settings. Theme and layout are independent; both apply immediately throughout the
app and are remembered across restarts.

- **Theme**: **Classic** (the original palette, following system light/dark mode) or
  **Hacker man** (a green-on-black demo theme).
- **Layout**: **Standard** (the full casing, wheel and VFD display) or **Debug** (flat panels
  with a live timestamped status log, for development and troubleshooting).

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/MainActivity.kt` | Single activity; hosts Compose |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/ui/` | Compose UI (screens and components) |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/device/` | Saved-device store, LG client and Companion (Apple TV) client: crypto, discovery, pairing, presses, text input |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/device/hue/` | Hue Bridge v2 client: mDNS discovery, link-button pairing, TLS pinning, lights/rooms/zones and the local event stream |
| `.maestro/` | Maestro flows and workspace configuration |
| `.dagger/modules/ci/main.dang` | The CI check: a JDK 21 container running ktlint and detekt |
| `.github/workflows/ci.yml` | GitHub Actions entry point: installs the pinned Dagger CLI and runs the check |

UI and device-network code live in separate packages in the single `app` module. A small
saved-device store (`device/SavedDevices.kt`) lists the devices and their kind; each kind keeps
its own client and per-device storage. Every kind registers its setup, main screen and settings
extras through `ui/DeviceIntegrations.kt`; see [Device integrations](docs/integrations.md) for the
seam and how to add a kind. There is no DI framework and no multi-module setup.

## App ID

`fi.goodconsulting.kaukosaadin` (both Gradle `namespace` and `applicationId`).

## Toolchain

| Component | Version | Why this version |
| --- | --- | --- |
| JDK | 21 (Homebrew `openjdk@21`) | JDK present on the build machine; AGP 9 requires 17+ |
| Gradle | 9.7.1 (wrapper checked in) | Installed Gradle version, so local and wrapper match |
| Android Gradle Plugin | 9.4.1 | Current stable; supports `compileSdk 37` |
| Kotlin | built into AGP 9 | AGP 9 compiles Kotlin itself; the `kotlin-android` plugin is not applied |
| Compose compiler plugin | 2.4.20 | Must match the Kotlin version used for Compose |
| Compose BOM | 2026.09.00 | Current stable; Compose 1.12+ requires `compileSdk 37` |
| `compileSdk` | 37 (Android 17) | Latest installed SDK platform; required by the Compose BOM above |
| `targetSdk` | 36 (Android 16) | Version of the phone the app is built for |
| `minSdk` | 29 (Android 10) | Modern floor; nothing in the app needs newer |
| Java/Kotlin bytecode target | 17 | AGP default, widest compatibility |

## Network permissions

- `android.permission.INTERNET` — declared now as the baseline for home-LAN traffic.
- **Local network access:** this app targets 36, so `INTERNET` suffices on Android 16
  and receives legacy LAN access on Android 17. Do **not** request
  `ACCESS_LOCAL_NETWORK` before targeting 37. A target-37 upgrade must declare and
  request it before any client network operation
  ([platform guidance](https://developer.android.com/privacy-and-security/local-network-permission)).
  Android 16's optional `RESTRICT_LOCAL_NETWORK` developer flag is not enabled by this app.
- `CHANGE_WIFI_MULTICAST_STATE` allows a Wi-Fi multicast lock during the bounded SSDP scan.
- LG uses TLS with an explicitly approved certificate pin. A Hue Bridge uses verified TLS
  too: the system CA store first, then a trust-on-first-use SPKI pin for a self-signed bridge.
  No cleartext exception, trust-all connection, or silent security downgrade.

## Prerequisites

1. **JDK 21** on `PATH` — check with `java -version`.
2. **Android SDK** with platform 37.0, build-tools 36, and platform-tools (adb), and
   `ANDROID_HOME` pointing at it (or a `local.properties` with `sdk.dir=...`):
   ```bash
   sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
   ```
3. **A phone** with Developer Options → USB debugging enabled, connected by USB and
   authorised: `adb devices` must list it as `device` (not `unauthorized`).

No Android Studio is required; everything below is command line.

## Build

```bash
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. The wrapper is checked in, so a
fresh checkout only needs the prerequisites above.

## Continuous integration

CI runs `dagger check`, which executes `.dagger/modules/ci/main.dang`: a JDK 21 container
with the working tree mounted and a shared Gradle cache. Locally, that is the same
command CI runs:

```bash
dagger check              # every check in the workspace
dagger check ci:lint      # just this one
```

`ci:lint` runs `./gradlew :app:ktlintCheck :app:detekt` — the zero-baseline style and
static analysis gate — and is deliberately the only thing that runs so far. Unit tests,
Android lint and the debug APK are **not** covered yet, and Maestro needs a phone, so UI
checks stay operator-run as described above. Running it locally needs a Dagger CLI release
that satisfies `engineVersion` and a Docker engine:

```bash
curl -fsSL https://dl.dagger.io/dagger/install.sh | DAGGER_VERSION=v1.0.0-beta.7 sh
dagger check    # under 10 s here once the Gradle cache volume is warm
```

Because the Android SDK is not part of this check, it runs natively on a laptop and on the
runner. The first run (and each GitHub-hosted runner) pays the Gradle distribution and
dependency download; after that the Gradle cache volume makes it seconds.

`.github/workflows/ci.yml` installs the Dagger CLI pinned to the same version as
`engineVersion` in `.dagger/modules/ci/dagger-module.toml` and runs `dagger check` on
pushes to `main` and on pull requests.

## Install and run on the phone

```bash
adb devices -l                        # find the serial, e.g. R3GL204147Z
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n fi.goodconsulting.kaukosaadin/.MainActivity
```

With exactly one device attached, `./gradlew :app:installDebug` installs the same way.

The phone and the devices it controls must be on the **same home network** (same
Wi-Fi/LAN). Nothing is routed through the internet or a cloud service.

## Maestro smoke test

[Maestro](https://docs.maestro.dev/get-started/supported-platform/android) runs on this
computer and drives the app on the phone over ADB. Nothing Maestro-related is bundled into
the app.

**Install (tested with Maestro CLI 2.11.0):**

```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
export PATH="$PATH:$HOME/.maestro/bin"
maestro --version
```

**Run smoke against one explicitly selected device** (replace the serial):

```bash
maestro test --device R3GL204147Z .maestro --include-tags smoke
```

- The app must already be installed on the device — build and install first (above).
- `.maestro/config.yaml` includes only the `smoke` and `living-room` suites and excludes
  `.maestro/helpers/**` from discovery, so helper sub-flows never run on their own.
- `living-room` is reserved for supervised real-TV checks; no automated living-room
  suite is present yet. Smoke only checks UI/disabled controls and opens setup; it
  never pairs, changes saved trust, wakes or sends navigation.
- Generated reports/screenshots and local environment files are git-ignored. Never commit
  real device addresses, pairing material, or secrets.

**Phone prerequisites for tests:** USB connection (preferred, so later Wi-Fi recovery
tests cannot sever the ADB link), USB debugging authorised, and the phone **unlocked** —
Maestro cannot dismiss a secure lock screen.

## Verified on

| Item | Result |
| --- | --- |
| Phone | Samsung Galaxy S25 (`SM-S931B`), Android 16 / API 36, arm64-v8a, serial `R3GL204147Z`, connected over USB |
| `./gradlew :app:assembleDebug` | PASS — JDK 21.0.12, Gradle 9.7.1, AGP 9.4.1 (also after `./gradlew clean`) |
| Install + launch | PASS — `adb install -r` returned `Success`; `MainActivity` was the resumed activity and `logcat -b crash` was empty |
| Maestro smoke | PASS — `maestro test --device R3GL204147Z .maestro --include-tags smoke` → `1/1 Flow Passed`, Maestro CLI 2.11.0, phone unlocked |

## LG client checks

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Style and static analysis (`ktlintCheck` and `detekt` also run as part of `./gradlew check`).

Fix a finding by changing the code. If a rule genuinely does not apply, suppress it at the
narrowest scope with a reason on the line above, instead of adding a baseline entry:

- detekt: `@Suppress("RuleName")` on a file (`@file:`), class, function, statement, or expression.
- ktlint: `@Suppress("ktlint:<ruleset>:<rule-id>")`, e.g. `ktlint:standard:max-line-length`.
  `// ktlint-disable` comments are deprecated by this ktlint version and no longer suppress.

`config/*/baseline.xml` is gone: neither linter has a baseline, so every finding fails the build and
the only way to land an exception is an inline `@Suppress` with a reason on the line above it.

```bash
./gradlew :app:ktlintCheck :app:detekt
```

GOO-26 uses OkHttp for Android WebSockets and platform Android Keystore for pairing.
The upstream lgtv-kotlin SSDP scanner is copied with attribution and small Android/safety
fixes; no additional dependency or discovery stack. Sleeping TVs retain saved setup and
manual address. MAC/subnet broadcast still need operator input for wake.
No bridge, cloud execution, power-off, or generic device framework.

Discovery UI check (scans for LG and Apple TV; no pairing or TV commands):
```bash
maestro test --device <serial> .maestro/helpers/device-discovery.yaml
```
