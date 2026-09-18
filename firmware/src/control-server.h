// Text control channel on proto::CONTROL_PORT (one client, newline-terminated lines).
//   ESP32 -> phone : "BTN <n>"  button pressed (n = press counter since boot)
//                    "MIC 0|1"  mic signal lost / back (sent on connect and on change)
//   phone -> ESP32 : "REC 1" / "REC 0"  phone is / is not recording (drives the LED)
//                    "PING" -> "PONG"
// Lets the phone stay connected while idle so a button press can start a take remotely.
#pragma once

namespace control {

void begin();
// Accept / read lines / drop dead clients. Call from loop().
void service();
// Send a button event to the connected phone (no-op without a client).
void sendButton();
// Tell the phone whether the INMP441 delivers a usable signal (no-op without a client).
void sendMicStatus(bool ok);
bool hasClient();

}  // namespace control
