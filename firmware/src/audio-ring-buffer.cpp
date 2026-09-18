#include "audio-ring-buffer.h"

#include <Arduino.h>
#include <esp_heap_caps.h>
#include <string.h>

namespace {
constexpr size_t FALLBACK_FRAMES = 32;   // ~640 ms in internal RAM if PSRAM is missing
}

bool AudioRingBuffer::begin(size_t capacityFrames) {
    // head/tail are free-running uint32 indices reduced with `% capacity_`; that only stays
    // continuous across the 2^32 wrap when the capacity is a power of two.
    if (capacityFrames == 0 || (capacityFrames & (capacityFrames - 1)) != 0) {
        log_e("ring capacity %u must be a power of two", (unsigned)capacityFrames);
        return false;
    }
    size_t bytes = capacityFrames * sizeof(proto::AudioFrame);
    frames_ = (proto::AudioFrame*)heap_caps_malloc(bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (frames_) {
        capacity_ = capacityFrames;
        inPsram_  = true;
    } else {
        log_w("PSRAM alloc of %u B failed, falling back to %u frames in internal RAM",
              (unsigned)bytes, (unsigned)FALLBACK_FRAMES);
        frames_ = (proto::AudioFrame*)heap_caps_malloc(FALLBACK_FRAMES * sizeof(proto::AudioFrame),
                                                       MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
        if (!frames_) {
            log_e("ring buffer allocation failed");
            return false;
        }
        capacity_ = FALLBACK_FRAMES;
        inPsram_  = false;
    }
    head_.store(0); tail_.store(0); dropped_.store(0);
    return true;
}

bool AudioRingBuffer::push(const proto::AudioFrame& frame) {
    uint32_t head = head_.load(std::memory_order_relaxed);
    uint32_t tail = tail_.load(std::memory_order_acquire);
    if (head - tail >= capacity_) {
        dropped_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    memcpy(&frames_[head % capacity_], &frame, sizeof(proto::AudioFrame));
    head_.store(head + 1, std::memory_order_release);
    return true;
}

bool AudioRingBuffer::pop(proto::AudioFrame& frame) {
    uint32_t tail = tail_.load(std::memory_order_relaxed);
    uint32_t head = head_.load(std::memory_order_acquire);
    if (head == tail) return false;
    memcpy(&frame, &frames_[tail % capacity_], sizeof(proto::AudioFrame));
    tail_.store(tail + 1, std::memory_order_release);
    return true;
}

void AudioRingBuffer::reset() {
    tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release);
}

uint32_t AudioRingBuffer::count() const {
    return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
}

bool AudioRingBuffer::selfTest() {
    proto::AudioFrame f = {};
    for (uint32_t i = 0; i < 3; i++) {
        f.seq = 100 + i;
        f.pcm[0] = (int16_t)(i * 1000);
        f.pcm[proto::FRAME_SAMPLES - 1] = (int16_t)-(int32_t)(i * 1000);
        if (!push(f)) { log_e("ring selftest: push %lu failed", (unsigned long)i); return false; }
    }
    if (count() != 3) { log_e("ring selftest: count=%lu", (unsigned long)count()); return false; }
    for (uint32_t i = 0; i < 3; i++) {
        proto::AudioFrame out;
        if (!pop(out) || out.seq != 100 + i || out.pcm[0] != (int16_t)(i * 1000)) {
            log_e("ring selftest: pop %lu mismatch", (unsigned long)i);
            return false;
        }
    }
    if (pop(f)) { log_e("ring selftest: ring not empty after pops"); return false; }
    Serial.printf("[ring] selftest OK: %lu frames (%s), %u KB\n", (unsigned long)capacity_,
                  inPsram_ ? "PSRAM" : "internal RAM",
                  (unsigned)(capacity_ * sizeof(proto::AudioFrame) / 1024));
    return true;
}
