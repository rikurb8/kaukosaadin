# Kaukosäädin

Android remote for **LG webOS TVs** and **Apple TV** over your home network.

Fresh installs show **No TVs added**, not a pre-filled remote. Tap **Add LG TV** to discover
and select a TV (or enter its IPv4 address), approve its certificate and pair with its
on-screen PIN. Successful pairing opens the main remote. Discovery supplies the TV's
advertised display name when available; **TV name** is editable and saved with setup.
**Save TV name** renames an existing TV without changing pairing. Old saved setups remain
usable and default to “LG TV” until named. **Remove TV** requires confirmation and returns
to the empty state; **Forget LG pairing and certificate** only resets trust/pairing.

The app saves one LG TV and one Apple TV; the **LG TV / Apple TV** switch on the remote
picks which one the keys drive (selecting one that isn't set up opens its setup). **Connect TV**
reuses saved pairing, and **TV settings** allows changing setup and optional Wake-on-LAN
settings. The arrows, **OK** and **BACK** send real commands; navigation is disabled before
connecting, while busy and after a failed command. Commands are never replayed automatically.
**Wake TV** is wake-only, enabled with saved MAC/broadcast settings. After waking, wait
for the TV and tap Connect. READY/LEDs indicate verified registration, not TV power or a
persistent socket.

**Add Apple TV** (or **Apple TV settings**) scans for Companion services, pairs with the
PIN the Apple TV shows and can forget the pairing. Its remote has arrows, **OK**, **BACK**
(Menu), **HOME** (TV) and **Play/Pause**; BACK/HOME also take double tap and a 1 s hold. Each press
connects, verifies the saved pairing and waits for the Apple TV's acknowledgment; nothing
is queued or replayed. There is no Apple TV wake. Pairing and Home/Menu tap, double tap
and hold, arrows, OK and Play/Pause were confirmed on the real Apple TV from the S25. See [Apple TV Companion](docs/apple-tv-companion.md); a debug-only
crypto gate and discovery screen remain there.

PIN pairing and saved-pairing reconnect have been operator-verified on the LG G3.
See [LG verification and operator handoff](docs/lg-g3.md) for physical-action checks.

**General settings** is available from both the empty screen and the remote, separately
from device setup. **Theme** picks the palette: **Classic** (the original palette, following
system light/dark mode) or **Hacker man** (a green-on-black demo theme). **Layout** picks how
the remote screen is presented: **Standard** (the full casing, wheel and VFD display) or
**Debug** (flat panels with a live timestamped status log, for development and
troubleshooting). Theme and layout are independent; both apply immediately throughout the
app and are remembered across restarts.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/MainActivity.kt` | Single activity; hosts Compose |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/ui/` | Compose UI (screens and components) |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/device/` | LG client and Companion (Apple TV) client: crypto, discovery, pairing, presses |
| `.maestro/` | Maestro flows and workspace configuration |

UI and device-network code live in separate packages in the single `app` module. There is
no DI framework, no multi-module setup, and no generic device abstraction.

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
- LG uses TLS with an explicitly approved certificate pin. No cleartext exception,
  trust-all connection, or silent security downgrade.

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
Existing violations are grandfathered in `config/*/baseline.xml`; regenerate after intentional
cleanup with `./gradlew :app:ktlintGenerateBaseline :app:detektBaseline`.

```bash
./gradlew :app:ktlintCheck :app:detekt
```

GOO-26 uses OkHttp for Android WebSockets and platform Android Keystore for pairing.
The upstream lgtv-kotlin SSDP scanner is copied with attribution and small Android/safety
fixes; no additional dependency or discovery stack. Sleeping TVs retain saved setup and
manual fallback. MAC/subnet broadcast still need operator input for wake.
No bridge, cloud execution, power-off, or generic device framework.

Discovery UI check (no pairing or TV commands):
```bash
maestro test --device <serial> .maestro/helpers/lg-discovery.yaml
```
