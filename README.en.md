# Lightly Monitor

[中文](README.md)

Lightly Monitor is a native Android remote-care monitor app. It discovers controlled devices, establishes signaling sessions, runs WebRTC two-way voice, displays the remote camera stream, and can trigger Mimo AI image analysis with spoken playback on the controller side.

It can either start its own EasyTier VPN or reuse the EasyTier state exposed by a same-signature Lightly app that already owns the Android system VPN.

## Features

- **Device discovery**: probes known controlled endpoints first, then uses EasyTier routes and VPN subnet scanning
- **Audio/video session**: connects to controlled devices through WebRTC with two-way voice and remote camera video
- **AI analysis**: captures the remote frame, calls a Mimo gateway for Chinese analysis, and plays speech through the call route
- **EasyTier integration**: supports both Monitor-owned VPN and reused Lightly VPN modes
- **Android 5+ native support**: EasyTier native libraries target API 21 and ship for `arm64-v8a` and `armeabi-v7a`

## Installation

Download APKs from GitHub Releases.

Recommended variants:

- `app-release-arm64-v8a-signed.apk` for 64-bit Android devices
- `app-release-armeabi-v7a-signed.apk` for 32-bit Android devices

Requirements:

- Android 5.0+
- Reusing Lightly VPN requires Monitor and Lightly to be signed with the same certificate

## Quick Start

1. Install and open Monitor
2. Configure EasyTier network name, secret, peer URI, and local virtual IPv4
3. Start Monitor's own EasyTier instance, or start EasyTier VPN from Lightly first
4. Refresh the device list and select a discovered controlled device
5. In the session screen, enable voice, remote camera, or AI analysis as needed

## Documentation

### Chinese

- [Quick Start (CN)](docs/quickstart.md)
- [Development Guide (CN)](docs/development.md)
- [Architecture (CN)](docs/architecture.md)
- [Lightly Integration (CN)](docs/lightly-integration.md)

### English

- [Quick Start](docs/quickstart.en.md)
- [Development Guide](docs/development.en.md)
- [Architecture](docs/architecture.en.md)
- [Lightly Integration](docs/lightly-integration.en.md)

## Tech Stack

- Kotlin / Android SDK
- Android Gradle Plugin 8.1
- Java 17
- EasyTier JNI / FFI native libraries
- WebRTC Android SDK
- Android DataStore

## Run and Build

Debug build:

```bash
./gradlew :app:assembleDebug
```

Install the debug APK while preserving app data:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Signed release build:

```bash
scripts/package_release_apks.sh
```

Rebuild EasyTier Android 5+ native libraries:

```bash
scripts/build_easytier_android5.sh
```

## License

This project is released under the [MIT License](LICENSE).
