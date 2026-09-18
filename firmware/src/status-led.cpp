#include "status-led.h"

#include <Arduino.h>

#ifndef LED_PIN
#define LED_PIN -1
#endif

namespace {

constexpr uint8_t  BRIGHT        = 16;    // max channel value, keeps the LED easy on the eyes
constexpr uint32_t BLINK_MS      = 1000;  // idle blink period (500 on / 500 off)
constexpr uint32_t DROP_FLASH_MS = 100;
constexpr uint32_t ACK_FLASH_MS  = 150;
constexpr uint32_t FAULT_BLINK_MS = 250;  // mic fault blink period

led::State        g_state = led::State::BOOT;
bool              g_recording = false;
bool              g_micFault = false;
volatile uint32_t g_dropUntilMs = 0;
uint32_t          g_ackUntilMs = 0;
uint32_t          g_lastColor = 0xFFFFFFFF;  // packed r<<16|g<<8|b of the last write

// Named setColor (not write) so it can never collide with the POSIX write(int, const void*, size_t)
// overload that Arduino.h drags in: write(0, 0, x) used to resolve to the libc call and crash.
void setColor(uint8_t r, uint8_t g, uint8_t b) {
    uint32_t packed = ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
    if (packed == g_lastColor) return;
    g_lastColor = packed;
#if LED_PIN >= 0
    rgbLedWrite(LED_PIN, r, g, b);
#endif
}

}  // namespace

namespace led {

void begin() {
#if LED_PIN >= 0
    setColor(BRIGHT / 4, BRIGHT / 4, BRIGHT / 4);
#endif
}

void setState(State s) { g_state = s; }

void setRecording(bool on) { g_recording = on; }

void setMicFault(bool on) { g_micFault = on; }

void flashDrop() { g_dropUntilMs = millis() + DROP_FLASH_MS; }

void flashAck() { g_ackUntilMs = millis() + ACK_FLASH_MS; }

void tick() {
#if LED_PIN >= 0
    uint32_t now = millis();
    if ((int32_t)(g_dropUntilMs - now) > 0) {
        setColor(BRIGHT, BRIGHT, BRIGHT);
        return;
    }
    if ((int32_t)(g_ackUntilMs - now) > 0) {
        setColor(BRIGHT, BRIGHT / 2, 0);
        return;
    }
    if (g_micFault) {
        uint8_t v = (now % FAULT_BLINK_MS) < FAULT_BLINK_MS / 2 ? BRIGHT : 0;
        setColor(v, 0, v);   // magenta
        return;
    }
    if (g_recording) {
        setColor(BRIGHT, 0, 0);
        return;
    }
    switch (g_state) {
        case State::BOOT:      setColor(BRIGHT / 4, BRIGHT / 4, BRIGHT / 4); break;
        case State::IDLE:      setColor(0, 0, (now % BLINK_MS) < BLINK_MS / 2 ? BRIGHT : 0); break;
        case State::STREAMING: setColor(0, BRIGHT, 0); break;
    }
#endif
}

}  // namespace led
