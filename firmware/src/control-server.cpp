#include "control-server.h"

#include <Arduino.h>
#include <WiFi.h>
#include "mic-signal-monitor.h"
#include "status-led.h"
#include "stream-protocol.h"

namespace {

constexpr uint32_t IDLE_TIMEOUT_MS = 15000;   // phone pings every 5 s; silence = half-open socket

WiFiServer* g_server = nullptr;
WiFiClient  g_client;
bool        g_hasClient = false;   // NetworkClient::operator bool() == connected(), so track it explicitly
uint32_t    g_presses = 0;
uint32_t    g_lastRxMs = 0;
char        g_line[32];
size_t      g_lineLen = 0;

void dropClient(const char* why) {
    Serial.printf("[control] phone disconnected (%s)\n", why);
    g_client.stop();
    g_hasClient = false;
    led::setRecording(false);
}

void handleLine(const char* line) {
    if (strcmp(line, "REC 1") == 0) {
        led::setRecording(true);
    } else if (strcmp(line, "REC 0") == 0) {
        led::setRecording(false);
    } else if (strcmp(line, "PING") == 0) {
        g_client.print("PONG\n");
    }
}

void readIncoming() {
    while (g_client.available() > 0) {
        g_lastRxMs = millis();
        char c = (char)g_client.read();
        if (c == '\n' || c == '\r') {
            if (g_lineLen > 0) {
                g_line[g_lineLen] = '\0';
                handleLine(g_line);
                g_lineLen = 0;
            }
        } else if (g_lineLen < sizeof(g_line) - 1) {
            g_line[g_lineLen++] = c;
        } else {
            g_lineLen = 0;   // oversized garbage: resync on next newline
        }
    }
}

}  // namespace

namespace control {

void begin() {
    g_server = new WiFiServer(proto::CONTROL_PORT, 1);
    g_server->setNoDelay(true);
    g_server->begin();
    Serial.printf("[control] listening on port %u\n", proto::CONTROL_PORT);
}

void service() {
    if (g_hasClient) {
        if (!g_client.connected()) { dropClient("closed"); return; }
        if (millis() - g_lastRxMs > IDLE_TIMEOUT_MS) { dropClient("no heartbeat"); return; }
        readIncoming();
        return;
    }
    WiFiClient incoming = g_server->accept();
    if (!incoming) return;
    g_client = incoming;
    g_client.setNoDelay(true);
    g_hasClient = true;
    g_lastRxMs = millis();
    g_lineLen = 0;
    Serial.printf("[control] phone %s connected\n", g_client.remoteIP().toString().c_str());
    control::sendMicStatus(!micmon::isFault());
}

void sendButton() {
    g_presses++;
    Serial.printf("[control] button press #%lu%s\n", (unsigned long)g_presses, g_hasClient ? "" : " (no phone)");
    if (g_hasClient) {
        g_client.printf("BTN %lu\n", (unsigned long)g_presses);
    }
    led::flashAck();
}

void sendMicStatus(bool ok) {
    if (g_hasClient) g_client.printf("MIC %d\n", ok ? 1 : 0);
}

bool hasClient() { return g_hasClient; }

}  // namespace control
