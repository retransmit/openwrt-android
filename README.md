# OpenWrt Manager

A native Android app for managing OpenWrt routers and access points, built with Jetpack Compose and Material 3.

It talks to the router over SSH only. Nothing has to be installed on the router: the app uses the `ubus` and `uci` tools that every OpenWrt system already has. LuCI and the web server are not required.

> Working title. The name and package id (`dev.openwrtmanager.app`) are placeholders.

## What it does today

**Devices**
- Save several routers and access points as profiles and switch between them.
- Log in with a password or a key. The app can create an ed25519 key and add it to the router so it no longer needs to keep the root password.
- First connection shows the device fingerprint for you to confirm. If it ever changes, the app stops and asks.

**Home**
- Live download and upload speed of the internet link, with a rolling graph.
- Wi-Fi networks with their bands and connected device counts, and a join code (QR) for each.
- Client count, load, memory, model, firmware and uptime.

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
  ssh/         SSH executor (sshj), host key policy, key generation
  router/      Read-only status queries over ubus
  uci/         Config snapshots and the uci batch builder
  apply/       Change sets and the safe apply sequence
  wifi/        Wi-Fi model, edits, wizards, steering, join codes
  connection/  Connection state and reconnect
  data/        Device profiles and encrypted secrets
  ui/          Compose screens and view models
```

Changes are built by pure functions from a snapshot of the current config, so they can be tested without a router. One class, `SafeApply`, is the only code that commits anything.

## Status and roadmap

| Phase | Scope | State |
|---|---|---|
| 1 | Device profiles, SSH, Home with live stats | Done |
| 2 | Wi-Fi management, guest and IoT wizards, join codes, band steering, safe apply | Done |
| 3 | VLAN wizard, including carrying guest networks to access points | Planned |
| 4 | Network, clients, firewall, packages, services, logs, system, a generic config editor and a terminal | Planned |

Known limits right now:

- Guest and IoT networks can only be created on a device that has its own internet link. On a plain access point the wizard explains this instead.
- Each device is managed on its own. There is no "apply to all my access points" yet.
- IPv6-only internet links are not detected as the uplink.
- Firmware upgrades are out of scope.

## License

Copyright (C) 2026 retransmit

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.

SPDX-License-Identifier: GPL-3.0-or-later

### Third-party software

The app is built on libraries under the Apache License 2.0 (AndroidX, Jetpack Compose, Kotlin, kotlinx, Hilt, sshj, ZXing) and the Bouncy Castle licence (Bouncy Castle), which remain under their own terms.

### Trademark

OpenWrt is a trademark of its owners. This project is independent and is not affiliated with, sponsored by, or endorsed by the OpenWrt project.
