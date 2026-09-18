# Project Changelog

Format: [Keep a Changelog](https://keepachangelog.com/); versioning: [SemVer](https://semver.org/).

## [0.2.1] - 2026-09-16 — Camera zoom/focus + mic wiring self-check

**Camera (in-app):** pinch zoom, double-tap = 1x, preset button (ultra-wide/1x/2x/3x), tap-to-focus (AF+AE, 5 s hold, amber ring). Works while recording. `CameraGestures`, `CameraRecorderController.zoomState/setZoomRatio/focusAt`.

**Mic self-check (firmware `mic-signal-monitor`):** 1 s windows in the capture task; DEAD = ≥90 % samples within ±2 LSB, FLOATING = ≤−30 dBFS with zero-crossing rate ≥15 %. 3 bad s → fault, 1 good s → clear. Fault → magenta 250 ms LED blink, serial `[mic] NO SIGNAL …`, control `MIC 0` (also `MIC <state>` on phone connect). App shows chip "Mic không có tín hiệu" + wiring hint (`RecorderState.micSignal`).

**Fix:** recordings 23:10–23:11 were hiss/0/−1 garbage after re-soldering (SD idle, loose INMP441 lead); detector validated against those WAVs vs. healthy ones (ZCR 0.14–0.80 vs ≤0.04).

**Ultra-wide (0.5x):** CameraX lists the phone's ultra-wide back camera as its own `CameraInfo` (S21 FE: intrinsic 0.61). Preset button cycles 0.6x/1x/2x/3x; picking the other lens re-binds (idle only), zoom label shows effective ratio (lens factor × digital zoom). `CameraRecorderController.setUltraWide/ultraWideZoom/isUltraWide`.

**Firmware diagnostics:** boot log prints `esp_reset_reason()` (BROWNOUT/PANIC/WDT/power-on); mic monitor logs `[mic] glitch #n` for every bad second below the fault threshold.

**Hardware fix (2026-09-18):** buzz (50 Hz = 20 ms TCP frames) + thumps (~10 Hz = beacons) in recordings, mic flapping OK/NO SIGNAL and an IWDT panic in `systimer_hal_get_counter_value` (supply dip) → 470 µF electrolytic across INMP441 VDD/GND at the module. Noise gone, no more glitches. Documented as required BOM item.

**Project name:** Guitar DIY (uMIC = first gadget).

**Cleanup:** `Context.openMedia()` replaces three duplicated ACTION_VIEW chooser snippets.

## [0.2.0] - 2026-09-16 — In-app recording + physical button + firmware robustness

**UI Redesign:** Material 3 dark theme (near-black #0B0B0F, amber #FF8F00 accent, semantic red/green/yellow), edge-to-edge, no ActionBar.

**Mode 2 (In-App Recording):** CameraX full-bleed preview; phone mic + ESP32 stream merged automatically at stop. Anchor = `wavStartElapsed − videoStartElapsed` (SystemClock.elapsedRealtime, WAV −20ms frame, video estimated from CameraX Status). Anchored correlation ±1.5 s, PSR ≥ 4 OR Pearson r ≥ 0.3. Output `umic-<ts>.mp4` to `Movies/uMIC`; raw `umic-<ts>-raw.mp4` deleted only if trusted else kept.

**Physical Button:** GPIO7 ↔ GND (internal pull-up, 40 ms debounce). Control channel TCP 5001: `BTN <n>`, `REC 1|0`, `PING`/`PONG` heartbeat (5 s, 15 s idle drop). LED: red solid while recording, amber flash button ack.

**Firmware:** `Serial.setTxTimeoutMs(0)` fixes USB-CDC blocking watchdog. Platform targeting FH4R2 (4 MB flash/2 MB PSRAM, huge_app.csv).

**Mode-1 Improvements:** WAV auto-pick by time, clock-anchored auto-sync (MP4 creation ± 60 s fallback), sharpened onset curves, AAC pre-roll (2048 samples) compensation.

**Sound Quality:** INMP441 placed outside guitar, 30–50 cm from 12th fret, port facing strings → −10…−13 dB dull when inside body.

**Files:** `EspLink`, `StreamSession`, `MergeCoordinator`, `InAppMergeJob`, `RecorderCommands`, `ControlChannelClient`, `CameraRecorderController`, `RecordInAppActivity/UiBinder`, `HomeActivity`, `RecentRecordings`, `RecorderStatusChip`, `EdgeToEdgeInsets`, `control-server`, `button-input`, `status-led`.

## [0.1.0] - 2026-09-16 — Initial release

**Firmware Features:**
- I2S capture from INMP441 microphone (48 kHz, 16-bit, mono)
- SoftAP WiFi (SSID "uMIC", WPA2 "umic12345", channel 6)
- TCP server streaming frames over 192.168.4.1:5000
- uMIC protocol v1: 8-byte header + 1928-byte frames with seq + timestamp_us
- Audio ring buffer (PSRAM-backed, 256 frames, drop-newest policy)
- Frame drop handling: seq increments regardless; receivers fill gaps with silence
- WS2812 RGB LED status indicator (boot/idle/streaming/drop states)
- Serial VU meter (dBFS level reporting in vu-test environment)
- Dual-environment build: `mic` (full) and `vu-test` (wiring verification)

**Android Features:**
- WiFi connection via `WifiNetworkSpecifier` (One UI) + manual fallback toggle
- Foreground service receives PCM stream, tracks level meter (dBFS)
- WAV file writer via MediaStore (public `Music/uMIC/` directory)
- Frame gap tracking: reconstructs silence for dropped frames
- Video + audio merge pipeline:
  - `VideoTrackReader`: Extracts video track from selected MP4
  - `WavPcmReader`: Reads WAV file with seeking support
  - `OffsetPcmSource`: Applies offset (prepends silence or skips samples)
  - `AacPcmEncoder`: Encodes audio to AAC via MediaCodec
  - `VideoAudioMerger`: Interleaves video + audio frames via MediaMuxer
  - `MediaStoreVideoSaver`: Saves merged MP4 to `Movies/uMIC/` with finalization
- Auto-sync via clap detection:
  - `EnvelopeAccumulator`: Computes RMS envelope (100 Hz window)
  - `EnvelopeCrossCorrelator`: Cross-correlation of sharpened onset curves, ±60 s search range
  - Confidence scoring (peak-to-sidelobe ratio)
  - Offset preview player: audition at computed/manual offset before commit
- UI (Vietnamese strings):
  - Recording screen with live level meter
  - Merge screen with video picker + auto-sync + offset controls
  - Preview player
  - Error dialogs with actionable guidance (e.g., "Disable HDR in Camera")
- Samsung One UI battery optimization guidance

**Testing:**
- Firmware: PlatformIO compile gate; serial VU meter verification; `stream-to-wav.py` protocol oracle
- Android: JUnit4 tests for pure-Kotlin classes (StreamProtocol, FrameGapTracker, WavFileWriter, OffsetPcmSource, etc.)
- Manual device tests: WiFi connection, audio merge, clap sync, preview accuracy

**Review Fixes:**
- rx-thread catch-all for socket errors; WAV header re-patched on every 32 KB flush; openWav/finish lock; SEEK_CLOSEST for preview; MergeActivity configChanges flag; idle STOP stops service; capture-task vTaskDelay on I2S error; ring capacity power-of-two guard; pioarduino release pinned to 55.03.311

**Documentation:**
- `README.md`: Hardware wiring, toolchain install, flash steps, usage workflow, troubleshooting
- `docs/project-overview-pdr.md`: Problem statement, goals, non-goals, requirements, acceptance criteria
- `docs/system-architecture.md`: Firmware/Android architecture, protocol spec, merge pipeline, auto-sync algorithm
- `docs/code-standards.md`: File naming, modularization, namespaces, error handling, testing conventions
- `docs/deployment-guide.md`: PlatformIO setup (pioarduino platform), Android SDK setup, build commands
- `docs/project-changelog.md`: This file
- `docs/project-roadmap.md`: Post-v1 backlog (drift correction, original audio, WorkManager)

**Known Limitations:**
- No drift correction (~45 ms over 15 min accepted in v1)
- Single client only (one phone per ESP32 AP session)
- Original phone audio dropped from merged MP4 (v0.2 feature)
- No iOS support (Android + Kotlin only in v0.1.0)
- HDR10+/Dolby Vision video → merge fails with error dialog; user must disable HDR in Camera

**Breaking Changes:** None (first release). **Security:** AP passphrase public by default (`umic12345`; override via `-D AP_PASS='"..."'` + app constant); no release signing yet (debug APK only).

### Device validation (2026-09-16, Samsung S21 FE / Android 16, ESP32-S3-FH4R2)
- Firmware flashed via native USB (`ARDUINO_USB_CDC_ON_BOOT=1`); board is FH4R2 (4 MB flash / 2 MB PSRAM) -> `qio_qspi` / 4MB / `huge_app.csv`; fixed LED crash (`write(r,g,b)` resolved to POSIX `write()`, renamed `setColor`).
- 42 s recording: 2101 frames, 0 dropped, WAV 42.000 s, no silence fill; app + service stable.
- Merge verified: video track bit-identical (H.264 2336x1080, 114 frames), AAC mono 48 kHz; measured 42.67 ms encoder pre-roll -> compensated (`leadSkipSamples = 2048`), residual 0.00 ms.
- Auto-sync: real takes showed offsets of -3.6 s and -14 s (recording starts long before filming) -> search window widened to ±60 s, onset curves sharpened (square + 5-hop smoothing), slider ±60 s.

## Unreleased
See `docs/project-roadmap.md` for the post-v1 backlog.
