# Code Standards

## File Naming & Structure

### Firmware (C++)

- **Format:** kebab-case, `.cpp`/`.h` pairs
- **Example:** `audio-ring-buffer.h`, `softap.cpp`
- **Purpose:** Self-documenting names; LLM tools (Grep, Glob) can quickly identify file purpose
- **Max lines:** ≤ 200 per file (enforced via modularization)
  - `mic.cpp/h` – I2S driver wrapper only (not capture logic)
  - `capture.cpp/h` – Core 1 task + seq counter; calls ring buffer
  - `audio-ring-buffer.cpp/h` – PSRAM queue; drop-newest policy
  - `stream-service.cpp/h` – TCP loop; does NOT implement WiFi init
  - `softap.cpp/h` – WiFi SoftAP only; called before stream service
  - `led.cpp/h` – Status LED state machine; <50 lines

### Android (Kotlin)

- **Format:** PascalCase classes in kebab-case files
- **Example:** `WavFileWriter.kt`, `RecorderCommands.kt`
- **Packages (kebab-case):**
  - `iot.guitar.protocol` – Stream constants (sample rate, frame size, magic, control port)
  - `iot.guitar.audio` – I/O: WAV writer, level meter, recent recordings list
  - `iot.guitar.net` – WiFi specifier, TCP/control channel client, button listener
  - `iot.guitar.service` – **`RecorderCommands`** (only entry point; main-confined), `EspLink`, `StreamSession`, `MergeCoordinator`, `InAppMergeJob`, foreground service, notification
  - `iot.guitar.merge` – Demux/mux pipeline, AAC encode, offset, output
  - `iot.guitar.sync` – Envelope, correlation, confidence scoring, clock anchor
  - `iot.guitar.ui` – Activities (Home, RecordExternal, RecordInApp, Merge), Material 3 components
- **Max lines:** ≤ 200 per file; extract classes once > 150 lines
- **Design Tokens (resources/values/*.xml):**
  - **umic_bg:** #0B0B0F (near-black), **umic_amber:** #FF8F00 (accent)
  - **Semantic:** umic_red #FF453A (error), umic_green #30D158 (success), umic_yellow #FFD60A (warning)
  - **Surfaces:** surface (#15161C), surface_high (#1E2028), surface_low (#101116)
  - **Text:** umic_text #F5F5F7, dim #A0A4B0, disabled #5C606B

### Testing

- **JVM unit tests:** `app/src/test/java/iot/guitar/**`
- **Format:** `*Test.kt`; class name mirrors tested class
- **Example:** `StreamProtocolTest`, `EnvelopeAccumulatorTest`
- **Scope:** Pure-Kotlin classes only (no `android.*` imports)
  - ✅ `StreamProtocol` (constants, struct packing)
  - ✅ `FrameGapTracker` (gap filling logic)
  - ✅ `WavFileWriter` (RIFF header writing)
  - ✅ `WavPcmReader` (seekable PCM read)
  - ✅ `OffsetPcmSource` (offset application)
  - ✅ `AacPcmEncoder` (format conversion)
  - ✅ `EnvelopeAccumulator` (RMS envelope extraction, file `EnvelopeAccumulator.kt`)
  - ✅ `EnvelopeCrossCorrelator` (correlation algorithm)
  - ✅ `PcmStreamClient` (network reads; loopback ServerSocket mock)
  - ❌ `MainActivity` (Android-specific; manual device tests only)

## Namespaces & Package Organization

### Firmware Namespaces (C++)

```cpp
namespace proto { ... }        // Stream protocol constants, AudioFrame, packHeader()
namespace mic { ... }          // I2S driver: MicInitialize(), MicRead()
namespace vu { ... }           // VU meter: log-based level reporting
namespace softap { ... }       // WiFi: ApInitialize(), get credentials
namespace stream { ... }       // TCP service: StreamService class, sendFrame()
namespace led { ... }          // Status LED: setColor(), states
namespace capture { ... }      // Core 1 task: sequencing, ring buffer writes
```

Each namespace maps to one primary `.h`/`.cpp` pair (plus internal helpers).

### Android Packages (Kotlin)

See **File Naming & Structure** → **Android (Kotlin)** for package breakdown. Each package groups related concerns; cross-package dependencies flow in one direction (UI → service → net/audio/merge/sync → protocol).

## Error Handling

### Firmware (C++)

**Pattern:** Return bool; log at source; halt on fatal error.
```cpp
bool MicInitialize() {
  if (!driver_ok) {
    log_e("I2S init failed, code=%d", esp_err);
    led::setColor(255, 0, 0);  // Red blink
    while (true) delay(100);    // Halt
    return false;
  }
  return true;
}
```

**Severity levels:**
- `log_e()` – error; halts service
- `log_w()` – warning; recoverable; log_d() – diagnostics only

**LED indicator for errors:** Red flash (every 100 ms).

### Android (Kotlin)

**Pattern:** `try/catch` → funnel to `finish()` or state `ERROR`.
```kotlin
try {
  val wavFile = WavFileWriter(...)
  wavFile.writeFrame(pcm)
} catch (e: IOException) {
  log_e("WAV write error", e)
  state.value = RecorderState.Error(getString(R.string.error_wav_write))
  finish()
}
```

**User-facing messages:** All from `res/values/strings.xml` (Vietnamese); no hardcoded strings in code.

**Recoverable errors:** Set state to `Error` with message; user can retry (e.g., WiFi reconnect).

**Fatal errors:** Call `finish()` after showing error dialog.

## Threading & Concurrency

### Firmware

- **Core 0:** Arduino loop() runs stream::service TCP writes
- **Core 1:** `captureTask` (priority 5) reads I2S, increments seq, writes ring buffer
- **Synchronization:** Ring buffer uses atomic compare-and-swap for write/read pointers; no locks
- **Blocking:** I2S reads and TCP writes block deliberately (no busy-wait)

### Android (v0.2.0+)

- **Main thread:** UI updates via `RecorderState` StateFlow; button press → `RecorderCommands.toggleRecording()`
- **Service thread:** `AudioReceiverService` runs `PcmStreamClient` (WiFi/TCP reads) on dedicated thread; control channel listener on separate thread
- **Merge thread:** `InAppMergeJob` / `MergeActivity` offloads to coroutine (non-blocking)
- **Service state:** All mutable fields in `RecorderCommands` main-confined; no concurrent mutation
- **Locks:** `RecorderLocks` provides coordination (Mutex); `EspLink` / `StreamSession` synchronize WiFi/TCP lifetime

## Testing Conventions

### Unit Tests (JVM)

1. **Pure Kotlin classes:** Use JUnit4
2. **Loopback networking:** `PcmStreamClient` + `ServerSocket` for integration tests
3. **Test data:** Generate synthetic WAV frames (960 samples @ 48 kHz × 20 ms)
4. **Fixtures:** Reusable MockPcmReader, MockVideoTrackReader classes in test package

### Desktop Test Client

- **Purpose:** Protocol oracle; validates firmware behavior
- **File:** `firmware/tools/stream-to-wav.py` (stdlib only; works on any OS)
- **Use:** Compare WAV output with Audacity; inspect seq gaps, RMS levels

### Manual Device Tests

Checklist per phase (not automated):
- [ ] WiFi connection + network specifier fallback
- [ ] Audio level meter accuracy (within ±3 dB of reference mic)
- [ ] WAV file seekable in CapCut
- [ ] Merge without drops (drop count = 0)
- [ ] Clap sync within ±50 ms
- [ ] HDR video → error dialog shown in Vietnamese

## Commit Style

### Format

Use **conventional commits** (no AI references):
```
type(scope): description

[optional body]
```

**Types:** `feat`, `fix`, `refactor`, `test`, `docs`, `chore`  
**Scope:** `firmware` | `android` | `docs` | (omit if cross-cutting)

### Examples

```
feat(firmware): add PSRAM-backed audio ring buffer with drop-newest policy
fix(android): handle WiFi specifier rejection with manual toggle
test(android): add FrameGapTracker unit tests for seq gap filling
docs: add system architecture diagram + protocol table
refactor(android): extract OffsetPcmSource from MergeRunner for reuse
```

### Rules

- No "AI" or "Claude"; use neutral, developer-focused prose
- Capitalize first word after colon
- Link to issues if applicable: `(#123)`
- Keep commits focused on one logical change
- Run linting + tests before commit

---

**Version:** 0.1.0  
**Last Updated:** 2026-09-16
