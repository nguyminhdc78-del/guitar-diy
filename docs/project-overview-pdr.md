# Project Overview & PDR

## Project: Guitar DIY

**Guitar DIY** — a project for guitar enthusiasts who like building their own electronics: make a gadget of your own to play alongside the guitar. uMIC (ESP32-S3 WiFi microphone + Android recorder, this repository) is the first gadget of the project; the name uMIC stays for the device, firmware and app.

## Problem Statement

Synchronizing high-quality external audio with video recorded on a smartphone is tedious. Users must choose between:
- Recording audio + video separately and manually syncing in post-production (labor-intensive, requires desktop tools)
- Using the phone's built-in microphone (poor audio quality in noisy environments, limited directionality)

**Solution:** A dedicated external microphone (ESP32-S3 + INMP441) streams PCM audio over WiFi to the phone while the user films with the stock Camera. The app records both streams and merges them with manual or automatic (clap-based) synchronization, saving a finished MP4 to the phone's MediaStore.

## Goals

- **Primary:** Enable one-touch audio-video sync for location shoots without desktop post-production
- **Quality:** 48 kHz / 16-bit mono from external microphone, muxed as AAC into original video container
- **Usability:** Flow should fit within a single shoot (clap, film, merge) with Vietnamese UI for primary user
- **Robustness:** Handle WiFi disconnects gracefully; detect and report merge failure scenarios (HDR video, format mismatch)

## Non-Goals (v1)

- Drift correction via timestamp sync (v0.2 feature; ~45 ms / 15 min acceptable)
- Multiple simultaneous clients (single client per AP session)
- Preserving original phone audio (v0.2 feature; v1 muxes ESP32 track only)
- iOS support (Kotlin + Android-specific APIs; iOS planned post-v1)
- Jetpack Compose UI (uses Android Views; Compose migration future)
- Live audio playback preview (preview player uses stored WAV)

## User Journey

1. **Setup (first time):** Flash firmware via PlatformIO, install APK, wire microphone
2. **Pre-shoot:** Power ESP32 (boots to blue blink), open uMIC app, accept WiFi connection
3. **Shoot:** Clap once (sync reference), film with stock Camera
4. **Post-shoot:** Stop recording in app → select video → auto-sync (or manual adjust) → merge → share MP4

## Functional Requirements (v0.2.0)

| ID | Requirement | Source |
|----|-------------|--------|
| F1 | ESP32-S3 captures INMP441 I2S @ 48 kHz / 16-bit mono | Hardware |
| F2 | Firmware: uMIC protocol v1 (TCP 5000) + control channel v1 (TCP 5001, `BTN`/`REC`/`PING`) | Protocol |
| F3-F4 | Mode 1 (External): WAV write + live level meter + manual merge workflow | Phase 3-5 |
| F5-F8 | Mode 2 (In-App): CameraX full-bleed preview, auto-merge at stop, clock-anchored sync, output to Movies/uMIC | Phase 6 (v0.2) |
| F9 | Physical button (GPIO7 ↔ GND): debounced edge detector; start/stop recording; control channel notify | Phase 7 (v0.2) |
| F10 | Auto-sync Mode 1: clap correlation ±60 s; Mode 2: clock anchor ±1.5 s (PSR ≥ 4 OR Pearson ≥ 0.3) | Phase 5-6 |
| F11 | Offset preview + fine-tuning UI; manual ±60 s buttons (Mode 1) or nudge locks (Mode 2) | Phase 5-6 |
| F12 | HDR merge failure → Vietnamese error + "Disable HDR in Camera" guidance | Phase 4-6 |

## Non-Functional Requirements (v0.2.0)

| ID | Requirement |
|----|-------------|
| NF1 | Throughput: 771 kbps (48 kHz × 16-bit mono) over WiFi, 10 m distance |
| NF2 | Drop rate: < 5% under normal conditions |
| NF3 | Clock drift: ±45 ms over 15 min (no sub-frame correction; Mode 2 uses clock anchor ±1.5 s) |
| NF4 | Control channel heartbeat: 5 s ping; 15 s idle drop both sides |
| NF5 | Button debounce: 40 ms; LED ack flash; start/stop same edge as FAB tap |
| NF6 | Battery: Foreground service + screen on Mode 1; Mode 2 standby keeps WiFi active |
| NF7 | Shared MediaStore (Music/uMIC, Movies/uMIC); no app-private storage |
| NF8 | Protocol stability: v1 "uMIC" and "uMI2" reserved for major changes |

## Acceptance Criteria (v0.2.0)

- [x] Mode 2 in-app recording: CameraX preview, phone mic + ESP32 streams, auto-merge at stop
- [x] Clock-anchored sync: ±1.5 s with PSR/Pearson rules; keep raw MP4 if unsure
- [x] Button + control channel: GPIO7 debounce, `BTN`/`REC`/`PING` protocol, LED state
- [x] Firmware robustness: USB-CDC non-blocking, FH4R2 board support, huge_app.csv
- [x] UI redesign: Material 3 dark (#0B0B0F bg, #FF8F00 accent), edge-to-edge, two mode cards
- [x] Sound quality guidance: INMP441 placement outside guitar, 30–50 cm from 12th fret
- [x] Comprehensive docs: README workflows, system architecture, code standards, deployment guide

## Post-v1 Backlog

- **Drift correction:** Timestamp-based sync using `timestamp_us` field in protocol
- **Original audio track:** Keep phone audio as 2nd track in merged MP4
- **WorkManager merge:** Non-blocking background merge job with notification
- **Battery ADC:** Monitor power draw; report voltage, current to app
- **mDNS discovery:** Auto-discover ESP32 on network instead of hardcoded 192.168.4.1
- **Stereo recording:** L/R variant of protocol; 2-channel capture + merge

---

**Version:** 0.1.0  
**Status:** Approved  
**Last Updated:** 2026-09-16
