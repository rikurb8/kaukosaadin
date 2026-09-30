# Kaukosäädin

Android remote for the living-room **LG G3** (webOS) and **Apple TV**, over the home
network.

**Current state: foundation only.** The app builds, installs, and opens a placeholder
screen. It does not control anything yet — LG support lands in GOO-26 and Apple TV
support in GOO-28; GOO-29 builds the shared remote screen, and GOO-30 validates it on the
real devices.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/MainActivity.kt` | Single activity; hosts Compose |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/ui/` | Compose UI (screens and components) |
| `app/src/main/java/fi/goodconsulting/kaukosaadin/device/` | Device-network clients (LG, Apple TV) — added in GOO-26/GOO-28 |
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
- **Local network access:** on Android 16 (`targetSdk 36`), reaching LAN devices still only
  needs `INTERNET`. Starting with Android 17 (API 37), apps must also declare the
  `android.permission.ACCESS_LOCAL_NETWORK` **runtime** permission
  ([docs](https://developer.android.com/privacy-and-security/local-network-permission)).
  That runtime request is added together with the device clients (GOO-26/GOO-28), which is
  also where any cleartext/HTTP exception for a specific device host will be scoped.
- No network-security config is relaxed in this issue.

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
- `living-room` covers the real LG TV / Apple TV and arrives with GOO-29; select it with
  `--include-tags living-room` (it is not run from this issue).
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

## Not part of this issue

No CI, no multi-module architecture, no device protocols/discovery/pairing, no device
libraries, and no relaxed network security.
