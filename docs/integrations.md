# Device integrations

The app is a single `app` module. A **kind** (LG TV, Apple TV, later Hue) plugs into the shell
through one explicitly registered object; the shell never branches on a kind.

## The seam

`ui/DeviceIntegrations.kt` holds the whole boundary:

- `DeviceIntegration` — one object per kind. Registers `kind`, its `Setup` section on the Add
  device screen, and a `controls` factory for a saved device.
- `DeviceControls` — what the integration does for one saved device: `Remote` (the main screen), its
  own dialogs and sub-screens, `Settings` (the kind's extra rows on Device settings), `forgetDetail`
  (what forgetting also clears) and `forget()` (the kind's own local cleanup).
- `SetupHost` — the Add device screen's shared state: the store, one `scanToken` that bumps when the
  operator taps Scan again, one `busy` flag, one progress `message`, and the `run` helper. A
  `Setup` scans on first composition and whenever `scanToken` changes, so the one Scan button
  searches every registered kind.
- `RemoteActions` — the callbacks every remote shares: select a saved device, add a device, open
  Device settings and General settings.
- `DeviceIntegrations.all` / `of(kind)` — the register. `all` order is the Add device section order.

Each kind owns its composables, dialogs, client and per-device storage:

| Kind | Integration | Client |
| --- | --- | --- |
| LG TV | `ui/LgIntegration.kt` | `device/LgClient.kt` |
| Apple TV | `ui/AppleTvIntegration.kt` | `device/companion/` |

## What the shell owns

Navigation (`Screen` in `ui/KaukosaadinApp.kt`), theme and layout preferences, the device picker,
the saved-device store (`device/SavedDevices.kt`), and the common device settings: rename and
forget.

## Adding a kind

1. Add its value to `DeviceKind` (`device/SavedDevices.kt`).
2. Write `ui/<Kind>Integration.kt`: an `object <Kind>Integration : DeviceIntegration` returning a
   `DeviceControls`, plus the kind's `Setup` section.
3. Register it in `DeviceIntegrations.all`.
4. Run the tests: `DeviceIntegrationsTest` fails until the kind is registered and every kind has
   exactly one integration.

No existing integration's controls are edited. A lighting kind is not a keypad: `RemoteKey`, whose
`lg`/`hid` columns map TV presses, is not extended for it — a kind that is not a TV brings its own
main screen instead of `RemoteScreen`/`RemoteKeys`.

## Forget

`DeviceStore.forget(id, clearKindState)` is the one forget path: it clears everything the kind
keeps for the device through the controls, then drops the saved device and its selection. A kind that
must clear credentials (a Hue bridge username/key, say) implements that in its `DeviceControls.forget()`;
it is then cleared from the same place as every other kind, so a kind never adds its own forget code
to the shell.
