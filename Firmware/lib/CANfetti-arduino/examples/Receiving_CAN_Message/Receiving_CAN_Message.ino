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