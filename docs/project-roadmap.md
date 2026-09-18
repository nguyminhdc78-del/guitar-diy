# Project Roadmap

## Version 0.2.1 – 2026-09-16

**Camera controls:** pinch zoom, presets incl. 0.5x ultra-wide lens switch, double-tap reset, tap-to-focus (in-app mode).  
**Hardware:** 470 µF decoupling at the INMP441 is now a required BOM item (WiFi-burst noise).  
**Mic wiring self-check:** firmware detects dead/floating INMP441 → magenta LED + `MIC 0|1` → app warning chip.  

## Version 0.2.0 – Released 2026-09-16 (device-validated on Samsung S21 FE + ESP32-S3-FH4R2)

**Mode 2 (In-App Recording):** CameraX full-bleed preview, auto-merge at stop with clock-anchored sync.  
**Physical Button + Control Channel:** GPIO7 debounce, TCP 5001 `BTN`/`REC`/`PING` protocol, LED state.  
**UI Redesign:** Material 3 dark theme, edge-to-edge, two mode cards, design tokens.  
**Firmware Robustness:** USB-CDC non-blocking, FH4R2 board (huge_app.csv), sharpened correlator, sound quality guidance.

See `docs/project-changelog.md` for detailed features.

## Version 0.3.0 – Backlog (Post-v0.2)

### High Priority (Future)

| Feature | Rationale |
|---------|-----------|
| **Drift correction (timestamp_us)** | Sub-frame sync; ~45 ms drift over 15 min |
| **Original audio track** | Keep phone mic as 2nd track in merged MP4 |
| **WorkManager merge** | Background merge job + notification (prevent ANR) |
| **Optional EQ** | "Sáng tiếng" mode for inside-body placement boost |
| **ESP32 reset reason logging** | Diagnose watchdog/USB issues via serial |

### Medium Priority (Future)

| Feature | Rationale |
|---------|-----------|
| **mDNS auto-discovery** | Remove hardcoded 192.168.4.1 IP |
| **Stereo recording** | L/R variant; dual I2S capture |
| **Battery/power monitoring** | Voltage/current diagnostics |

### Low Priority

- iOS support (Swift + Objective-C, same protocol)
- Jetpack Compose UI rewrite
- Sub-frame temporal alignment
- Per-client audio balancing (multi-phone)
- Cloud sync (auto-upload MP4)

## Known Issues & Testing Backlog

### Robustness
- [ ] Handle WiFi reconnect mid-recording
- [ ] Recover from partial WAV on merge error
- [ ] Validate PSRAM at firmware boot
- [ ] Capture first-sample PTS of video audio
- [ ] Auto-sync recovery UI after app data clear
- [x] AAC 2048-sample pre-roll compensation (done v0.2)
- [x] USB-CDC non-blocking (done v0.2)

### Testing
- [ ] Long-soak: 6+ hours recording + merge
- [ ] Rapid WiFi connect/disconnect cycles
- [ ] HDR video (HEVC/VP9/AV1) systematic testing
- [ ] Cross-device (Pixel, OnePlus, OPPO)

---

**Last Updated:** 2026-09-16  
**Maintained By:** Development Team  
**Related:** `docs/project-overview-pdr.md` (non-goals), `docs/project-changelog.md` (released features)
