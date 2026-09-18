#include "button-input.h"

#include <Arduino.h>

#ifndef BUTTON_PIN
#define BUTTON_PIN -1
#endif

namespace {
constexpr uint32_t DEBOUNCE_MS = 40;
bool     g_stableLow  = false;   // debounced state (true = pressed)
bool     g_lastRaw    = false;
uint32_t g_lastEdgeMs = 0;
}  // namespace

namespace button {

void begin() {
#if BUTTON_PIN >= 0
    pinMode(BUTTON_PIN, INPUT_PULLUP);
    g_lastRaw = digitalRead(BUTTON_PIN) == LOW;
    g_stableLow = g_lastRaw;
    Serial.printf("[button] GPIO%d (active low, pull-up)\n", BUTTON_PIN);
#endif
}

bool pollPressed() {
#if BUTTON_PIN >= 0
    bool raw = digitalRead(BUTTON_PIN) == LOW;
    uint32_t now = millis();
    if (raw != g_lastRaw) {
        g_lastRaw = raw;
        g_lastEdgeMs = now;
    }
    if (raw != g_stableLow && now - g_lastEdgeMs >= DEBOUNCE_MS) {
        g_stableLow = raw;
        return raw;   // press edge only; release is ignored
    }
#endif
    return false;
}

}  // namespace button
