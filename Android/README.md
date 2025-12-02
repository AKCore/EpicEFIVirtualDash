# EpicEFI Virtual Dash - Android App

Android dashboard application for EpicEFI ECU. Displays real-time ECU variables, GPS speed, and provides customizable button controls.

## Features

### Dashboard
- **GPS Speed Gauge**: Real-time speed from device GPS (optional)
- **ECU Variable Gauges**: Display any ECU variable by hash
  - 2 large gauges (top row)
  - 4 smaller gauges (secondary row)
- **Customizable Buttons**: Up to 16 buttons with labels and modes
- **Foldable Device Support**: Adapts layout for folding phones

### Button Modes
- **Momentary**: Press and hold (sends on press, off on release)
- **Toggle**: Tap to toggle state (visual feedback, sends single pulse)

### Settings
- **Data Rate**: 1-60 Hz polling rate (adjustable for device capability)
- **Speed Unit**: MPH, KMH, or m/s
- **Button Configuration**: Custom labels and modes per button
- **Gauge Configuration**: Add/remove ECU variables, set positions

## Requirements

- Android 8.0+ (API 26)
- Bluetooth LE support
- Location permission (for GPS speed and BLE scanning)

## Building

1. Open `Android` folder in Android Studio
2. Sync Gradle
3. Build and run on device

## Architecture

```
app/src/main/java/com/buttonbox/ble/
├── MainActivity.kt      # Main dashboard UI
├── SettingsActivity.kt  # Configuration screen
├── SplashActivity.kt    # Startup with disclaimer
├── BleManager.kt        # BLE communication
└── data/
    ├── SettingsManager.kt    # Persistent settings
    ├── VariableRepository.kt # ECU variable definitions
    └── EcuVariable.kt        # Data models
```

## BLE Protocol

Connects to ESP32 Dashboard device and communicates via:
- **Button Characteristic**: Send 16-bit button mask
- **Variable Request**: Send batched variable hashes
- **Variable Data**: Receive batched variable values

## Customization

### Adding ECU Variables
Edit `variables.json` to add new variables with:
```json
{
  "name": "Variable Name",
  "hash": 12345678,
  "source": "EpicEFI"
}
```

### Theming
Colors defined in `res/values/colors.xml`:
- `dashboard_bg`: Background color
- `accent_orange`: Accent/highlight color
- `card_dark`: Card background
