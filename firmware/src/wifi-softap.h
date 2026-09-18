// SoftAP bring-up: SSID/pass/channel from stream-protocol.h (build-flag overridable),
// single station, fixed IP 192.168.4.1, WiFi power save disabled for low-jitter TX.
#pragma once

namespace softap {

// Starts the access point. Returns false if the AP could not be started.
bool begin();

// Number of stations currently associated (0 or 1).
int stationCount();

}  // namespace softap
