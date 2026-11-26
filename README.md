# OpenWrt Manager

A native Android app for managing OpenWrt routers and access points, built with Jetpack Compose and Material 3.

It talks to the router over SSH only. Nothing has to be installed on the router: the app uses the `ubus` and `uci` tools that every OpenWrt system already has. LuCI and the web server are not required.

> Working title: the name may still change. The app id is `dev.openwrtapp.app` (from the openwrtapp.dev domain); it is permanent once published.

## Install

Download the APK from the [latest release](https://github.com/retransmit/openwrt-android/releases/latest) and open it on your phone (Android 8.0 or newer). You may need to allow installing apps from your browser or file manager.

Release APKs are signed with this certificate (SHA-256):

```
a9:c8:21:2d:9c:bc:eb:f9:ba:fc:95:ff:25:ef:ae:82:21:94:f7:52:54:77:bf:e9:55:e3:f9:b7:0a:8b:39:74
```

If you installed a development build before, uninstall it first: it is signed with a different key, so Android will not update it in place.

## What it does today

**Devices**
- Save several routers and access points as profiles and switch between them.
- Log in with a password or a key. The app can create an ed25519 key and add it to the router so it no longer needs to keep the root password.
- First connection shows the device fingerprint for you to confirm. If it ever changes, the app stops and asks.

**Home**
- Live download and upload speed of the internet link. The graph shows download above a centre line and upload below it, on a labelled scale; press and drag to read any moment of the last two minutes.
- How long the router takes to answer, next to its online status.
- Uptime, clients, load, memory and flash usage, plus model and firmware.
- Wi-Fi networks with their bands and connected device counts, and a join code (QR) for each.

**Wi-Fi**
- Edit networks: name, password, security (WPA2, WPA2/WPA3, WPA3, open), bands, hidden, client isolation, on or off.
- Add and remove networks.
- Radio settings: channel, channel width, transmit power, on or off.
- Join codes: show a QR code for any network and share it as an image.
- Band steering: one network name across bands with roaming assistance (802.11k and 802.11v), optional fast roaming (802.11r), and a button to start the `usteer` daemon.

**Guest and IoT networks in one step**
- A guided flow creates a separate network with its own address range, DHCP pool, firewall zone and Wi-Fi, then shows the join code.
- Guest devices reach the internet but not your home network or the router's settings.
- IoT devices cannot start connections to your home network. Whether your own devices may reach them is your choice.
- The address range is picked so it does not overlap anything already in use on the device.
- On a router with a DSA switch, each network is its own VLAN on the LAN bridge (guest 10, IoT 20, others 30 and up), the way OpenWrt documents it. The first one turns VLAN filtering on with every port still on your home network.
- An optional step puts the network on ports: a device port (untagged) for anything plugged into it, or an access point link (tagged).
- After applying, the app checks that the new network is up and that DHCP is serving it.

**VLANs**
- The Network tab lists the VLANs on the LAN bridge with their address range and ports, and lets you set each port to none, device port or access point link.
- Create a VLAN with its own address range and internet access, or one that only passes through to another device.
- Changes that would leave your home network with no port and no Wi-Fi are refused. A device port whose VLAN goes away returns to your home network.
- On an access point, "Add a network from my router" reads the router's guest and IoT networks and puts the same Wi-Fi names on the same VLANs, tagged on the uplink port. The router keeps doing DHCP and the firewall.

**Network**
- Interfaces with their state, addresses, traffic and uptime.
- DHCP pools: change the range and lease time of each network.
- Address reservations: give a device the same address every time.

**Clients**
- Every device the router knows about, wired and wireless, merged from DHCP leases, the Wi-Fi association lists and the router's neighbour table, with names from LuCI's host hints.
- Search by name, address or MAC, and filter by Wi-Fi, wired or blocked.
- Reconnect a Wi-Fi device, block a device from the internet straight away, or reserve its address under a name of your choice.

**Firewall**
- Port forwards from the internet: add, edit, turn on or off, remove.
- Traffic rules: turn OpenWrt's standard rules and your own on or off, or remove them.
- Zones, with their networks and what they allow in, out and between.

**System and maintenance**
- Device name and time zone; change the root password (the app updates its own saved login to match); reboot.
- Services: start, stop, restart, enable and disable. Services the app needs to reach the router (dropbear, network, rpcd) cannot be stopped or disabled from the app.
- Live system and kernel logs with search.
- Packages: list, search, install and remove (apk or opkg). Packages the system needs are protected.
- Backup to your phone and restore from it, with a warning when a backup came from a different device.

**Config editor**
- Every config file on the router, section by section. Change, add and remove options and sections; all edits are reviewed and applied together with the undo timer. Passwords and keys stay hidden until you open them.

**Terminal**
- A full interactive shell on the router (xterm-256color), with a key row for Esc, Ctrl, Tab, arrows and the like. Commands here run directly and are not covered by the undo timer.

## Changing a router safely

Every change to router config goes through the same path:

1. You see what will change in plain words, and can expand the exact `uci` changes (passwords hidden).
2. The app refuses to continue if another tool, such as LuCI, has unsaved changes on the router.
3. The router starts a 90 second undo timer, then saves and applies the change.
4. The app reconnects and confirms. If it cannot reach the router in time, the router restores the previous config by itself.

The result screen always says what actually happened: applied, undone, refused, or could not be confirmed.

## Security

- Passwords and private keys are encrypted with a key held in the Android Keystore and are excluded from device backups.
- Secrets are never logged and never appear in the text form of app state.
- Every value placed into a shell command is quoted; identifiers are validated.
- There are no accounts, no cloud services, no analytics and no ads.

## Requirements

- Android 8.0 (API 26) or newer.
- OpenWrt 21.02 or newer (DSA). Developed against 24.10, 25.12 and snapshot builds.
- SSH access as `root` (dropbear, the OpenWrt default).
- The phone must be able to reach the router's SSH port: the same network, or a VPN you already use.

## Building

You need the Android SDK (platform 37) and JDK 21. Android Studio provides both.

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

A release build is signed with the release key. Put its location and passwords in your own `~/.gradle/gradle.properties` (never in this repository):

```
OWA_KEYSTORE=/path/to/release.jks
OWA_KEY_ALIAS=openwrtapp
OWA_KEYSTORE_PASSWORD=...
OWA_KEY_PASSWORD=...
```

```bash
./gradlew assembleRelease
```

Without those settings a release build stops with a message instead of producing an unsigned APK.

## Testing

```bash
./gradlew testDebugUnitTest
```

runs the unit tests on your computer. They cover the SSH client (against an in-process SSH server), all config parsing, every change builder, the safe apply sequence and the view models.

```bash
./gradlew connectedDebugAndroidTest
```

runs the on-device tests (Keystore storage, key generation) on a connected phone or emulator.

### Optional tests against a real router

These are skipped unless you set both variables. They only read status, plus one test that exercises the save and undo sequence inside a scratch folder under `/tmp` on the router. The router's real config is never changed.

| Variable | Meaning |
|---|---|
| `OWM_LIVE_HOSTS` | Comma separated router addresses |
| `OWM_LIVE_KEY` | Path to a private key that `root` accepts on those routers |

## How it is put together

```
app/src/main/java/dev/openwrtmanager/app/
  ssh/         SSH executor (sshj), interactive shell, host key policy, key generation
  router/      Read-only status queries over ubus
  uci/         Config snapshots and the uci batch builder
  apply/       Change sets, the safe apply sequence and the health check
  wifi/        Wi-Fi model, edits, wizards, steering, join codes
  vlan/        VLAN layout, port roles and the VLAN planner
  clients/     Client list, blocking, reservations and DHCP pools
  firewall/    Port forwards, traffic rules and zones
  admin/       Services, logs, system settings, packages and backup
  editor/      Generic config editor model
  terminal/    Terminal session on top of the Termux emulator
  connection/  Connection state and reconnect
  data/        Device profiles and encrypted secrets
  ui/          Compose screens and view models; ui/kit holds the shared components
```

Changes are built by pure functions from a snapshot of the current config, so they can be tested without a router. One class, `SafeApply`, is the only code that commits anything.

## Status and roadmap

| Phase | Scope | State |
|---|---|---|
| 1 | Device profiles, SSH, Home with live stats | Done |
| 2 | Wi-Fi management, guest and IoT wizards, join codes, band steering, safe apply | Done |
| 3 | VLANs: guest and IoT as VLANs, port roles, carrying networks to access points, health check | Done |
| 4 | Network, clients, firewall, packages, services, logs, system, a generic config editor and a terminal | Done |
| 5 | Material 3 redesign, readable speed graph, app icon, first release (0.1.0) | Done |

Known limits right now:

- Guest and IoT networks are created on the device that has its own internet link. On a plain access point, "Add a network from my router" carries them there instead.
- VLAN features need a DSA switch. Older swconfig devices keep the separate-bridge layout and the VLAN screens are hidden.
- "Add a network from my router" has been tested in code but not yet on a real access point.
- Each device is managed on its own. There is no "apply to all my access points" yet.
- IPv6-only internet links are not detected as the uplink.
- Firmware upgrades are out of scope.
- 0.1.0 is a pre-release: blocking a device, port forwards, restoring a backup, installing packages and changing the root password have so far only been checked on test setups.
- On routers that hand out IPv4 addresses with odhcpd instead of dnsmasq, the client count on Home can read low; the Clients tab finds those devices.

## License

Copyright (C) 2026 retransmit

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.

SPDX-License-Identifier: GPL-3.0-or-later

### Third-party software

The app is built on libraries under the Apache License 2.0 (AndroidX, Jetpack Compose, Kotlin, kotlinx, Hilt, sshj, ZXing, the Termux terminal emulator) and the Bouncy Castle licence (Bouncy Castle), and bundles the JetBrains Mono font under the SIL Open Font License 1.1 (its licence is in `app/src/main/assets/licenses/`). All remain under their own terms.

### Trademark

OpenWrt is a trademark of its owners. This project is independent and is not affiliated with, sponsored by, or endorsed by the OpenWrt project.
