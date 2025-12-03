#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <CANfetti.hpp>

#ifdef CORE_DEBUG_LEVEL
#undef CORE_DEBUG_LEVEL
#endif

#define CORE_DEBUG_LEVEL 3
#define LOG_LOCAL_LEVEL ESP_LOG_DEBUG

#define RGB_PIN 38       // WS2812 data pin
#define ADC1_PIN 5       // Hardware ADC input for ADC1 (GPIO 5)

// Hardware ADC configuration
#define ADC1_SAMPLE_INTERVAL_MS 10   // Sample every 50ms (20 Hz)
#define ADC1_FILTER_SAMPLES 1        // Number of samples for averaging filter (1 = no filter)
#define ADC1_CHANGE_THRESHOLD 5      // Minimum change to trigger CAN send

#define TS_HW_BUTTONBOX1_CATEGORY 27 // hardware button box 1
#define CANBUS_BUTTONBOX_ADDRESS 0x711 // CANBUS BUTTONBOX TX

// CAN Variable Protocol
#define ECU_ID 1
#define CAN_VAR_REQUEST_BASE 0x700  // TX: Request variable (0x700 + ecuId)
#define CAN_VAR_RESPONSE_BASE 0x720 // RX: Variable broadcast (0x720 + ecuId)
#define CAN_GPS_DATA_BASE 0x780     // TX: GPS data to ECU (0x780 + ecuId)

// GPS variable hashes (for CAN transmission to ECU)
const int32_t VAR_HASH_GPS_HMSD_PACKED = 703958849;       // Hours, minutes, seconds, days (packed)
const int32_t VAR_HASH_GPS_MYQSAT_PACKED = -1519914092;   // Months, years, quality, satellites (packed)
const int32_t VAR_HASH_GPS_ACCURACY = -1489698215;
const int32_t VAR_HASH_GPS_ALTITUDE = -2100224086;
const int32_t VAR_HASH_GPS_COURSE = 1842893663;
const int32_t VAR_HASH_GPS_LATITUDE = 1524934922;
const int32_t VAR_HASH_GPS_LONGITUDE = -809214087;
const int32_t VAR_HASH_GPS_SPEED = -1486968225;

// Virtual ADC variable hashes (A0-A15) - sent from phone app
const int32_t VAR_HASH_ADC[16] = {
    595545759,   // A0
    595545760,   // A1
    595545761,   // A2
    595545762,   // A3
    595545763,   // A4
    595545764,   // A5
    595545765,   // A6
    595545766,   // A7
    595545767,   // A8
    595545768,   // A9
    -1821826352, // A10
    -1821826351, // A11
    -1821826350, // A12
    -1821826349, // A13
    -1821826348, // A14
    -1821826347  // A15
};

// Digital input variable hash (D22-D37 packed as 16-bit bitmask)
// Currently only reading IO1 (touch sensor)
const int32_t VAR_HASH_D22_D37 = 2138825443;  // TODO: Replace with actual hash from ECU

// Digital input configuration
#define DIGITAL_INPUT_PIN 1          // IO1 - touch sensor
#define DIGITAL_SAMPLE_INTERVAL_MS 20 // Sample every 20ms (50 Hz)
#define DIGITAL_DEBOUNCE_MS 50       // Debounce time

// BLE UUIDs - must match Android app
#define SERVICE_UUID           "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_BUTTON_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"  // Write buttons (no response)
#define CHAR_VAR_DATA_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26a9"  // Notify var data
#define CHAR_VAR_REQUEST_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26aa"  // Write var request
#define CHAR_GPS_DATA_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26ab"  // Write GPS data (phone -> ESP32 -> CAN)

// Forward declarations
void setupCan();
void setupBLE();
void sendButtonCanFrame(uint16_t buttonMask);
void requestCanVariable(int32_t varHash);
void sendGpsDataToCan(int32_t varHash, float value);
void sendGpsDataToCan(int32_t varHash, uint32_t packedValue);
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

static inline void writeFloat32BigEndian(float value, uint8_t* out) {
    union { float f; uint32_t u; } conv;
    conv.f = value;
    out[0] = (uint8_t)((conv.u >> 24) & 0xFF);
    out[1] = (uint8_t)((conv.u >> 16) & 0xFF);
    out[2] = (uint8_t)((conv.u >> 8) & 0xFF);
    out[3] = (uint8_t)(conv.u & 0xFF);
}

static inline void writeUint32BigEndian(uint32_t value, uint8_t* out) {
    out[0] = (uint8_t)((value >> 24) & 0xFF);
    out[1] = (uint8_t)((value >> 16) & 0xFF);
    out[2] = (uint8_t)((value >> 8) & 0xFF);
    out[3] = (uint8_t)(value & 0xFF);
}

// Globals
CANfettiManager canManager;
BLEServer* pServer = nullptr;
BLECharacteristic* pButtonChar = nullptr;
BLECharacteristic* pVarDataChar = nullptr;
BLECharacteristic* pVarRequestChar = nullptr;
BLECharacteristic* pGpsDataChar = nullptr;
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

// Hardware ADC1 state
uint32_t lastAdc1SampleTime = 0;
uint16_t adc1FilterBuffer[ADC1_FILTER_SAMPLES];
uint8_t adc1FilterIndex = 0;
bool adc1FilterFilled = false;
float lastAdc1Value = -1;  // Last sent value (-1 = never sent)

// Digital input state
uint32_t lastDigitalSampleTime = 0;
uint16_t currentDigitalBits = 0;
uint16_t lastSentDigitalBits = 0xFFFF;  // Force initial send
uint32_t lastDigitalChangeTime = 0;
bool digitalDebouncing = false;

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
        //logMessage(String("Batch request: ") + String(pendingVarCount) + " vars");
      }
    }
  }
};

// Helper to check if hash is an ADC variable (A0-A15)
int8_t getAdcChannel(int32_t varHash) {
  for (int i = 0; i < 16; i++) {
    if (VAR_HASH_ADC[i] == varHash) return i;
  }
  return -1;
}

// Variable Set Callbacks - receives GPS/ADC data from phone app, forwards to CAN
// Format: [0..3] VarHash (int32 BE), [4..7] Value (float32 BE or uint32 BE for packed)
class VarSetCharCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pCharacteristic) override {
    std::string value = pCharacteristic->getValue();
    size_t len = value.length();
    
    if (len < 8) {
      logMessage("VarSet data too short!");
      return;
    }
    
    // Process multiple variable entries (8 bytes each)
    for (size_t i = 0; i + 8 <= len; i += 8) {
      const uint8_t* data = (const uint8_t*)value.data() + i;
      int32_t varHash = readInt32BigEndian(data);
      
      // Forward directly to CAN - data is already in correct format
      // CAN ID 0x781 = Variable Set (0x780 + ECU_ID)
      uint8_t canData[8];
      memcpy(canData, data, 8);
      
      CANfettiFrame frame = CANfetti()
                              .setId(CAN_GPS_DATA_BASE + ECU_ID)
                              .setDataLength(8)
                              .setData(canData, 8)
                              .build();
      
      bool ok = canManager.sendMessage(frame);
      
      // Optional debug logging (uncomment to enable)
      /*
      int8_t adcCh = getAdcChannel(varHash);
      if (adcCh >= 0) {
        float floatVal = readFloat32BigEndian(data + 4);
        logMessage(String("ADC[") + String(adcCh) + "] = " + String(floatVal, 0) + 
                   " ok=" + String(ok ? "Y" : "N"));
      } else if (varHash == VAR_HASH_GPS_HMSD_PACKED || varHash == VAR_HASH_GPS_MYQSAT_PACKED) {
        uint32_t rawValue = ((uint32_t)data[4] << 24) | ((uint32_t)data[5] << 16) | 
                            ((uint32_t)data[6] << 8) | (uint32_t)data[7];
        logMessage(String("GPS U32: hash=") + String(varHash) + " val=0x" + String(rawValue, HEX));
      }
      */
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
    //logMessage(String("CAN TX 0x711 mask=0x") + String(buttonMask, HEX));
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
    //logMessage(String("CAN TX var request hash=") + String(varHash));
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
            //logMessage(String("BLE TX batch: ") + String(batchResponseCount) + " vars");
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

// Send a variable value to ECU via CAN (float)
void sendVariableToEcu(int32_t varHash, float value) {
  uint8_t canData[8];
  writeInt32BigEndian(varHash, canData);
  writeFloat32BigEndian(value, canData + 4);
  
  CANfettiFrame frame = CANfetti()
                          .setId(CAN_GPS_DATA_BASE + ECU_ID)
                          .setDataLength(8)
                          .setData(canData, 8)
                          .build();
  
  canManager.sendMessage(frame);
}

// Sample hardware ADC1 and send to ECU if changed
void sampleHardwareAdc1() {
  uint32_t now = millis();
  if (now - lastAdc1SampleTime < ADC1_SAMPLE_INTERVAL_MS) return;
  lastAdc1SampleTime = now;
  
  // Read raw ADC value (12-bit: 0-4095)
  uint16_t rawValue = analogRead(ADC1_PIN);
  
  // Scale to 0-1023 range (10-bit) to match app sliders
  uint16_t scaledValue = rawValue >> 2;
  
  // Add to filter buffer
  adc1FilterBuffer[adc1FilterIndex] = scaledValue;
  adc1FilterIndex = (adc1FilterIndex + 1) % ADC1_FILTER_SAMPLES;
  if (adc1FilterIndex == 0) adc1FilterFilled = true;
  
  // Calculate filtered value (average)
  uint32_t sum = 0;
  uint8_t count = adc1FilterFilled ? ADC1_FILTER_SAMPLES : adc1FilterIndex;
  if (count == 0) return;  // No samples yet
  
  for (uint8_t i = 0; i < count; i++) {
    sum += adc1FilterBuffer[i];
  }
  float filteredValue = (float)sum / count;
  
  // Check if value changed enough to send
  if (lastAdc1Value < 0 || abs(filteredValue - lastAdc1Value) >= ADC1_CHANGE_THRESHOLD) {
    lastAdc1Value = filteredValue;
    sendVariableToEcu(VAR_HASH_ADC[1], filteredValue);  // ADC1 = index 1
  }
}

// Read digital inputs and pack into 16-bit bitmask
// Currently only reading IO1 (touch sensor) - bit 0
void readDigitalInputs(uint16_t* bits) {
  *bits = 0;
  // Read IO1 touch sensor - LOW = touched = 1
  int state = digitalRead(DIGITAL_INPUT_PIN);
  if (state == LOW) {
    *bits |= (uint16_t)(1u << 0);  // Bit 0 for IO1
  }
  // Future: Add more pins here (IO22-IO37 would be bits 0-15)
}

// Sample digital inputs and send to ECU if changed (with debounce)
void sampleDigitalInputs() {
  uint32_t now = millis();
  if (now - lastDigitalSampleTime < DIGITAL_SAMPLE_INTERVAL_MS) return;
  lastDigitalSampleTime = now;
  
  uint16_t newBits;
  readDigitalInputs(&newBits);
  
  // Check if changed
  if (newBits != currentDigitalBits) {
    currentDigitalBits = newBits;
    lastDigitalChangeTime = now;
    digitalDebouncing = true;
  }
  
  // Debounce: only send after stable for DIGITAL_DEBOUNCE_MS
  if (digitalDebouncing && (now - lastDigitalChangeTime >= DIGITAL_DEBOUNCE_MS)) {
    digitalDebouncing = false;
    
    // Only send if different from last sent value
    if (currentDigitalBits != lastSentDigitalBits) {
      lastSentDigitalBits = currentDigitalBits;
      sendVariableToEcu(VAR_HASH_D22_D37, (float)currentDigitalBits);
      logMessage(String("Digital: 0x") + String(currentDigitalBits, HEX));
    }
  }
}

void setup() {

  Serial.begin(921600);
  Serial.setDebugOutput(true);

  // CAN transceiver control pin
  pinMode(9, OUTPUT);
  digitalWrite(9, LOW); // LOW = high speed mode
  
  // Hardware ADC1 input
  pinMode(ADC1_PIN, INPUT);
  analogReadResolution(12);  // 12-bit ADC (0-4095)
  
  // Digital input (IO1 touch sensor) - internal pullup
  pinMode(DIGITAL_INPUT_PIN, INPUT_PULLUP);
  
  // Initialize subsystems
  setupCan();
  setupBLE();
  
  logMessage("Setup complete - BLE Dashboard ready");
}

void loop() {
  // Process CAN RX messages - check frequently
  processCanRx();
  
  // Sample hardware ADC1 (GPIO 5) and send to ECU
  sampleHardwareAdc1();
  
  // Sample digital inputs (IO1 touch sensor) and send to ECU
  sampleDigitalInputs();
  
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
  
  // Set MTU size for larger packets
  BLEDevice::setMTU(517);
  
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  
  // Create service with enough handles for 4 characteristics (5 handles per char)
  // 4 chars * 5 handles = 20, plus service handle = 21, round up to 25
  BLEService* pService = pServer->createService(BLEUUID(SERVICE_UUID), 25);
  
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
  
  // GPS data characteristic - write only (Phone -> ESP32 -> CAN)
  pGpsDataChar = pService->createCharacteristic(
    CHAR_GPS_DATA_UUID,
    BLECharacteristic::PROPERTY_WRITE_NR
  );
  pGpsDataChar->setCallbacks(new VarSetCharCallbacks());
  
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
