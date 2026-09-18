# Deployment Guide

## Firmware (PlatformIO)

### PlatformIO Core Installation

```powershell
# Install via pip (Python 3.8+)
python -m pip install --upgrade platformio

# Verify installation
pio --version

# Note: Use PowerShell or cmd, NOT Git Bash.
# Git Bash breaks pioarduino's idf_tools.py script.
```

### Platform & Toolchain

The project uses the **pioarduino** fork of the Arduino-ESP32 core (v3.x). The official `espressif32` platform is stuck on core 2.0.x and will NOT compile this code.

**Why pioarduino?** It ships the modern `driver/i2s_std.h` required for INMP441 I2S capture.

**platformio.ini reference:**
```ini
platform = https://github.com/pioarduino/platform-espressif32/releases/download/55.03.311/platform-espressif32.zip
board = esp32-s3-devkitm-1
framework = arduino
board_build.arduino.memory_type = qio_opi  # N16R8 = QIO flash + Octal PSRAM
```

The platform is pinned to release `55.03.311` (Arduino core 3.3.11) for reproducible builds. To follow the newest release instead, replace the version segment with `stable`.

### Build Environments

| Environment | Description | Build Command |
|-------------|-------------|---|
| `mic` (default) | Full firmware: SoftAP + TCP streaming on 192.168.4.1:5000 | `pio run -e mic -t upload` |
| `vu-test` | Serial VU meter only (no WiFi); tests mic wiring | `pio run -e vu-test -t upload` |

### Build Flags Reference (v0.2.0)

| Flag | Default | Purpose |
|------|---------|---------|
| `MIC_PIN_SCK` | 3 | I2S clock (INMP441 SCK) |
| `MIC_PIN_WS` | 4 | I2S word-select (INMP441 WS) |
| `MIC_PIN_SD` | 5 | I2S data (INMP441 SD) |
| `MIC_SHIFT_BITS` | 14 | I2S gain (>>14 = +12 dB; tune with vu-test) |
| `LED_PIN` | 48 | WS2812 RGB LED (48 official, 38 clone, −1 disabled) |
| `BUTTON_PIN` | −1 | Momentary button GPIO (7 recommended, −1 disabled) |
| `AP_SSID` | `uMIC` | WiFi SSID (quote if spaces) |
| `AP_PASS` | `umic12345` | WiFi passphrase (always quote) |
| `AP_CHANNEL` | 6 | WiFi channel (1–13) |
| `VU_METER_ONLY` | unset | Set to 1 for vu-test env (no WiFi) |
| `ARDUINO_USB_CDC_ON_BOOT` | unset | Native USB serial; use if UART unavailable |
| `ARDUINO_USB_MODE` | unset | Native USB serial (pair with above) |

**Override example:**
```powershell
cd firmware
python -m platformio run -e mic -t upload -D LED_PIN=38 -D MIC_SHIFT_BITS=12
```

### Flash & Monitor

```powershell
# Wiring checklist before flashing: SCK=3 WS=4 SD=5, L/R->GND, VDD=3V3, GND, and a 100-470 uF
# electrolytic across the INMP441 VDD/GND pads (WiFi TX bursts otherwise buzz/thump in the audio).
# Flash vu-test (first, to verify wiring)
cd firmware
python -m platformio run -e vu-test -t upload

# Open serial monitor (adjust COM port as needed)
python -m platformio device monitor --port COM3 --baud 115200

# Flash production mic environment
python -m platformio run -e mic -t upload

# Monitor production firmware
python -m platformio device monitor
```

**Serial note:** By default, logs go to the **UART bridge port** (labeled "UART" or "COM" on most devkits). If you only have the native "USB" connector available, use the `-D ARDUINO_USB_*` flags above.

## Android App

### Prerequisites

- **JDK 17** (required for compileSdk 36)
  - Download from https://adoptium.net/
  - Or use Chocolatey: `choco install openjdk17`

- **Android SDK** (headless)
  - Download cmdline-tools from https://developer.android.com/studio/command-line-tools
  - Extract to `%ANDROID_HOME%\cmdline-tools\latest` (create directory if needed)

- **Environment variables**
  - Set `ANDROID_HOME` to your SDK path (e.g., `C:\Users\YourUser\AppData\Local\Android\sdk`)
  - Add `%ANDROID_HOME%\cmdline-tools\latest\bin` to `PATH`

### SDK Setup (One-Time)

```powershell
# Set environment variable
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\$env:USERNAME\AppData\Local\Android\sdk", "User")

# Create local.properties in android/ directory
# Replace YOURPATH with actual SDK location
echo "sdk.dir=C:\Users\YourUsername\AppData\Local\Android\sdk" | Out-File -Encoding utf8 android\local.properties

# Download SDK packages (platform-tools, platforms;android-36, build-tools;36.0.0)
$ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# Verify installation
$ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat --list
```

### Android Permissions (v0.2.0)

| Permission | Mode | Purpose |
|-----------|------|---------|
| `INTERNET` | 1–2 | WiFi TCP/control channel |
| `CHANGE_NETWORK_STATE` | 1–2 | WiFi specifier; manual fallback |
| `CAMERA` | 2 only | CameraX full-bleed preview + recorder |
| `RECORD_AUDIO` | 2 only | Phone mic sync track (optional; fallback to clock-only if denied) |
| `FOREGROUND_SERVICE(_DATA_SYNC)`, `WAKE_LOCK` | 1–2 | Recording/merge service, wake + WiFi locks |
| `POST_NOTIFICATIONS` | 1–2 | Foreground service notification |

No storage permissions: videos come from the Photo Picker, outputs go through MediaStore (`IS_PENDING` flag), app-owned rows need no READ/WRITE_EXTERNAL_STORAGE on API 29+.

### Gradle Build Configuration

**android/gradle/libs.versions.toml (v0.2.0):**
- AGP: 8.13.0, Kotlin: 2.2.20, Gradle: 8.14.3
- compileSdk: 36 (Android 15), minSdk: 29 (Android 10)
- CameraX: 1.6.2

### Build Commands

```powershell
cd android

# Build debug APK
.\gradlew.bat :app:assembleDebug
# Output: app\build\outputs\apk\debug\app-debug.apk

# Run unit tests
.\gradlew.bat :app:testDebugUnitTest

# Install to connected device
.\gradlew.bat :app:installDebug

# Or use adb directly
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Clean build
.\gradlew.bat :app:clean :app:assembleDebug
```

### Release Signing (Not Configured in v0.1.0)

Release APK signing is not set up in v0.1.0. For future releases:
1. Generate keystore: `keytool -genkey -alias umic_key -keystore umic.keystore`
2. Configure `android/local.properties` with `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. Add signing config to `app/build.gradle.kts` release block
4. Build release: `.\gradlew.bat :app:assembleRelease`

## Versioning

| Component | Version | Reference |
|-----------|---------|-----------|
| Firmware | 0.2.0 | `platformio.ini` + `stream-protocol.h` |
| App | 0.2.0 | `app/build.gradle.kts` → `versionName = "0.2.0"`, `versionCode = 2` |
| Protocol | v1 (TCP 5000 audio + TCP 5001 control) | `stream-protocol.h` → `MAGIC="uMIC"`, `CONTROL_PORT=5001` |
| CameraX | 1.6.2 | `android/gradle/libs.versions.toml` → `camerax` |

**Protocol bumping:** Change `MAGIC` to `"uMI2"` if changing sample rate, frame size, bit depth, or control channel format.

## Memory & Storage

### Firmware PSRAM

- **Board:** ESP32-S3-N16R8 with 8 MB Octal PSRAM
- **Ring buffer:** 256 frames × 1928 bytes = ~493 KB in PSRAM
- **Audio data:** Stays in PSRAM during capture and TCP send; never copies to DRAM
- **Build flag:** `board_build.arduino.memory_type = qio_opi` ensures PSRAM detection at boot

If PSRAM not found at boot, LED goes red and logs `PSRAM init failed`. Check board variant and memory_type setting.

### Android MediaStore

- **WAV storage:** `Music/uMIC/` (public, visible in file manager)
- **MP4 storage:** `Movies/uMIC/` (public, visible in Camera app)
- **Permissions:** No runtime permission needed for API 29+; MediaStore entries are app-owned
- **Write protocol:** Use `ParcelFileDescriptor` with `IS_PENDING` flag during write; set `IS_PENDING=0` on finalize

---

**Version:** 0.1.0  
**Last Updated:** 2026-09-16
