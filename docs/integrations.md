# Device integrations

The app is a single `app` module. A **kind** (LG TV, Apple TV, Hue) plugs into the shell
through one explicitly registered object; the shell never branches on a kind.

## The seam

`ui/DeviceIntegrations.kt` holds the whole boundary:

- `DeviceIntegration` — one object per kind. Registers `kind`, its `scan` (one bounded LAN scan
  returning `Candidate`s), whether it `addsByAddress` and its `candidateAt(address)`, and a
  `controls` factory for a saved device.
- `Candidate` — a device a scan returned or the operator typed the address of, not saved yet: its
  `kind`, `name`, `host`, optional `detail` (a bridge's model), and its kind's `Pairing` steps.
- `PairingHost` — what pairing reports back to Add a device: the store, `onAdded` once the device is
  paired and saved, and `onCancel`. Pairing runs inside the composition, so dismissing the sheet
  cancels it; each kind clears what an unfinished attempt kept in a `finally`.
- `DeviceControls` — what the integration does for one saved device: `Remote` (the device's
  screen: the keypad for a TV or the lighting screen for a bridge), its own dialogs and sub-screens, `Settings` (the kind's extra rows under Connection on Device settings), `forgetDetail`
  (what forgetting also clears) and `forget()` (the kind's own local cleanup).
- `RemoteActions` — the callbacks every remote shares: select a saved device, add a device, open
  the Devices screen, Device settings and General settings.
- `DeviceIntegrations.all` / `of(kind)` — the register. `all` order is the order kinds are listed in.

Add a device (`ui/AddDeviceScreen.kt`) runs every kind's `scan` side by side and merges the outcomes
with `mergeScan` (`ui/FoundDevices.kt`) into one list: new devices first, already-saved ones marked
Added. Tapping one opens `PairingSheet` (`ui/PairingSheet.kt`), which hosts the candidate's
`Pairing`; every kind lays its steps out with the shared `PairingStep`, `PinField` and
`Disclosure`.

Each kind owns its composables, dialogs, client and per-device storage:

| Kind | Integration | Client |
| --- | --- | --- |
| LG TV | `ui/LgIntegration.kt` | `device/LgClient.kt` |
| Apple TV | `ui/AppleTvIntegration.kt` | `device/companion/` |
| Hue Bridge | `ui/HueIntegration.kt` | `device/hue/` |

One screen is deliberately outside the seam: the **Super remote** is a cross-kind layout, not a
kind. It puts one saved Apple TV's shortcuts and controls and one saved bridge's room-or-zone
lighting on one screen, so it resolves its own bindings to those saved devices and builds their
clients directly (`ui/SuperRemoteScreen.kt`, `ui/SuperRemoteBindings.kt`). It is not in
`DeviceIntegrations.all` and registers nothing.

## What the shell owns

Navigation (`Screen` in `ui/KaukosaadinApp.kt`), theme and layout preferences, the device picker,
the merged scan list, the Devices screen (`ui/DevicesScreen.kt`), the saved-device store
(`device/SavedDevices.kt`), and the common device settings: rename and forget.

## Adding a kind

1. Add its value to `DeviceKind` (`device/SavedDevices.kt`).
2. Write `ui/<Kind>Integration.kt`: an `object <Kind>Integration : DeviceIntegration` with its
   `scan`, a `Candidate` whose `Pairing` saves the device through `PairingHost`, and a
   `DeviceControls`.
3. Register it in `DeviceIntegrations.all`.
4. Run the tests: `DeviceIntegrationsTest` fails until the kind is registered and every kind has
   exactly one integration.

No existing integration's controls are edited. A lighting kind is not a keypad: `RemoteKey`, whose
`lg`/`hid` columns map TV presses, is not extended for it — a kind that is not a TV brings its own
main screen instead of `RemoteScreen`/`RemoteKeys`.

## Forget

`DeviceStore.forget(id, clearKindState)` is the one forget path, used by both Device settings and the
Devices screen: it clears everything the kind
keeps for the device through the controls, then drops the saved device and its selection. A kind that
must clear credentials (a Hue bridge's app key, say) implements that in its `DeviceControls.forget()`;
it is then cleared from the same place as every other kind, so a kind never adds its own forget code
to the shell.
