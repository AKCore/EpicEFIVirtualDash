# EpicEFI Virtual Dash

A Bluetooth Low Energy (BLE) virtual dashboard system for **EpicEFI** ECUs. Displays real-time engine data and provides wireless button inputs for ECU control.

![EpicEFI](logo.png)

## Build Status

| Component | Status |
|-----------|--------|
| **Android** | [![Android](https://github.com/AKCore/EpicEFIVirtualDash/actions/workflows/build-android.yml/badge.svg)](https://github.com/AKCore/EpicEFIVirtualDash/actions/workflows/build-android.yml) |
| **Firmware** | [![Firmware](https://github.com/AKCore/EpicEFIVirtualDash/actions/workflows/build-firmware.yml/badge.svg)](https://github.com/AKCore/EpicEFIVirtualDash/actions/workflows/build-firmware.yml) |
| **iOS** | [![iOS](https://github.com/AKCore/EpicEFIVirtualDash/actions/workflows/build-ios.yml/badge.svg)](https://github.com/AKCore/EpicEFIVirtualDash/actions/workflows/build-ios.yml) |

## Overview

| Component | Description |
|-----------|-------------|
| [**Firmware**](Firmware/) | ESP32-S3 BLE-to-CAN bridge |
| [**Android App**](Android/) | Dashboard with gauges and buttons |
| [**iOS App**](iOS/) | Dashboard with gauges and buttons |

⚠️ **OFF-ROAD USE ONLY** - This application is designed for competition and off-road use.

## Quick Start

1. **Flash Firmware** to ESP32-S3 (see [Firmware README](Firmware/README.md))
2. **Install App** - [Android](Android/README.md) or [iOS](iOS/README.md)
3. **Connect** - App auto-connects to "ESP32 Dashboard"
4. **Configure** - Set up gauges and buttons in Settings

## Features

### Mobile Apps (Android & iOS)
- **Real-Time Gauges** - GPS speed + ECU variables (up to 60 Hz)
- **Customizable Buttons** - 1-16 buttons, momentary or toggle mode
- **Two-Tier Layout** - 2 large + 4 small gauges
- **Dark Theme** - Automotive-style UI with orange accents

### Firmware
- **BLE Server** - Low-latency communication
- **Batched Requests** - Multiple variables per BLE packet
- **CAN Bridge** - Buttons TX, variables RX

## BLE Protocol

### Service UUID
`4fafc201-1fb5-459e-8fcc-c5c9c331914b`

### Characteristics

| UUID | Name | Direction | Description |
|------|------|-----------|-------------|
| `...a8` | Button | Android → ESP32 | 2-byte button mask (little-endian) |
| `...a9` | VarData | ESP32 → Android | Batched: N × 8-byte entries [hash(4) + value(4)] big-endian |
| `...aa` | VarRequest | Android → ESP32 | Batched: N × 4-byte hashes (big-endian) |

### Batched Variable Protocol
For higher data rates, variables are requested and returned in batches:
1. **Android** sends multiple 4-byte hashes in one BLE write
2. **ESP32** requests each variable from ECU via CAN sequentially
3. **ESP32** collects all responses and sends one batched BLE notification
4. **Android** parses multiple 8-byte entries from the notification

This reduces BLE round-trips from N to 1 per update cycle.

## CAN Protocol

### Button TX (0x711)
| Byte | Description |
|------|-------------|
| 0 | Header (0x5A) |
| 1 | Reserved (0x00) |
| 2 | Category ID (27) |
| 3 | Button mask high byte |
| 4 | Button mask low byte |

### Variable Request TX (0x700 + ecuId)
| Byte | Description |
|------|-------------|
| 0-3 | VarHash (int32 big-endian) |

### Variable Response RX (0x720 + ecuId)
| Byte | Description |
|------|-------------|
| 0-3 | VarHash (int32 big-endian) |
| 4-7 | Value (float32 big-endian) |

## Building

### GitHub Actions (CI/CD)
Automated builds run on push to `main`. Releases are created on version tags.

**Required Secrets** (Settings → Secrets → Actions):

**Android:**
| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded Android keystore (`base64 keystore.jks`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Signing key password |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Google Play API service account JSON |

**iOS:**

To enable iOS builds, set the repository variable `IOS_BUILD_ENABLED` to `true` (Settings → Variables → Repository variables).

| Secret | Description |
|--------|-------------|
| `IOS_BUILD_CERTIFICATE_BASE64` | Base64-encoded .p12 distribution certificate |
| `IOS_P12_PASSWORD` | Password for .p12 certificate |
| `IOS_KEYCHAIN_PASSWORD` | Temporary keychain password (any value) |
| `IOS_PROVISIONING_PROFILE_BASE64` | Base64-encoded .mobileprovision file |
| `IOS_TEAM_ID` | Apple Developer Team ID |
| `APP_STORE_CONNECT_API_KEY_ID` | App Store Connect API Key ID |
| `APP_STORE_CONNECT_API_ISSUER_ID` | App Store Connect Issuer ID |
| `APP_STORE_CONNECT_API_KEY_BASE64` | Base64-encoded .p8 API key |

**Creating a Release:**

1. Update the `VERSION` file with the new version number
2. Go to Actions → Build → Run workflow
3. Check "Create a GitHub release" checkbox
4. Click "Run workflow"

The release will be created with the version from the `VERSION` file.

### Local Build

#### Firmware (PlatformIO)
```bash
cd Firmware
pio run
pio run -t upload
```

#### Android App (Android Studio)
1. Open `Android/` folder in Android Studio
2. Sync Gradle
3. Build and run on device

#### iOS App (Xcode)
1. Open `iOS/EpicDash/EpicDash.xcodeproj` in Xcode
2. Set your development team in Signing & Capabilities
3. Build and run on device (BLE requires physical device)

## Hardware

- **ESP32-S3** DevKitC-1
- CAN transceiver: TX=GPIO10, RX=GPIO11
- CAN mode pin: GPIO9 (LOW=high speed)

## Permissions (Android)

- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` - BLE
- `ACCESS_FINE_LOCATION` - GPS speedometer & BLE scanning

## Variables

The `variables.json` file contains ECU variable definitions with:
- `name` - Variable name
- `hash` - Int32 hash for CAN protocol
- `source` - "output" (live data) or "config"

Common dashboard variables:
- `AFRValue` - Air/Fuel Ratio
- `baroPressure` - Barometric pressure
- `baseIgnitionAdvance` - Ignition timing
- `boostboostOutput` - Boost control output
