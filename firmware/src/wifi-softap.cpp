#include "wifi-softap.h"

#include <Arduino.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include "stream-protocol.h"

namespace softap {

bool begin() {
    const IPAddress ip(192, 168, 4, 1);
    const IPAddress mask(255, 255, 255, 0);

    WiFi.mode(WIFI_AP);
    // hidden=0 (visible), max_connection=1 => a second phone cannot steal the stream.
    if (!WiFi.softAP(proto::AP_SSID_STR, proto::AP_PASS_STR, proto::AP_CHANNEL_NUM, 0,
                     proto::AP_MAX_CLIENTS)) {
        log_e("WiFi.softAP failed");
        return false;
    }
    delay(100);  // let the AP netif come up before reconfiguring its IP
    if (!WiFi.softAPConfig(ip, ip, mask)) {
        log_w("softAPConfig failed, keeping default IP %s", WiFi.softAPIP().toString().c_str());
    }
    // Power save adds 100+ ms bursts of latency; the stream needs steady 20 ms pacing.
    WiFi.setSleep(false);
    esp_wifi_set_ps(WIFI_PS_NONE);

    Serial.printf("[wifi] AP %s ch%d up, IP %s, TCP port %u\n", proto::AP_SSID_STR,
                  proto::AP_CHANNEL_NUM, WiFi.softAPIP().toString().c_str(), proto::TCP_PORT);
    return true;
}

int stationCount() { return (int)WiFi.softAPgetStationNum(); }

}  // namespace softap
