// Momentary push button on BUTTON_PIN (to GND, internal pull-up). Debounced edge detector:
// pollPressed() returns true exactly once per press. BUTTON_PIN < 0 disables the module.
#pragma once

namespace button {

void begin();
// Call every loop() iteration; true on a debounced press edge.
bool pollPressed();

}  // namespace button
