#include <Arduino.h>
#include <CANfetti.h>

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