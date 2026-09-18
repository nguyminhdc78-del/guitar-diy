# System Architecture

## Firmware Architecture

```
┌─ Core 0 (Arduino loop()) ────────────────────┐
│                                               │
│  ┌──────────────────────────────────────┐    │
│  │ stream::service (TCP server loop)    │    │
│  │ - listens on 192.168.4.1:5000        │    │
│  │ - sends AudioFrame() per connected   │    │
│  │   WiFiClient using PSRAM ring buffer │    │
│  └──────────────────────────────────────┘    │
│           │                                    │
│           ↓ reads frames                       │
│  ┌──────────────────────────────────────┐    │
│  │ AudioRingBuffer (PSRAM, 256 frames)  │    │
│  │ - circular queue of AudioFrame       │    │
│  │ - write ptr (Core 1), read ptr (C0)  │    │
│  │ - drop-newest policy when full       │    │
│  └──────────────────────────────────────┘    │
│           ↑                                    │
│           │ writes frames                      │
└───────────┼──────────────────────────────────┘
            │
┌───────────┴──────────────────────────────────┐
│ Core 1 (mic::captureTask, prio 5)            │
│                                               │
│  ┌──────────────────────────────────────┐    │
│  │ I2S DMA driver (i2s_std.h)           │    │
│  │ - INMP441: SCK(GPIO3), WS(GPIO4),   │    │
│  │   SD(GPIO5), 48 kHz, 16-bit, mono    │    │
│  └──────────────────────────────────────┘    │
│           │                                    │
│           ↓ 20 ms buffers                      │
│  ┌──────────────────────────────────────┐    │
│  │ captureTask: reads I2S, increments  │    │
│  │ seq (even on drop), writes frame to │    │
│  │ PSRAM ring buffer, blinks LED       │    │
│  └──────────────────────────────────────┘    │
│                                               │
└───────────────────────────────────────────────┘
```

**Drop Policy:** When ring buffer is full, the newest captured frame is dropped (overwriting the write pointer position). The seq counter increments regardless, so receivers rebuild silence gaps using `gap * 1920` zero bytes.

## Stream Protocol v1 (Byte-Identical to firmware/src/stream-protocol.h)

All integers **little-endian**. Per TCP connection: 8-byte header once, then 1928-byte frames until close.

| Part | Offset | Length | Value |
|------|--------|--------|-------|
| **Header** | | | |
| magic | 0 | 4 | ASCII `uMIC` = `0x75 0x4D 0x49 0x43` |
| sample_rate | 4 | 4 | u32 = 48000 |
| **Frame** (repeating) | | | |
| seq | 0 | 4 | u32; increments per captured 20 ms frame since boot, **including dropped frames** |
| timestamp_us | 4 | 4 | u32; low 32 bits of `esp_timer_get_time()` at capture; wraps ~71.6 min |
| pcm | 8 | 1920 | 960 × int16 LE, mono, left channel only |

**Receiver Gap Rule:** `gap = current_seq - expected_seq`
- If `gap > 0`: insert `gap × 1920` zero bytes (silence), increment drop counter, update expected_seq
- If `gap < 0`: ignore frame (duplicate or reordered)
- Timeline: `sample_index = frame_seq × 960`; wall-clock: `frame_seq × 20 ms`

**Fixed Constants:** `FRAME_SAMPLES=960`, `FRAME_PCM_BYTES=1920`, `FRAME_BYTES=1928`, 16-bit, 1 channel.  
**Bitrate:** 771 kbps. **Network:** SSID `uMIC`, WPA2 `umic12345`, channel 6, max 1 client, AP IP 192.168.4.1, TCP port 5000.

## Android Service State Machine (v0.2.0+)

**IDLE** → (connect) → **CONNECTING_WIFI** → **STANDBY** → (button/tap) → **CONNECTING_TCP** → **RECORDING** → (stop) → **STOPPING** → **MERGING** → **STANDBY** / **IDLE** / **ERROR**

- **STANDBY:** WiFi connected, control channel active, screen on, button listens
- **MERGING:** In-app mode only; `InAppMergeJob` anchors WAV to video, merges to `umic-<ts>.mp4`

## Android Architecture

**Entry Point:** `RecorderCommands` (only service API; main-confined for all state mutations).

**Mode 1 (External):** `AudioReceiverService` runs `PcmStreamClient` + `WavFileWriter`. `MergeActivity` launches `VideoAudioMerger` (offscreen).

**Mode 2 (In-App):** `RecordInAppActivity` runs `CameraRecorderController` + `StreamSession`. At stop, `InAppMergeJob` auto-merges; `StreamSession` keeps ESP32 WAV.

**Foreground Service:** `RecorderStatusChip` + `EdgeToEdgeInsets` (Material 3 notch support). Main thread UI via `RecorderState` StateFlow. WiFi/TCP on dedicated thread. Merge on coroutine (non-blocking).

**Control Channel:** `ControlChannelClient` (port 5001) sends `REC 1|0`, receives `BTN <n>`. Heartbeat `PING` every 5 s; 15 s idle drop both sides.

## Control Protocol v1 (Text, TCP 5001)

Heartbeat-based command channel (one client, newline-terminated). Idle → heartbeat every 5 s; no message for 15 s drops client.

| Direction | Message | Meaning |
|-----------|---------|---------|
| ESP32→phone | `BTN <n>` | Button press (n = counter since boot) |
| ESP32→phone | `MIC 0` / `MIC 1` | Mic signal lost / back (sent on connect and on change; see `mic-signal-monitor`) |
| phone→ESP32 | `REC 1` or `REC 0` | Phone is/not recording (LED solid red on REC 1) |
| phone→ESP32 | `PING` | Heartbeat request |
| ESP32→phone | `PONG` | Heartbeat response |

## Auto-Sync Algorithm (Clap Detection + Clock Anchor)

**Mode 1:** Clap-based correlation ±60 s (sharpen onset curves, PSR-only confidence metric).

**Mode 2 (In-App):** Clock-anchored sync. Anchor = `wavStartElapsed − videoStartElapsed` (both SystemClock.elapsedRealtime). If anchor ∈ ±1.5 s with PSR ≥ 4 OR Pearson r ≥ 0.3 → trusted, delete raw MP4. Else keep raw for manual merge. No wide fallback (clock trusted).

**Measured:** Start-event anchor ~480 ms late on device → Status-based video estimate + Pearson rule mitigates.

## File Map (v0.2.0)

### Firmware (`firmware/src/`)

| Module | File(s) | Purpose |
|--------|---------|---------|
| protocol | `stream-protocol.h` | uMIC v1 + control constants (CONTROL_PORT 5001); `AudioFrame` |
| mic | `i2s-mic-capture.cpp/h` | I2S DMA driver (INMP441, 48 kHz, 16-bit, mono) |
| button | `button-input.cpp/h` | GPIO7 button (active low, pull-up, 40 ms debounce); `pollPressed()` |
| control | `control-server.cpp/h` | TCP 5001 text channel; `BTN`, `MIC 0|1`, `REC 1|0`, `PING`/`PONG` heartbeat |
| micmon | `mic-signal-monitor.cpp/h` | Dead/floating INMP441 detection (1 s windows, 3 s hysteresis) → LED magenta, log, `MIC 0|1` |
| capture | `capture.cpp/h` | Core 1 task: I2S → ring buffer, seq counter |
| ring | `audio-ring-buffer.cpp/h` | PSRAM circular queue, drop-newest |
| softap | `wifi-softap.cpp/h` | WiFi SoftAP init (SSID "uMIC", WPA2, channel 6) |
| stream | `tcp-stream-server.cpp/h` | TCP 5000 audio frames |
| led | `status-led.cpp/h` | WS2812 RGB: boot/idle/stream/record/mic-fault/drop/button states |
| vu | `serial-vu-meter.cpp/h` | Serial dBFS meter (vu-test env) |

### Android (`android/app/src/main/java/iot/guitar/`)

| Package | Classes | Purpose |
|---------|---------|---------|
| `protocol` | `StreamProtocol`, `FrameGapTracker` | Constants; gap filling |
| `audio` | `WavFileWriter`, `WavStore`, `PcmLevelMeter`, `RecentRecordings` | MediaStore WAV; level state; recent list |
| `net` | `EspWifiConnector`, `PcmStreamClient`, `ControlChannelClient` | WiFi + TCP stream + control channel (GPIO7 button, REC state) |
| `service` | `RecorderCommands`, `AudioReceiverService`, `EspLink`, `StreamSession`, `MergeCoordinator`, `InAppMergeJob`, `RecorderState`, `RecordingNotification` | Service entry point; foreground service; WiFi/TCP lifetime; in-app merge jobs |
| `merge` | `VideoTrackReader`, `WavPcmReader`, `OffsetPcmSource`, `AacPcmEncoder`, `VideoAudioMerger`, `MediaStoreVideoSaver` | MP4 pipeline; offset; output |
| `sync` | `EnvelopeAccumulator`, `EnvelopeCrossCorrelator`, `AutoSyncEngine`, `VideoAudioDecoder` | Envelope; correlation; confidence; clock anchor (Mode 2) |
| `ui` | `HomeActivity`, `RecordExternalActivity`, `RecordInAppActivity`, `RecordInAppUiBinder`, `CameraRecorderController`, `LevelMeterView`, `MergeActivity`, `RecorderStatusChip`, `EdgeToEdgeInsets`, `OffsetControls`, `OffsetPreviewPlayer`, `WavFilePickerDialog` | 2-mode home; external/in-app workflows; Material 3 theme; CameraX preview |

---

**Design Principles:**
- **Separation of concerns:** Protocol (constants) separate from I/O; service separate from UI
- **Async I/O:** TCP reads on dedicated thread; UI updates via StateFlow
- **Error-first:** Clear exception logging + Vietnamese user messages from `strings.xml`
- **Testability:** Pure-Kotlin classes (envelope, correlator, offset, proto) have JVM unit tests; network mocked with loopback socket

**Version:** 0.1.0  
**Last Updated:** 2026-09-16
