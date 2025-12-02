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