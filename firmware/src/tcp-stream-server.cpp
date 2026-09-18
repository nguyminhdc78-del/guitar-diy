#include "tcp-stream-server.h"

#include <Arduino.h>
#include <WiFi.h>
#include "i2s-mic-capture.h"
#include "status-led.h"
#include "wifi-softap.h"

namespace {

constexpr int      MAX_FRAMES_PER_CALL = 8;      // bounds loop() latency at ~8 x 1928 B
constexpr uint32_t STATS_PERIOD_MS     = 5000;
constexpr uint32_t SEND_TIMEOUT_S      = 3;      // NetworkClient::setTimeout unit = seconds

WiFiServer*        g_server = nullptr;
WiFiClient         g_client;
volatile bool      g_hasClient = false;
uint32_t           g_sent = 0;
uint32_t           g_lastStatsMs = 0;
proto::AudioFrame  g_frame;   // static: keep 1.9 KB off the loop task stack

void dropClient(const char* why) {
    Serial.printf("[stream] client gone (%s) after %lu frames\n", why, (unsigned long)g_sent);
    g_client.stop();
    g_hasClient = false;
    led::setState(led::State::IDLE);
}

void acceptClient(AudioRingBuffer& ring) {
    WiFiClient incoming = g_server->accept();
    if (!incoming) return;
    g_client = incoming;
    g_client.setNoDelay(true);
    g_client.setTimeout(SEND_TIMEOUT_S);
    ring.reset();  // start the session with live audio, not stale frames
    uint8_t header[proto::HEADER_BYTES];
    proto::packHeader(header);
    if (g_client.write(header, proto::HEADER_BYTES) != proto::HEADER_BYTES) {
        dropClient("header write failed");
        return;
    }
    g_sent = 0;
    g_hasClient = true;
    led::setState(led::State::STREAMING);
    Serial.printf("[stream] client %s connected\n", g_client.remoteIP().toString().c_str());
}

void pumpFrames(AudioRingBuffer& ring) {
    if (!g_client.connected()) {
        dropClient("connection closed");
        return;
    }
    for (int i = 0; i < MAX_FRAMES_PER_CALL; i++) {
        if (!ring.pop(g_frame)) break;
        size_t written = g_client.write((const uint8_t*)&g_frame, proto::FRAME_BYTES);
        if (written != proto::FRAME_BYTES) {
            dropClient("write failed / timeout");
            return;
        }
        g_sent++;
    }
}

void logStats(const AudioRingBuffer& ring) {
    uint32_t now = millis();
    if (now - g_lastStatsMs < STATS_PERIOD_MS) return;
    g_lastStatsMs = now;
    Serial.printf("[stream] sta=%d client=%d sent=%lu dropped=%lu ring=%lu/%lu shortReads=%lu "
                  "heap=%u psram=%u\n",
                  softap::stationCount(), g_hasClient ? 1 : 0, (unsigned long)g_sent,
                  (unsigned long)ring.droppedTotal(), (unsigned long)ring.count(),
                  (unsigned long)ring.capacity(), (unsigned long)mic::shortReadCount(),
                  (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getFreePsram());
}

}  // namespace

namespace stream {

void begin() {
    g_server = new WiFiServer(proto::TCP_PORT, proto::AP_MAX_CLIENTS);
    g_server->setNoDelay(true);
    g_server->begin();
    led::setState(led::State::IDLE);
    Serial.printf("[stream] TCP server listening on port %u\n", proto::TCP_PORT);
}

bool hasClient() { return g_hasClient; }

void service(AudioRingBuffer& ring) {
    if (g_hasClient) {
        pumpFrames(ring);
    } else {
        acceptClient(ring);
    }
    logStats(ring);
}

}  // namespace stream
