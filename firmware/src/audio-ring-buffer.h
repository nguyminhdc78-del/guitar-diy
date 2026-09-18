// Single-producer / single-consumer ring of AudioFrame, allocated in PSRAM (fallback:
// internal RAM, 32 frames). Producer = I2S capture task, consumer = loop()/TCP writer.
// Lock-free: head is only written by the producer, tail only by the consumer (+ reset()).
// Drop policy = drop newest: push() fails when full and the frame is discarded; seq keeps
// counting on the producer side so the receiver can fill the hole with silence.
#pragma once

#include <atomic>
#include <stdint.h>
#include <stddef.h>
#include "stream-protocol.h"

class AudioRingBuffer {
public:
    // Allocates capacityFrames frames (PSRAM first). Returns false if even the fallback fails.
    bool begin(size_t capacityFrames);

    // Producer side. Returns false (and counts a drop) when the ring is full.
    bool push(const proto::AudioFrame& frame);

    // Consumer side. Returns false when empty.
    bool pop(proto::AudioFrame& frame);

    // Consumer side: discard everything buffered (called when a new client connects).
    void reset();

    uint32_t count() const;
    uint32_t capacity() const { return capacity_; }
    uint32_t droppedTotal() const { return dropped_.load(std::memory_order_relaxed); }
    bool inPsram() const { return inPsram_; }

    // Boot-time sanity check: push/pop 3 frames and verify contents. Logs the result.
    bool selfTest();

private:
    proto::AudioFrame*    frames_   = nullptr;
    uint32_t              capacity_ = 0;
    std::atomic<uint32_t> head_{0};   // next write index (monotonic, wraps at 2^32)
    std::atomic<uint32_t> tail_{0};   // next read index
    std::atomic<uint32_t> dropped_{0};
    bool                  inPsram_  = false;
};
