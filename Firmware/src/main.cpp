#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <CANfetti.hpp>

#define RGB_PIN 38       // WS2812 data pin

#define TS_HW_BUTTONBOX1_CATEGORY 27 // hardware button box 1
#define CANBUS_BUTTONBOX_ADDRESS 0x711 // CANBUS BUTTONBOX TX

// CAN Variable Protocol
#define ECU_ID 1
#define CAN_VAR_REQUEST_BASE 0x700  // TX: Request variable (0x700 + ecuId)
#define CAN_VAR_RESPONSE_BASE 0x720 // RX: Variable broadcast (0x720 + ecuId)

// BLE UUIDs - must match Android app
#define SERVICE_UUID           "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_BUTTON_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"  // Write buttons (no response)
#define CHAR_VAR_DATA_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26a9"  // Notify var data
#define CHAR_VAR_REQUEST_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26aa"  // Write var request

// Forward declarations
void setupCan();
void setupBLE();
void sendButtonCanFrame(uint16_t buttonMask);
void requestCanVariable(int32_t varHash);
void processCanRx();
void logMessage(const String& message);

// Big-endian helpers
static inline void writeInt32BigEndian(int32_t value, uint8_t* out) {
    out[0] = (uint8_t)((value >> 24) & 0xFF);
    out[1] = (uint8_t)((value >> 16) & 0xFF);
    out[2] = (uint8_t)((value >> 8) & 0xFF);
    out[3] = (uint8_t)(value & 0xFF);
}

static inline int32_t readInt32BigEndian(const uint8_t* in) {
    return ((int32_t)in[0] << 24) | ((int32_t)in[1] << 16) | 
           ((int32_t)in[2] << 8) | (int32_t)in[3];
}

static inline float readFloat32BigEndian(const uint8_t* in) {
    union { float f; uint32_t u; } conv;
    conv.u = ((uint32_t)in[0] << 24) | ((uint32_t)in[1] << 16) |
             ((uint32_t)in[2] << 8) | (uint32_t)in[3];
    return conv.f;
}

// Globals
CANfettiManager canManager;
BLEServer* pServer = nullptr;
BLECharacteristic* pButtonChar = nullptr;
BLECharacteristic* pVarDataChar = nullptr;
BLECharacteristic* pVarRequestChar = nullptr;
bool deviceConnected = false;
bool oldDeviceConnected = false;
uint16_t lastButtonMask = 0;

// Batched variable request queue
#define MAX_BATCH_VARS 16
int32_t pendingVarHashes[MAX_BATCH_VARS];
uint8_t pendingVarCount = 0;
uint8_t pendingVarIndex = 0;

// Batched response buffer (up to 16 vars * 8 bytes = 128 bytes)
#define VAR_RESPONSE_SIZE 8  // 4 bytes hash + 4 bytes value
uint8_t batchResponseBuffer[MAX_BATCH_VARS * VAR_RESPONSE_SIZE];
uint8_t batchResponseCount = 0;

// BLE Server Callbacks
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    logMessage("BLE device connected");
  }

  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    logMessage("BLE device disconnected");
  }
};

// Button Characteristic Callbacks - receives button data from Android app
class ButtonCharCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pCharacteristic) override {
    std::string value = pCharacteristic->getValue();
    if (value.length() >= 2) {
      // Receive 16-bit button mask (2 bytes, little-endian)
      uint16_t buttonMask = (uint8_t)value[0] | ((uint8_t)value[1] << 8);
      // Only send if changed (debounce rapid presses)
      if (buttonMask != lastButtonMask) {
        lastButtonMask = buttonMask;
        sendButtonCanFrame(buttonMask);
      }
    } else if (value.length() == 1) {
      // Single byte for simple button ID (0-15)
      uint8_t buttonId = (uint8_t)value[0];
      uint16_t buttonMask = (1 << buttonId);
      if (buttonMask != lastButtonMask) {
        lastButtonMask = buttonMask;
        sendButtonCanFrame(buttonMask);
      }
    }
  }
};

// Variable Request Callbacks - receives var hash requests from Android app
// Now supports batched requests: multiple 4-byte hashes in one write
class VarRequestCharCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pCharacteristic) override {
    std::string value = pCharacteristic->getValue();
    size_t len = value.length();
    
    if (len >= 4) {
      // Parse multiple 4-byte hashes
      pendingVarCount = 0;
      pendingVarIndex = 0;
      batchResponseCount = 0;
      
      for (size_t i = 0; i + 4 <= len && pendingVarCount < MAX_BATCH_VARS; i += 4) {
        pendingVarHashes[pendingVarCount++] = readInt32BigEndian((const uint8_t*)value.data() + i);
      }
      
      // Start requesting first variable
      if (pendingVarCount > 0) {
        requestCanVariable(pendingVarHashes[0]);
        logMessage(String("Batch request: ") + String(pendingVarCount) + " vars");
      }
    }
  }
};

// Send unique button ID(s) over CAN
void sendButtonCanFrame(uint16_t buttonMask) {
  uint8_t data[8];
  data[0] = 0x5A;                                      // Header byte
  data[1] = 0x00;                                      // Reserved
  data[2] = TS_HW_BUTTONBOX1_CATEGORY;                 // Category ID
  data[3] = static_cast<uint8_t>((buttonMask >> 8) & 0xFF);  // High byte
  data[4] = static_cast<uint8_t>(buttonMask & 0xFF);         // Low byte

  CANfettiFrame frame = CANfetti()
                          .setId(CANBUS_BUTTONBOX_ADDRESS)
                          .setDataLength(5)
                          .setData(data, 5)
                          .build();

  bool ok = canManager.sendMessage(frame);
  if (ok) {
    logMessage(String("CAN TX 0x711 mask=0x") + String(buttonMask, HEX));
  }
}

// Request a variable from ECU via CAN
void requestCanVariable(int32_t varHash) {
  uint8_t data[8] = {0};
  writeInt32BigEndian(varHash, data);

  CANfettiFrame frame = CANfetti()
                          .setId(CAN_VAR_REQUEST_BASE + ECU_ID)
                          .setDataLength(4)
                          .setData(data, 4)
                          .build();

  bool ok = canManager.sendMessage(frame);
  if (ok) {
    logMessage(String("CAN TX var request hash=") + String(varHash));
  }
}

// Process incoming CAN messages and forward variable data to BLE
void processCanRx() {
  CANfettiFrame frame;
  while (canManager.receiveMessage(frame, 0)) {
    // Check if this is a variable response from ECU
    if (frame.id == (CAN_VAR_RESPONSE_BASE + ECU_ID) && frame.len >= 8) {
      if (deviceConnected && pVarDataChar != nullptr) {
        // Add to batch response buffer
        if (batchResponseCount < MAX_BATCH_VARS) {
          memcpy(batchResponseBuffer + (batchResponseCount * VAR_RESPONSE_SIZE), frame.buf, 8);
          batchResponseCount++;
        }
        
        // Move to next pending variable
        pendingVarIndex++;
        
        if (pendingVarIndex < pendingVarCount) {
          // Request next variable
          requestCanVariable(pendingVarHashes[pendingVarIndex]);
        } else {
          // All variables received - send batched response
          if (batchResponseCount > 0) {
            pVarDataChar->setValue(batchResponseBuffer, batchResponseCount * VAR_RESPONSE_SIZE);
            pVarDataChar->notify();
            logMessage(String("BLE TX batch: ") + String(batchResponseCount) + " vars");
          }
          // Reset for next batch
          pendingVarCount = 0;
          pendingVarIndex = 0;
          batchResponseCount = 0;
        }
      }
    }
  }
}

// Legacy sendCMD
void sendCMD(uint16_t buttonMask) {
  sendButtonCanFrame(buttonMask);
}

void setup() {
  Serial.begin(115200);
  
  // CAN transceiver control pin
  pinMode(9, OUTPUT);
  digitalWrite(9, LOW); // LOW = high speed mode
  
  // Initialize subsystems
  setupCan();
  setupBLE();
  
  logMessage("Setup complete - BLE Dashboard ready");
}

void loop() {
  // Process CAN RX messages - check frequently
  processCanRx();
  
  // Handle BLE connection state changes
  if (!deviceConnected && oldDeviceConnected) {
    delay(500);
    pServer->startAdvertising();
    logMessage("BLE advertising restarted");
    oldDeviceConnected = deviceConnected;
  }
  
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }
  
  // Yield to other tasks without blocking - maximum responsiveness
  yield();
}

void setupCan() {
  canManager.init(500000);
  logMessage("CAN initialized at 500kbps");
}

void setupBLE() {
  BLEDevice::init("ESP32 Dashboard");
  
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  
  // Create service with enough handles for 3 characteristics
  BLEService* pService = pServer->createService(BLEUUID(SERVICE_UUID), 15);
  
  // Button characteristic - write only, no response for speed
  pButtonChar = pService->createCharacteristic(
    CHAR_BUTTON_UUID,
    BLECharacteristic::PROPERTY_WRITE_NR  // Write without response for speed
  );
  pButtonChar->setCallbacks(new ButtonCharCallbacks());
  
  // Variable data characteristic - notify only (ESP32 -> Android)
  pVarDataChar = pService->createCharacteristic(
    CHAR_VAR_DATA_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pVarDataChar->addDescriptor(new BLE2902());
  
  // Variable request characteristic - write only (Android -> ESP32)
  pVarRequestChar = pService->createCharacteristic(
    CHAR_VAR_REQUEST_UUID,
    BLECharacteristic::PROPERTY_WRITE_NR
  );
  pVarRequestChar->setCallbacks(new VarRequestCharCallbacks());
  
  pService->start();
  
  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  BLEDevice::startAdvertising();
  
  logMessage("BLE server started, advertising as 'ESP32 Dashboard'");
}

void logMessage(const String& message) {
  Serial.println(String(millis()) + "ms: " + message);
}
