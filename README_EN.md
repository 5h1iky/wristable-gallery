<div align="center">

# Wristable Gallery (腕能图库)

[中文](README.md) | **English**

[![Build](https://github.com/5h1iky/wristable-gallery/actions/workflows/build.yml/badge.svg)](https://github.com/5h1iky/wristable-gallery/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/5h1iky/wristable-gallery)](https://github.com/5h1iky/wristable-gallery/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**An image / video gallery built for Android smartwatches**, with Wi-Fi web transfer and a file browser.

Runs entirely on-device. No data is collected.

</div>

## Screenshots

| Home | Settings | About | Transfer | Files |
|---|---|---|---|---|
| ![Home](docs/screenshots/home.png) | ![Settings](docs/screenshots/settings.png) | ![About](docs/screenshots/about.png) | ![Transfer](docs/screenshots/transfer.png) | ![Files](docs/screenshots/files.png) |

## Features

- **Gallery**: MediaStore scan, 3-column grid grouped by date (Today / Yesterday / This week / This month / Earlier), image & video filters
- **Image viewer**: pinch zoom, double-tap zoom (animated), swipe paging, pull-down to exit, shared-element transition, auto-hiding info bars
- **Video player**: TextureView rendering, continuous zoom / double-tap fit⇄fill / 90° step rotation / volume & brightness gestures / lock / resume playback
- **Long-press menu**: details, save a copy, copy to clipboard, share, rename, delete (large batches go through the system trash)
- **Wi-Fi transfer**: the watch runs a tiny HTTP server — open the address in any phone/desktop browser to drag-and-drop upload and browse/download files; QR-code pairing; configurable port
- **File browser**: /sdcard tree, any extension (extension badges), open with system apps, delete / rename / details
- **Settings**: global UI scaling 0.75×~1.5× applied instantly, 4 accent themes (Ice blue / Mint green / Amber / Neon purple), about page

## Compatibility

- Android 7.0+ (minSdk 24), targetSdk 36
- Designed on 1:1 screens / dpi186, **works on both round and square** displays (round screens get automatic safe insets)
- Pure bytecode, single DEX, zero native `.so` libraries — runs on both 32-bit and 64-bit watches

## Download

Grab `Wristable-Gallery-v1.0.0.apk` from [Releases](https://github.com/5h1iky/wristable-gallery/releases) and install it with adb or your watch's installer:

```bash
adb install -r Wristable-Gallery-v1.0.0.apk
```

## Build from source

Requirements: JDK 21 (e.g. Android Studio's bundled JBR), Android SDK 36.

```bash
git clone https://github.com/5h1iky/wristable-gallery.git
cd wristable-gallery
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # requires your own keystore.properties + keystore/
```

> On Windows use `gradlew.bat`. CI (GitHub Actions) builds the debug APK on every push / PR.

## Tech stack

| Item | Choice |
|---|---|
| Language / build | Kotlin 1.9.24 + AGP 8.7.0 + Gradle 9.3.1 |
| UI | Classic Views + XML + ViewBinding (low memory, fast start, rock-solid on watches) |
| Image loading | Glide 4.16.0 |
| Video playback | TextureView + MediaPlayer (custom continuous-zoom / rotation engine) |
| Transfer | Hand-rolled zero-dependency ServerSocket/HTTP + NsdManager discovery |
| QR codes | zxing core 3.5.3 |

## Privacy

- Fully usable offline (transfer only talks inside your LAN); no analytics, no telemetry, no accounts
- All media stays on your device

## License

[MIT](LICENSE) © 2026 明日awo (5h1iky)
