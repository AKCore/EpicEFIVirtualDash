//
//    FILE: CANfetti.hpp
//  AUTHOR: RyLee Harrison
// VERSION: 1.0.0
//     URL: https://github.com/RyLeeHarrison/CANfetti-arduino

#pragma once

#include <Arduino.h>

#if defined(ARDUINO_TEENSY40) || defined(ARDUINO_TEENSY41)
#include <FlexCAN_T4.h>
#define USE_FLEXCAN
#elif defined(ESP32)
#include "driver/twai.h"
#define USE_TWAI
#else
#error "Unsupported platform - requires Teensy 4.x or ESP32"
#endif

#define DEBUG_OUTPUT false

class CANfettiFrame {
public:
  uint32_t id;
  uint8_t len;
  uint8_t buf[8];
  struct {
    bool extended;
    bool remote;
  } flags;

  CANfettiFrame() : id(0), len(0), flags{ false, false } {
    memset(buf, 0, sizeof(buf));
  }
};

class CANfettiManager {
private:
  bool is_running;

#ifdef USE_FLEXCAN
  FlexCAN_T4<CAN1, RX_SIZE_256, TX_SIZE_16> can;
#endif

#ifdef USE_TWAI
  int8_t tx_pin;
  int8_t rx_pin;
  uint16_t tx_queue_size;
  uint16_t rx_queue_size;
#endif

public:
  CANfettiManager()
    : is_running(false) {
#ifdef USE_TWAI
    tx_pin = 10;
    rx_pin = 11;
    tx_queue_size = 32;
    rx_queue_size = 32;
#endif
  }

  bool init(uint32_t bitrate = 500000) {
#ifdef USE_FLEXCAN
    can.begin();
    can.setBaudRate(bitrate);
    is_running = true;
    return true;
#endif

#ifdef USE_TWAI
    twai_general_config_t g_config = {
      .mode = TWAI_MODE_NORMAL,
      .tx_io = (gpio_num_t)tx_pin,
      .rx_io = (gpio_num_t)rx_pin,
      .clkout_io = TWAI_IO_UNUSED,
      .bus_off_io = TWAI_IO_UNUSED,
      .tx_queue_len = tx_queue_size,
      .rx_queue_len = rx_queue_size,
      .alerts_enabled = TWAI_ALERT_NONE,
      .clkout_divider = 0,
      .intr_flags = ESP_INTR_FLAG_LEVEL1
    };

    twai_timing_config_t t_config;
    switch (bitrate) {
      case 1000000: t_config = TWAI_TIMING_CONFIG_1MBITS(); break;
      case 800000: t_config = TWAI_TIMING_CONFIG_800KBITS(); break;
      case 500000: t_config = TWAI_TIMING_CONFIG_500KBITS(); break;
      case 250000: t_config = TWAI_TIMING_CONFIG_250KBITS(); break;
      case 125000: t_config = TWAI_TIMING_CONFIG_125KBITS(); break;
      case 100000: t_config = TWAI_TIMING_CONFIG_100KBITS(); break;
      default: t_config = TWAI_TIMING_CONFIG_500KBITS(); break;
    }

    twai_filter_config_t f_config = TWAI_FILTER_CONFIG_ACCEPT_ALL();

    is_running = (twai_driver_install(&g_config, &t_config, &f_config) == ESP_OK && twai_start() == ESP_OK);
    return is_running;
#endif
  }

  void stop() {
    if (!is_running) return;

#ifdef USE_FLEXCAN
    can.reset();
#endif

#ifdef USE_TWAI
    twai_stop();
    twai_driver_uninstall();
#endif

    is_running = false;
  }

  bool sendMessage(const CANfettiFrame& message) {
    if (!is_running) {
      Serial.println("CAN not running");
      return false;
    }

#ifdef USE_FLEXCAN
    CAN_message_t msg;
    msg.id = message.id;
    msg.len = message.len;
    msg.flags.extended = message.flags.extended;
    msg.flags.remote = message.flags.remote;
    memcpy(msg.buf, message.buf, message.len);
    bool result = can.write(msg) == 1;
    if (DEBUG_OUTPUT) { Serial.println("CAN TX ID: " + String(message.id, HEX) +  " Len: " + String(message.len) + " Result: " + String(result ? "Success" : "Failed")); }
    return result;
#endif

#ifdef USE_TWAI
    twai_message_t msg;
    msg.identifier = message.id;
    msg.data_length_code = message.len;
    msg.flags = 0;
    if (message.flags.extended) msg.flags |= TWAI_MSG_FLAG_EXTD;
    if (message.flags.remote) msg.flags |= TWAI_MSG_FLAG_RTR;
    memcpy(msg.data, message.buf, message.len);
    bool result = (twai_transmit(&msg, pdMS_TO_TICKS(100)) == ESP_OK);
    if (DEBUG_OUTPUT) { Serial.println("CAN TX ID: " + String(message.id, HEX) +  " Len: " + String(message.len) + " Result: " + String(result ? "Success" : "Failed")); }
    return result;
#endif
  }

  bool receiveMessage(CANfettiFrame& message, uint32_t timeout = 0) {
    if (!is_running) {
      Serial.println("CAN not running");
      return false;
    }

#ifdef USE_FLEXCAN
    CAN_message_t msg;
    bool result = can.read(msg);
    if (result) {
      message.id = msg.id;
      message.len = msg.len;
      message.flags.extended = msg.flags.extended;
      message.flags.remote = msg.flags.remote;
      memcpy(message.buf, msg.buf, msg.len);
      if (DEBUG_OUTPUT) { Serial.println("CAN RX ID: " + String(message.id, HEX) + " Len: " + String(message.len)); }
    }
    return result;
#endif

#ifdef USE_TWAI
    twai_message_t msg;
    bool result = (twai_receive(&msg, pdMS_TO_TICKS(timeout)) == ESP_OK);
    if (result) {
      message.id = msg.identifier;
      message.len = msg.data_length_code;
      message.flags.extended = msg.flags & TWAI_MSG_FLAG_EXTD;
      message.flags.remote = msg.flags & TWAI_MSG_FLAG_RTR;
      memcpy(message.buf, msg.data, msg.data_length_code);
      if (DEBUG_OUTPUT) { Serial.println("CAN RX ID: " + String(message.id, HEX) + " Len: " + String(message.len)); }
    }
    return result;
#endif
  }

  bool isRunning() const {
    return is_running;
  }
};

class CANfetti {
private:
  CANfettiFrame message;

public:
  CANfetti() : message{} {}

  CANfetti& setId(uint32_t id) {
    message.id = id;
    return *this;
  }

  CANfetti& setExtendedFrame(bool isExtended) {
    message.flags.extended = isExtended;
    return *this;
  }

  CANfetti& setRemoteTransmissionRequest(bool isRtr) {
    message.flags.remote = isRtr;
    return *this;
  }

  CANfetti& setDataLength(uint8_t length) {
    message.len = (length > 8) ? 8 : length;
    return *this;
  }

  CANfetti& setData(const uint8_t* data, uint8_t length) {
    uint8_t dataLength = (length > 8) ? 8 : length;
    memcpy(message.buf, data, dataLength);
    message.len = dataLength;
    return *this;
  }

  CANfettiFrame build() const {
    return message;
  }
};
