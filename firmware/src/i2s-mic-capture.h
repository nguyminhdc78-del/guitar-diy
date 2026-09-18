// INMP441 I2S capture (Arduino-ESP32 3.x `driver/i2s_std.h`).
// begin() configures I2S0 as master RX @ 48 kHz, 32-bit slot, mono LEFT slot and
// discards the first frames while the mic DC offset settles. readFrame() blocks until
// one 20 ms frame (960 samples) is available, converts 32-bit words to int16 and stamps
// seq + timestamp. No heap allocation per frame; safe to call from a dedicated task.
//
// Troubleshooting (use the vu-test env):
//   all zeros / -96 dBFS      -> SD pin not connected, or L/R pin floating (must be GND)
//   full-scale noise / clicks -> SCK and WS swapped, or bad ground
//   very quiet speech         -> lower MIC_SHIFT_BITS (14 = +12 dB, 12 = +24 dB)
//   constant clipping         -> raise MIC_SHIFT_BITS toward 16
#pragma once

#include <stdint.h>
#include "stream-protocol.h"

namespace mic {

// Initialise and enable the I2S RX channel. Returns false on driver error.
bool begin();

// Blocks until a full frame is captured. Returns false on driver error or short read.
bool readFrame(proto::AudioFrame& frame);

// Diagnostics: number of reads that returned fewer bytes than requested.
uint32_t shortReadCount();

}  // namespace mic
