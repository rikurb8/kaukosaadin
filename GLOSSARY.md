# Kaukosaadin

Kaukosaadin turns an Android phone into a remote control for the TVs on the home
network: Apple TVs and LG webOS TVs, reached directly over Wi-Fi with no cloud,
account or bridge in between.

## Devices

**Device**:
A TV or set-top box the remote can drive. Every saved device is either an LG TV or an Apple TV.
_Avoid_: TV (as the umbrella noun, or when an Apple TV is meant), box, target, unit

**Kind**:
Which of the two device families a saved device belongs to: LG TV or Apple TV. Each kind has its own client, pairing and stored credentials.
_Avoid_: type, platform, brand

**Saved device**:
A device the operator has paired and kept: a kind, a name and the host it was paired at.
_Avoid_: slot, entry, pairing

**Name**:
A saved device's display label, taken from the device's advertisement or typed by the operator. A bounded, sanitised label, not an identity.
_Avoid_: title, alias, label

**Host**:
The address a saved device was paired at, kept to display and to recognise the device in a scan.
_Avoid_: IP, endpoint, address

**Picker**:
The control that lists the saved devices and switches which one the remote drives.
_Avoid_: dropdown, selector, device list

**Default device**:
The saved device the remote opens on when the operator has not picked one: the last device driven while it is still saved, otherwise an Apple TV, otherwise the first saved device.
_Avoid_: fallback device, active device

## Remote and commanding

**Remote**:
The on-screen keypad that drives the selected saved device.
_Avoid_: main page, keypad, control panel

**Press**:
One command sent to the selected device: an arrow, OK, BACK/Menu, HOME/TV, Play/Pause or Volume -/+. A press is never queued or replayed.
_Avoid_: key, button, command, input

**Navigation**:
The presses that move around the device's own UI. On an LG TV navigation is disabled until registration succeeds.
_Avoid_: control, D-pad

**Connect** (LG TV):
The operation that registers the current app run with the TV. Arrows, OK and BACK stay disabled until it succeeds, and it says nothing about TV power.
_Avoid_: registration, ready, pair, reconnect

**Session** (Apple TV):
The verified encrypted link the remote opens on showing the remote screen and holds while it stays visible.
_Avoid_: connection, socket

## Pairing and trust

**Pairing**:
Establishing the credentials that let the remote command one specific device. For an LG TV this starts by pinning the TV's certificate; both kinds finish with the PIN the device displays.
_Avoid_: trust, setup, sync, provisioning

**PIN**:
The short code the device shows on its screen and the operator types into the phone to finish pairing.
_Avoid_: passcode, code, password

**Re-pair** (LG TV):
Pairing again with a saved LG TV, replacing its pinned certificate and pairing material.
_Avoid_: reconnect, re-trust

**Forget**:
Removing a saved device and clearing everything kept for it: its name and host, its pairing material, and for an LG TV the pinned certificate and wake settings.
_Avoid_: remove, delete, clear, unpair

## Discovery

**Scan**:
A bounded Wi-Fi search for devices to add, returning LG TVs and Apple TVs together.
_Avoid_: discovery, search, browse

**Manual address**:
An IPv4 address the operator types for an LG TV the scan did not return.
_Avoid_: manual entry, fallback

## Power

**Wake** (LG TV):
Sending Wake-on-LAN to bring a sleeping LG TV up. This is all the LG power key does; it never toggles standby.
_Avoid_: power on, turn on

**Sleep** (Apple TV):
The power-off press the Apple TV power key sends. There is no Apple TV wake.
_Avoid_: power off, shutdown

## Appearance and settings

**Theme**:
The app-wide palette: Classic or Hacker man.
_Avoid_: skin, colours

**Layout**:
The app-wide remote presentation: Standard (full casing, wheel and VFD) or Debug (flat panels with a live status log).
_Avoid_: view mode, style

**General settings**:
App-wide appearance settings, shared by every device.
_Avoid_: settings

**Device settings**:
The settings of one saved device: rename, forget, and for LG re-pair and wake settings.
_Avoid_: TV settings, per-device settings
