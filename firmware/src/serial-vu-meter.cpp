#include "serial-vu-meter.h"

#include <Arduino.h>
#include <math.h>

namespace {

constexpr uint32_t REPORT_FRAMES = 5;     // 5 x 20 ms = 100 ms
constexpr int      BAR_WIDTH     = 20;    // characters
constexpr float    BAR_MIN_DB    = -60.0f;
constexpr float    DB_FLOOR      = -96.0f;

double   g_sumSq   = 0.0;
int32_t  g_peak    = 0;
uint32_t g_clips   = 0;
uint32_t g_samples = 0;
uint32_t g_frames  = 0;

void printBar(float rmsDb) {
    // Map -60..0 dB onto BAR_WIDTH characters.
    int filled = (int)lroundf((rmsDb - BAR_MIN_DB) / (0.0f - BAR_MIN_DB) * BAR_WIDTH);
    if (filled < 0) filled = 0;
    if (filled > BAR_WIDTH) filled = BAR_WIDTH;
    char bar[BAR_WIDTH + 1];
    for (int i = 0; i < BAR_WIDTH; i++) bar[i] = (i < filled) ? '#' : '.';
    bar[BAR_WIDTH] = 0;
    Serial.printf("|%s|\n", bar);
}

}  // namespace

namespace vu {

float toDb(float linear) {
    if (linear <= 0.0f) return DB_FLOOR;
    float db = 20.0f * log10f(linear / 32768.0f);
    return db < DB_FLOOR ? DB_FLOOR : db;
}

void report(const int16_t* pcm, size_t samples) {
    for (size_t i = 0; i < samples; i++) {
        int32_t s = pcm[i];
        int32_t a = s < 0 ? -s : s;
        g_sumSq += (double)s * (double)s;
        if (a > g_peak) g_peak = a;
        if (a >= 32767) g_clips++;
    }
    g_samples += samples;
    if (++g_frames < REPORT_FRAMES) return;

    float rms    = g_samples ? (float)sqrt(g_sumSq / g_samples) : 0.0f;
    float rmsDb  = toDb(rms);
    float peakDb = toDb((float)g_peak);
    Serial.printf("[VU] rms=%6.1fdBFS peak=%6.1fdBFS clip=%lu ", rmsDb, peakDb, (unsigned long)g_clips);
    printBar(rmsDb);

    g_sumSq = 0.0; g_peak = 0; g_clips = 0; g_samples = 0; g_frames = 0;
}

}  // namespace vu
