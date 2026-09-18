// uMIC firmware entry point.
//   env vu-test (-D VU_METER_ONLY=1): mic -> serial VU meter, no WiFi.
//   env mic:  mic -> capture task -> PSRAM ring -> SoftAP "uMIC" -> TCP 192.168.4.1:5000.
// Optional -D SERIAL_VU=1 on the mic env also prints the VU meter while streaming.
#include <Arduino.h>
#include <esp_system.h>

#ifndef VU_METER_ONLY
#define VU_METER_ONLY 0
#endif
#ifndef SERIAL_VU
#define SERIAL_VU 0
#endif

#include "i2s-mic-capture.h"
#include "serial-vu-meter.h"
#include "stream-protocol.h"

#if !VU_METER_ONLY
#include "audio-ring-buffer.h"
#include "button-input.h"
#include "control-server.h"
#include "mic-signal-monitor.h"
#include "status-led.h"
#include "tcp-stream-server.h"
#include "wifi-softap.h"
#endif

namespace {

constexpr size_t RING_FRAMES = 256;   // ~5.1 s at 20 ms/frame, ~494 KB in PSRAM

// Fatal init error: blink red (100 ms on / 400 ms off) forever so the failure is visible
// without a terminal.
[[noreturn]] void halt(const char* what) {
    log_e("FATAL: %s", what);
    for (;;) {
#if !VU_METER_ONLY
        led::flashDrop();
        led::tick();
        delay(100);
        led::tick();
        delay(400);
#else
        delay(500);
#endif
    }
}

// Why the chip (re)started: distinguishes a brownout (power/battery) from a panic or a plain power-on.
const char* resetReasonName() {
    switch (esp_reset_reason()) {
        case ESP_RST_POWERON:   return "power-on";
        case ESP_RST_SW:        return "software";
        case ESP_RST_PANIC:     return "PANIC";
        case ESP_RST_INT_WDT:   return "INTERRUPT WDT";
        case ESP_RST_TASK_WDT:  return "TASK WDT";
        case ESP_RST_WDT:       return "WDT";
        case ESP_RST_BROWNOUT:  return "BROWNOUT (supply dipped)";
        case ESP_RST_USB:       return "usb";
        case ESP_RST_DEEPSLEEP: return "deep-sleep";
        default:                return "other";
    }
}

void logBoot() {
    Serial.printf("\n[boot] uMIC firmware, %s build\n", VU_METER_ONLY ? "vu-test" : "mic");
    Serial.printf("[boot] PSRAM %s, %u KB free of %u KB\n", psramFound() ? "found" : "MISSING",
                  (unsigned)(ESP.getFreePsram() / 1024), (unsigned)(ESP.getPsramSize() / 1024));
    Serial.printf("[boot] heap %u KB free\n", (unsigned)(ESP.getFreeHeap() / 1024));
    Serial.printf("[boot] reset reason: %s\n", resetReasonName());
}

#if !VU_METER_ONLY
AudioRingBuffer g_ring;
proto::AudioFrame g_captureFrame;  // owned by the capture task

// Core 1, priority 5: never blocked by the network. Frames are discarded while no client
// is connected so a new session always starts with live audio.
void captureTask(void*) {
    for (;;) {
        if (!mic::readFrame(g_captureFrame)) {
            vTaskDelay(1);  // never spin at prio 5 on a persistent driver error
            continue;
        }
        micmon::observe(g_captureFrame.pcm, proto::FRAME_SAMPLES);
        if (!stream::hasClient()) continue;
        if (!g_ring.push(g_captureFrame)) led::flashDrop();
#if SERIAL_VU
        vu::report(g_captureFrame.pcm, proto::FRAME_SAMPLES);
#endif
    }
}
#endif

}  // namespace

void setup() {
    Serial.begin(115200);
#if ARDUINO_USB_CDC_ON_BOOT
    // Native USB console: never block when no host is reading (a stalled HWCDC write can
    // starve the WiFi core and trip the interrupt watchdog). Excess log lines are dropped.
    Serial.setTxTimeoutMs(0);
#endif
    delay(200);
    logBoot();

#if !VU_METER_ONLY
    led::begin();
#endif
    if (!mic::begin()) halt("I2S mic init failed (check wiring / pins)");

#if !VU_METER_ONLY
    if (!g_ring.begin(RING_FRAMES)) halt("ring buffer alloc failed");
    if (!g_ring.selfTest()) halt("ring buffer self-test failed");
    if (!softap::begin()) halt("SoftAP start failed");
    stream::begin();
    control::begin();
    button::begin();
    BaseType_t ok = xTaskCreatePinnedToCore(captureTask, "i2s-capture", 6144, nullptr, 5,
                                            nullptr, 1);
    if (ok != pdPASS) halt("capture task create failed");
#endif
    Serial.println("[boot] ready");
}

void loop() {
#if VU_METER_ONLY
    static proto::AudioFrame frame;
    if (mic::readFrame(frame)) vu::report(frame.pcm, proto::FRAME_SAMPLES);
#else
    stream::service(g_ring);
    control::service();
    if (button::pollPressed()) control::sendButton();
    micmon::service();
    led::tick();
    delay(1);
#endif
}
