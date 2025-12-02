# EpicEFI Virtual Dash - Firmware

ESP32-S3 firmware for the EpicEFI Virtual Dashboard. Bridges BLE communication from the Android app to CAN bus for button inputs and ECU variable requests.

## Hardware

- **Board**: ESP32-S3-DevKitC-1
- **CAN Transceiver**: Connected to GPIO pins (speed control on GPIO 9)
- **RGB LED**: WS2812 on GPIO 38 (optional)

## Features

- **BLE Server**: Advertises as "ESP32 Dashboard"
- **Button Forwarding**: Receives 16-bit button mask via BLE, sends to CAN (0x711)
- **Variable Batching**: Receives multiple variable hash requests, queries ECU via CAN, returns batched response
- **High-Speed**: Optimized for low-latency communication

## BLE Characteristics

| UUID Suffix | Name | Direction | Description |
|-------------|------|-----------|-------------|
| `...a8` | Button | App → ESP32 | 2-byte button mask (little-endian) |
| `...a9` | VarData | ESP32 → App | Batched variable data |
| `...aa` | VarRequest | App → ESP32 | Batched variable hash requests |

## CAN Messages

### Button TX (0x711)
```
[0] 0x5A (header)
[1] 0x00 (reserved)
[2] 27 (category: buttonbox1)
[3] button mask high byte
[4] button mask low byte
```

### Variable Request TX (0x701)
```
[0-3] Variable hash (int32 big-endian)
```

### Variable Response RX (0x721)
```
[0-3] Variable hash (int32 big-endian)
[4-7] Value (float32 big-endian)
```

## Building

1. Install [PlatformIO](https://platformio.org/)
2. Open the `Firmware` folder in VS Code with PlatformIO extension
3. Build and upload:
   ```bash
   pio run -t upload
   ```

## Configuration

Edit `main.cpp` to change:
- `ECU_ID`: ECU identifier (default: 1)
- `CANBUS_BUTTONBOX_ADDRESS`: Button CAN ID (default: 0x711)
- CAN baud rate in `setupCan()` (default: 500kbps)
