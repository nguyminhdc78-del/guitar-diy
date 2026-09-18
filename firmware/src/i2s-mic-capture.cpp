#include "i2s-mic-capture.h"

#include <Arduino.h>
#include <driver/i2s_std.h>
#include <esp_timer.h>

#ifndef MIC_PIN_SCK
#define MIC_PIN_SCK 3
#endif
#ifndef MIC_PIN_WS
#define MIC_PIN_WS 4
#endif
#ifndef MIC_PIN_SD
#define MIC_PIN_SD 5
#endif
#ifndef MIC_SHIFT_BITS
#define MIC_SHIFT_BITS 14
#endif

namespace {

constexpr size_t   RAW_BYTES        = proto::FRAME_SAMPLES * sizeof(int32_t);  // 3840
constexpr uint32_t STARTUP_DISCARD  = 10;   // frames (200 ms) dropped after enable
constexpr uint32_t DMA_DESC_NUM     = 8;    // 8 x 480 samples x 4 B = 80 ms of buffering
constexpr uint32_t DMA_FRAME_NUM    = 480;  // samples per descriptor (1920 B, < 4092 max)

i2s_chan_handle_t g_rx = nullptr;
int32_t           g_raw[proto::FRAME_SAMPLES];  // static scratch, no per-frame malloc
uint32_t          g_seq = 0;
uint32_t          g_shortReads = 0;

// Reads exactly one frame of raw 32-bit words. Returns bytes actually read.
size_t readRaw() {
    size_t got = 0;
    esp_err_t err = i2s_channel_read(g_rx, g_raw, RAW_BYTES, &got, portMAX_DELAY);
    if (err != ESP_OK) {
        log_e("i2s_channel_read failed: %s", esp_err_to_name(err));
        return 0;
    }
    return got;
}

}  // namespace

namespace mic {

bool begin() {
    i2s_chan_config_t chanCfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chanCfg.dma_desc_num  = DMA_DESC_NUM;
    chanCfg.dma_frame_num = DMA_FRAME_NUM;
    esp_err_t err = i2s_new_channel(&chanCfg, nullptr, &g_rx);
    if (err != ESP_OK) {
        log_e("i2s_new_channel failed: %s", esp_err_to_name(err));
        return false;
    }

    // INMP441: 24-bit data left-justified in a 32-bit Philips slot, LEFT slot when L/R=GND.
    i2s_std_config_t stdCfg = {
        .clk_cfg  = I2S_STD_CLK_DEFAULT_CONFIG(proto::SAMPLE_RATE),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_MONO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = (gpio_num_t)MIC_PIN_SCK,
            .ws   = (gpio_num_t)MIC_PIN_WS,
            .dout = I2S_GPIO_UNUSED,
            .din  = (gpio_num_t)MIC_PIN_SD,
            .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false },
        },
    };
    stdCfg.slot_cfg.slot_mask = I2S_STD_SLOT_LEFT;

    err = i2s_channel_init_std_mode(g_rx, &stdCfg);
    if (err != ESP_OK) {
        log_e("i2s_channel_init_std_mode failed: %s", esp_err_to_name(err));
        return false;
    }
    err = i2s_channel_enable(g_rx);
    if (err != ESP_OK) {
        log_e("i2s_channel_enable failed: %s", esp_err_to_name(err));
        return false;
    }

    // Let the mic internal DC filter settle; these frames are never sent.
    for (uint32_t i = 0; i < STARTUP_DISCARD; i++) {
        if (readRaw() == 0) return false;
    }
    Serial.printf("[mic] I2S ready: %lu Hz, SCK=%d WS=%d SD=%d, shift=%d\n",
                  (unsigned long)proto::SAMPLE_RATE, MIC_PIN_SCK, MIC_PIN_WS, MIC_PIN_SD, MIC_SHIFT_BITS);
    return true;
}

bool readFrame(proto::AudioFrame& frame) {
    size_t got = readRaw();
    if (got == 0) return false;
    if (got != RAW_BYTES) {
        g_shortReads++;
        return false;
    }
    frame.seq         = g_seq++;
    frame.timestampUs = (uint32_t)esp_timer_get_time();
    for (size_t i = 0; i < proto::FRAME_SAMPLES; i++) {
        // Arithmetic shift keeps the sign; clamp because shift < 16 adds gain.
        int32_t v = g_raw[i] >> MIC_SHIFT_BITS;
        if (v > 32767) v = 32767;
        else if (v < -32768) v = -32768;
        frame.pcm[i] = (int16_t)v;
    }
    return true;
}

uint32_t shortReadCount() { return g_shortReads; }

}  // namespace mic
