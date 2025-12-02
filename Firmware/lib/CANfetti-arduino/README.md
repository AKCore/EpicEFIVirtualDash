# CANfetti :tada:

**CANfetti** is a lightweight Arduino library designed to simplify CAN (Controller Area Network) communications across **Teensy 4.x** and **ESP32** platforms. Whether you're sending periodic messages or building robust data logging systems, CANfetti offers a unified API that masks platform complexities.

---

## Features

1. **Cross-Platform CAN Handling**  
   - Automatically detects **Teensy 4.x** (FlexCAN) or **ESP32** (TWAI) and configures the appropriate backend.

2. **Simple Initialization**  
   - Easily set up the CAN bus with a single function call, specifying your desired bit rate.

3. **Versatile Frame Building**  
   - Build standard or extended CAN frames, set the RTR flag, and manage up to 8 bytes of data.

4. **Reliable Sending & Receiving**  
   - Send and receive CAN frames with configurable timeouts. Includes built-in debug outputs for quick diagnostics.

5. **Minimal Footprint**  
   - Designed with efficiency in mind, using minimal resources while handling all core CAN operations.

---

## Installation

1. **Library Manager (Recommended)**  
   - In the Arduino IDE, go to **Sketch** > **Include Library** > **Manage Libraries...**  
   - Search for **CANfetti** and click **Install**.

2. **Manual Installation**  
   - Clone or download [CANfetti-arduino](https://github.com/RyLeeHarrison/CANfetti-arduino) from GitHub.  
   - Move the `CANfetti-arduino` folder into your `Documents/Arduino/libraries/` directory.

---

## API Breakdown

The **CANfetti** library offers three main classes:

1. **CANfettiManager**: Manages the CAN controller (initialization, start/stop, send/receive).  
2. **CANfettiFrame**: Represents a CAN message (ID, flags, data).  
3. **CANfetti**: A helper class for building and customizing **CANfettiFrame** objects.

### 1. **CANfettiManager Class**

Responsible for initializing and managing CAN operations.

- **`bool init(uint32_t bitrate = 500000)`**  
  Initializes the CAN controller with the specified bit rate. Returns `true` if successful.

- **`void stop()`**  
  Stops the CAN controller, releasing any resources.

- **`bool sendMessage(const CANfettiFrame& message)`**  
  Sends a CAN message. Returns `true` on success.

- **`bool receiveMessage(CANfettiFrame& message, uint32_t timeout = 0)`**  
  Attempts to receive a CAN message. Optionally specify a `timeout` in milliseconds (ESP32 only). Returns `true` if a message was received.

- **`bool isRunning() const`**  
  Returns `true` if the CAN controller is currently running.

### 2. **CANfettiFrame Class**

A simple container for CAN message data.

- **Members**  
  - `uint32_t id`: The CAN ID (standard or extended).  
  - `uint8_t len`: Length of the CAN data payload (0–8).  
  - `uint8_t buf[8]`: The 8-byte CAN data buffer.  
  - `struct flags { bool extended; bool remote; }`: Frame type flags:
    - `extended` — set `true` for extended (29-bit) IDs.  
    - `remote` — set `true` for remote transmission request (RTR) frames.

### 3. **CANfetti Class**

A utility class to build **CANfettiFrame** objects with a fluent interface.

- **`CANfetti& setId(uint32_t id)`**  
  Sets the CAN ID.

- **`CANfetti& setExtendedFrame(bool isExtended)`**  
  Marks the frame as extended or standard.

- **`CANfetti& setRemoteTransmissionRequest(bool isRtr)`**  
  Marks the frame as an RTR frame if `true`.

- **`CANfetti& setDataLength(uint8_t length)`**  
  Sets the length of the CAN data (up to 8).

- **`CANfetti& setData(const uint8_t* data, uint8_t length)`**  
  Copies the provided data into the frame buffer. Automatically caps at 8 bytes.

- **`CANfettiFrame build() const`**  
  Finalizes and returns the built frame.

---

## Supported Platforms

- **Teensy 4.0 / 4.1**  
  - Uses [FlexCAN_T4](https://github.com/tonton81/FlexCAN_T4).  
  - Automatically handled via the **`USE_FLEXCAN`** define.

- **ESP32**  
  - Uses [TWAI driver](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/peripherals/twai.html).  
  - Automatically handled via the **`USE_TWAI`** define.

> **Note:** Other boards are currently **not** supported.

---

## Examples

Below are minimal examples to get you started. For more detailed use cases, check out the [examples folder](https://github.com/RyLeeHarrison/CANfetti-arduino/examples).

### 1. Basic Initialization

```cpp
#include <Arduino.h>
#include <CANfetti.hpp>

CANfettiManager canManager;

void setup() {
  Serial.begin(115200);

  // Initialize CAN at 500 kbps
  if (canManager.init(500000)) {
    Serial.println("CAN initialized successfully!");
  } else {
    Serial.println("Failed to initialize CAN.");
  }
}

void loop() {
  // ...
}
```

---

### 2. Sending a CAN Message

```cpp
#include <Arduino.h>
#include <CANfetti.hpp>

CANfettiManager canManager;

void setup() {
  Serial.begin(115200);
  canManager.init(500000);
}

void loop() {
  // Build a simple CAN message with ID 0x123 and 3 bytes of data
  CANfettiFrame frame = CANfetti()
                          .setId(0x123)
                          .setDataLength(3)
                          .setData((const uint8_t*)"\x01\x02\x03", 3)
                          .build();

  bool success = canManager.sendMessage(frame);

  Serial.println(success ? "Message sent!" : "Message failed!");

  delay(1000);
}
```

---

### 3. Receiving a CAN Message

```cpp
#include <Arduino.h>
#include <CANfetti.hpp>

CANfettiManager canManager;
CANfettiFrame incomingFrame;

void setup() {
  Serial.begin(115200);
  canManager.init(500000);
}

void loop() {
  // Non-blocking check for a new CAN message
  if (canManager.receiveMessage(incomingFrame)) {
    Serial.print("Received CAN ID: 0x");
    Serial.println(incomingFrame.id, HEX);

    Serial.print("Data Length: ");
    Serial.println(incomingFrame.len);

    Serial.print("Data Bytes: ");
    for (int i = 0; i < incomingFrame.len; i++) {
      Serial.print(incomingFrame.buf[i], HEX);
      Serial.print(" ");
    }
    Serial.println();
  }

  // Continue with other tasks...
  delay(10);
}
```

---

### 4. Sending an Extended Frame

```cpp
#include <Arduino.h>
#include <CANfetti.hpp>

CANfettiManager canManager;

void setup() {
  Serial.begin(115200);
  canManager.init(500000);
}

void loop() {
  // Build an extended CAN message with ID 0x1ABCDE
  CANfettiFrame frame = CANfetti()
                          .setId(0x1ABCDE)
                          .setExtendedFrame(true)
                          .setDataLength(4)
                          .setData((const uint8_t*)"\xAA\xBB\xCC\xDD", 4)
                          .build();

  canManager.sendMessage(frame);

  delay(1000);
}
```

---

### 5. Stopping the CAN Interface

```cpp
#include <Arduino.h>
#include <CANfetti.hpp>

CANfettiManager canManager;

void setup() {
  Serial.begin(115200);
  if (canManager.init(500000)) {
    Serial.println("CAN running...");
  }
}

void loop() {
  // Stop CAN after 5 seconds
  if (millis() > 5000 && canManager.isRunning()) {
    canManager.stop();
    Serial.println("CAN has been stopped.");
  }
}
```

---

## Troubleshooting

- **Message Not Sending**  
  - Ensure your transceiver wiring is correct and that the bus has proper termination (120Ω resistor on each end).  
  - Check that the bit rate matches all other devices on the CAN bus.

- **Platform Unrecognized**  
  - This library requires Teensy 4.x or ESP32. If you're on an unsupported board, compilation will fail with an error.

- **Timeout or No Messages Received**  
  - Confirm that another CAN node is actively sending data and that your wiring is correct.  
  - On ESP32, verify the TX/RX pin assignments (`GPIO_NUM_9` / `GPIO_NUM_10` by default in the library, update as needed).

---

## License

This library is distributed under the MIT License. See the [LICENSE](https://github.com/RyLeeHarrison/CANfetti-arduino/blob/main/LICENSE) file for details.
