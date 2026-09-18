// Detects a mic that is wired wrong or came loose, without a terminal:
//   DEAD     -> >= 90 % of a 1 s window within +-2 LSB (SD idle: SD/VDD/GND open, L/R floating)
//   FLOATING -> weak (<= -30 dBFS) but zero-crossing-rich (>= 15 %) garbage: SD pin picking up
//               clock crosstalk instead of mic data
// Three bad seconds in a row raise the fault, one good second clears it. observe() runs in the
// capture task; service() runs in loop() and drives the LED, the log and the control channel.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace micmon {

void observe(const int16_t* pcm, size_t samples);
// Seconds judged bad since boot (also counts glitches shorter than the fault threshold).
uint32_t badSeconds();
bool isFault();
void service();

}  // namespace micmon
