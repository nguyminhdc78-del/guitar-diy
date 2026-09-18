// Stream protocol v1 - single source of truth for every constant shared between the
// firmware, the Android app (StreamProtocol.kt) and tools/stream-to-wav.py.
//
// Per TCP connection: an 8-byte header once, then back-to-back 1928-byte frames until
// the socket closes. All integers little-endian (native on Xtensa, so the packed
// AudioFrame struct below IS the wire format and is written with a single write()).
//
//   Header : magic "uMIC" (4) | sample_rate u32 (4)
//   Frame  : seq u32 (4) | timestamp_us u32 (4) | pcm int16[960] (1920)
//
// seq increments by 1 for every 20 ms frame captured since boot, INCLUDING frames the
// ESP32 dropped because the ring buffer was full. Receivers therefore rebuild a gap-free
// timeline by inserting `gap * 1920` zero bytes whenever seq jumps forward.
// Any change to sample rate / frame size / bit depth => bump MAGIC to "uMI2".
#pragma once

#include <stdint.h>
#include <stddef.h>

namespace proto {

constexpr uint8_t  MAGIC[4]        = {'u', 'M', 'I', 'C'};
constexpr uint32_t SAMPLE_RATE     = 48000;   // Hz, mono, 16-bit
constexpr size_t   FRAME_SAMPLES   = 960;     // 20 ms @ 48 kHz
constexpr size_t   FRAME_PCM_BYTES = FRAME_SAMPLES * sizeof(int16_t);   // 1920
constexpr size_t   FRAME_BYTES     = 8 + FRAME_PCM_BYTES;               // 1928
constexpr size_t   HEADER_BYTES    = 8;
constexpr uint16_t TCP_PORT        = 5000;
// Control channel (text lines): ESP32 -> phone `BTN <count>` on button press; phone -> ESP32
// `REC 1|0` to drive the LED. Independent of the audio stream, usable before recording.
constexpr uint16_t CONTROL_PORT    = 5001;

// Network defaults, overridable from platformio.ini build flags.
#ifndef AP_SSID
#define AP_SSID "uMIC"
#endif
#ifndef AP_PASS
#define AP_PASS "umic12345"
#endif
#ifndef AP_CHANNEL
#define AP_CHANNEL 6
#endif
constexpr const char* AP_SSID_STR  = AP_SSID;
constexpr const char* AP_PASS_STR  = AP_PASS;
constexpr int         AP_CHANNEL_NUM = AP_CHANNEL;
constexpr uint8_t     AP_MAX_CLIENTS = 1;

// One captured frame, laid out exactly as sent on the wire. Naturally aligned (4+4+1920,
// no padding) so no `packed` attribute is needed; the static_assert guards the layout.
struct AudioFrame {
    uint32_t seq;                 // frame counter since boot (drops included)
    uint32_t timestampUs;         // low 32 bits of esp_timer_get_time() at capture
    int16_t  pcm[FRAME_SAMPLES];  // mono, left channel, little-endian
};
static_assert(sizeof(AudioFrame) == FRAME_BYTES, "AudioFrame must match wire layout");

// Writes the 8-byte stream header: magic + little-endian sample rate.
inline void packHeader(uint8_t out[HEADER_BYTES]) {
    out[0] = MAGIC[0]; out[1] = MAGIC[1]; out[2] = MAGIC[2]; out[3] = MAGIC[3];
    out[4] = (uint8_t)(SAMPLE_RATE & 0xFF);
    out[5] = (uint8_t)((SAMPLE_RATE >> 8) & 0xFF);
    out[6] = (uint8_t)((SAMPLE_RATE >> 16) & 0xFF);
    out[7] = (uint8_t)((SAMPLE_RATE >> 24) & 0xFF);
}

}  // namespace proto
