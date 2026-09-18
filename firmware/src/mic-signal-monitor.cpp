#include "mic-signal-monitor.h"

#include <Arduino.h>
#include <math.h>
#include "control-server.h"
#include "status-led.h"
#include "stream-protocol.h"

namespace {

constexpr uint32_t WINDOW_FRAMES   = proto::SAMPLE_RATE / proto::FRAME_SAMPLES;  // 1 s
constexpr int16_t  IDLE_LSB        = 2;
constexpr float    DEAD_IDLE_FRAC  = 0.90f;
constexpr float    FLOAT_MAX_DB    = -30.0f;
constexpr float    FLOAT_MIN_ZCR   = 0.15f;   // healthy audio (room, guitar, voice) stays < 0.05
constexpr uint8_t  FAULT_AFTER     = 3;       // consecutive bad seconds
constexpr uint32_t REMIND_MS       = 10000;

uint64_t g_sumSq = 0;
uint32_t g_idle = 0, g_zc = 0, g_frames = 0;
bool     g_lastNeg = false;
uint8_t  g_badStreak = 0;
volatile bool g_fault = false;
volatile uint32_t g_badSeconds = 0;   // every bad window, faulted or not
// Last window's figures, for the log line.
volatile float g_rmsDb = -96, g_idleFrac = 0, g_zcr = 0;

void finishWindow() {
    const uint32_t n = g_frames * proto::FRAME_SAMPLES;
    float rms = sqrtf((float)g_sumSq / n) / 32768.0f;
    g_rmsDb = 20.0f * log10f(rms > 1e-6f ? rms : 1e-6f);
    g_idleFrac = (float)g_idle / n;
    g_zcr = (float)g_zc / n;
    bool dead = g_idleFrac >= DEAD_IDLE_FRAC;
    bool floating = g_rmsDb <= FLOAT_MAX_DB && g_zcr >= FLOAT_MIN_ZCR;
    if (dead || floating) {
        g_badSeconds++;
        if (g_badStreak < FAULT_AFTER) g_badStreak++;
        if (g_badStreak >= FAULT_AFTER) g_fault = true;
    } else {
        g_badStreak = 0;
        g_fault = false;
    }
    g_sumSq = 0; g_idle = 0; g_zc = 0; g_frames = 0;
}

}  // namespace

namespace micmon {

void observe(const int16_t* pcm, size_t samples) {
    for (size_t i = 0; i < samples; i++) {
        int32_t v = pcm[i];
        g_sumSq += (uint64_t)(v * v);
        if (v <= IDLE_LSB && v >= -IDLE_LSB) g_idle++;
        bool neg = v < 0;
        if (neg != g_lastNeg) g_zc++;
        g_lastNeg = neg;
    }
    if (++g_frames >= WINDOW_FRAMES) finishWindow();
}

bool isFault() { return g_fault; }

uint32_t badSeconds() { return g_badSeconds; }

void service() {
    static bool     lastFault = false;
    static uint32_t lastLogMs = 0;
    static uint32_t seenBad = 0;
    bool fault = g_fault;
    uint32_t now = millis();
    if (fault != lastFault) {
        lastFault = fault;
        lastLogMs = now;
        led::setMicFault(fault);
        control::sendMicStatus(!fault);
        if (fault) {
            Serial.printf("[mic] NO SIGNAL: rms=%.0f dBFS idle=%.0f%% zcr=%.2f -> check INMP441 SD/L-R/VDD/GND wiring\n",
                          g_rmsDb, g_idleFrac * 100, g_zcr);
        } else {
            Serial.println("[mic] signal OK");
        }
    } else if (!fault && g_badSeconds != seenBad) {
        // Short glitch (< FAULT_AFTER s): loose contact or supply dip, worth a trace even without a fault.
        Serial.printf("[mic] glitch #%lu: rms=%.0f dBFS idle=%.0f%% zcr=%.2f\n", (unsigned long)g_badSeconds, g_rmsDb, g_idleFrac * 100, g_zcr);
    } else if (fault && now - lastLogMs >= REMIND_MS) {
        lastLogMs = now;
        Serial.printf("[mic] still no signal (rms=%.0f dBFS idle=%.0f%% zcr=%.2f)\n", g_rmsDb, g_idleFrac * 100, g_zcr);
    }
    seenBad = g_badSeconds;
}

}  // namespace micmon
