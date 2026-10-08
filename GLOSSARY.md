# Kaukosaadin

Kaukosaadin turns an Android phone into a remote control for the devices on the home
network: Apple TVs and LG webOS TVs, and Philips Hue lights through a Hue Bridge, reached
directly over Wi-Fi with no cloud account in between.

## Devices

**Device**:
A thing the remote can drive: a TV, a set-top box, or a Hue Bridge (which the app drives as one
device for everything it holds). Every saved device is an LG TV, an Apple TV or a Hue Bridge.
_Avoid_: TV (as the umbrella noun, or when an Apple TV is meant), box, target, unit

**Kind**:
Which of the device families a saved device belongs to: LG TV, Apple TV or Hue Bridge. Each kind
has its own client, pairing and stored credentials.
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
The control that lists the saved devices and switches which one the remote drives; it also leads to
Add a device, the Devices screen and General settings.
_Avoid_: dropdown, selector, device list

**Devices screen**:
The screen listing every saved device: which one the remote drives, each one's Device settings, and forget.
_Avoid_: device manager, device list, my devices

**Default device**:
The saved device the remote opens on when the operator has not picked one: the last device driven while it is still saved, otherwise an Apple TV, otherwise the first saved device.
_Avoid_: fallback device, active device

## Remote and commanding

**Remote**:
The screen the app opens for the selected saved device and drives it through: the on-screen
keypad for a TV, the lighting screen for a bridge.
_Avoid_: main page, keypad (as the umbrella noun), control panel

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

## Super remote

**Preset**:
One of the Super remote's fixed lighting outcomes for the bound room or zone: Bright (on at 100%),
Dim (on at 20%) and Off (off).
_Avoid_: scene, mode, setting

**Shortcut**:
One of the two remembered Apple TV apps the Super remote launches in one tap.
_Avoid_: favorite, bookmark, quick launch

## Pairing and trust

**Pairing**:
Establishing the credentials that let the remote command one specific device, shown as one sheet
over Add a device. An LG TV starts by pinning the TV's certificate when the operator taps Connect
and, like an Apple TV, finishes with the PIN the device displays; a Hue Bridge finishes when the
operator presses its link button, which the sheet waits up to 30 seconds for.
_Avoid_: trust, setup, sync, provisioning

**PIN**:
The short code the device shows on its screen and the operator types into the phone to finish pairing.
_Avoid_: passcode, code, password

**Certificate pin**:
The stored identity of the TLS certificate the phone first trusted for a device, checked again on
every later connection. A bridge uses its public-key hash; a TV uses its certificate fingerprint.
_Avoid_: thumbprint, key hash

**Re-pair** (LG TV):
Pairing again with a saved LG TV, replacing its pinned certificate and pairing material. The button reads "Pair again".
_Avoid_: reconnect, re-trust

**Forget**:
Removing a saved device and clearing everything kept for it: its name and host and its pairing
material, plus, per kind, an LG TV's pinned certificate and wake settings or a bridge's app key,
certificate pin and favorites.
_Avoid_: remove, delete, clear, unpair

## Discovery

**Scan**:
A bounded Wi-Fi search for devices to add: every kind searches side by side and Add a device lists
LG TVs, Apple TVs and Hue Bridges together as each kind answers.
_Avoid_: discovery, search, browse

**Candidate**:
A device a scan returned, or the operator typed the address of, that is not saved yet. Picking one starts its pairing.
_Avoid_: result, found device, discovered device

**Manual address**:
An IPv4 address the operator types, under "Can't find your device?", for a device the scan did not
return: an LG TV or a Hue Bridge.
_Avoid_: manual entry, fallback

## Power

**Wake** (LG TV):
Sending Wake-on-LAN to bring a sleeping LG TV up. This is all the LG power key does; it never toggles standby.
_Avoid_: power on, turn on

**Sleep** (Apple TV):
The power-off press the Apple TV power key sends. There is no Apple TV wake.
_Avoid_: power off, shutdown

## Hue lighting

**Bridge**:
A Philips Hue Bridge: the box on the home LAN that holds the lights, rooms and zones and mints
this phone's app key. The app saves it as one device and drives everything it holds through it.
_Avoid_: hub, gateway, controller

**Light**:
An individual lamp the bridge reports, with its own on/off and brightness, controlled and favorited
on its own.
_Avoid_: bulb, lamp, device

**Room**:
A named set of lights the bridge groups for one space. The app controls a room as one unit through
its grouped light.
_Avoid_: group, area, space

**Zone**:
A named set of lights the bridge groups across spaces. Like a room, the app controls a zone as one
unit through its grouped light.
_Avoid_: group, area, region

**Grouped light**:
The bridge's aggregate for one room or zone: on when any member light is on, and its brightness
averaging the on members. It is what a room's or zone's switch and slider command.
_Avoid_: group, aggregate

**Favorite**:
A light, room or zone the operator marked to list first. Kept on the phone by id, not on the bridge.
_Avoid_: bookmark, star, pinned

**App key**:
The credential the bridge mints for this phone when the operator presses its link button; every
command carries it, and the phone stores it sealed.
_Avoid_: username, API key, token

**Live subscription**:
The open event stream from the bridge that reports changes made by other controllers, held while
the lighting screen is visible. It is read-only and never sends a command.
_Avoid_: websocket, poll, event feed

## Appearance and settings

**Theme**:
The app-wide palette: Classic or Hacker man.
_Avoid_: skin, colours

**Layout**:
The app-wide remote presentation: Standard (full casing, wheel and VFD), Debug (flat panels with a live status
log) or Super remote (app shortcuts, Apple TV controls and lighting on one screen).
_Avoid_: view mode, style

**General settings**:
App-wide settings shared by every device: the theme, the layout, and the reset that
clears everything for a fresh install.
_Avoid_: settings

**Device settings**:
The settings of one saved device: rename, forget, and for LG re-pair and wake settings.
_Avoid_: TV settings, per-device settings
