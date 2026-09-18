// Serial VU meter: accumulates RMS / peak / clip count over PCM16 frames and prints a
// dBFS bar every 5 frames (100 ms). Used by the vu-test env to validate mic wiring and
// to tune MIC_SHIFT_BITS. Same dB formulas as the Android PcmLevelMeter (floor -96 dB).
#pragma once

#include <stdint.h>
#include <stddef.h>

namespace vu {

// Feed one frame; prints a line every 5 frames.
void report(const int16_t* pcm, size_t samples);

// dBFS of a linear 0..32768 value, floored at -96.
float toDb(float linear);

}  // namespace vu
