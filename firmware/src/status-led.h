// Non-blocking status patterns on the on-board WS2812 RGB LED (LED_PIN build flag).
//   BOOT      -> dim white
//   IDLE      -> slow blue blink (AP up, waiting for a client)
//   STREAMING -> solid green (phone connected to the audio stream)
//   recording -> solid red (phone confirmed it is recording, via the control channel)
//   mic fault -> fast magenta blink (no usable signal from the INMP441, see mic-signal-monitor.h)
//   flashDrop -> 100 ms white overlay whenever a frame is dropped
//   flashAck  -> 150 ms amber overlay on a button press
// LED_PIN < 0 compiles everything to no-ops. Brightness capped at 16/255.
#pragma once

#include <stdint.h>

namespace led {

enum class State : uint8_t { BOOT, IDLE, STREAMING };

void begin();
void setState(State s);
// Phone-confirmed recording state (overrides STREAMING colour while true).
void setRecording(bool on);
// Mic wiring fault overlay: beats every steady colour, yields only to the short flashes.
void setMicFault(bool on);
// May be called from another task (capture task); only writes a timestamp.
void flashDrop();
// Short amber blink acknowledging a button press.
void flashAck();
// Call from loop(); only touches the LED when the colour actually changes.
void tick();

}  // namespace led
