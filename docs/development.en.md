# Lightly Monitor Development Guide

[中文](development.md)

## Requirements

- Java 17
- Android SDK, compile SDK 34
- Android Gradle Plugin 8.1
- Gradle wrapper from this repository (`./gradlew`)
- Android NDK / Rust / cargo-ndk, only when rebuilding EasyTier native libraries
- ADB for installation and manual verification

## Directory Structure

```text
monitor/
├── app/                         # Android source code and resources
│   └── src/main/java/lightly/monitor/
├── docs/                        # Chinese and English documentation
├── scripts/                     # Release and native build scripts
├── temp/                        # Local signing material; ignored by git
├── .github/workflows/           # GitHub Actions
├── build.gradle
└── settings.gradle
```

## Common Commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Install the debug APK while preserving app data
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release build; produces signed 64-bit and 32-bit APKs by default
scripts/package_release_apks.sh

# Build only one ABI
ABIS="arm64-v8a" scripts/package_release_apks.sh
ABIS="armeabi-v7a" scripts/package_release_apks.sh

# Rebuild Android 5/API 21 EasyTier native libraries
scripts/build_easytier_android5.sh
```

## Release Signing

Local release signing material lives under the ignored `temp/signing/` directory:

```text
temp/signing/signing.properties
temp/signing/lightly-monitor-release.jks
```

Example `signing.properties`:

```properties
storeFile=temp/signing/lightly-monitor-release.jks
storePassword=change-me
keyAlias=monitor
keyPassword=change-me
```

`package_release_apks.sh` runs the Gradle release build, `zipalign`, `apksigner sign`, signature verification, and, when available, `aapt dump badging` to verify that `native-code` contains the target ABI.

## EasyTier Native Build

`build_easytier_android5.sh` rebuilds EasyTier native libraries and copies them into:

```text
app/src/main/jniLibs/arm64-v8a/
app/src/main/jniLibs/armeabi-v7a/
```

Default behavior:

- Clones the configured EasyTier source repository into the local build directory if `build/EasyTier` does not exist
- Applies Android 5 compatibility patches to the EasyTier checkout
- Builds `arm64-v8a` and `armeabi-v7a` with Android platform 21
- Verifies that `libeasytier_ffi.so` does not import `getifaddrs` / `freeifaddrs`
- Verifies that `libeasytier_android_jni.so` has `DT_NEEDED: libeasytier_ffi.so`
- Builds the Debug APK by default

Useful environment variables:

```bash
ABIS="arm64-v8a armeabi-v7a"
BUILD_APK=0
EASYTIER_DIR=build/EasyTier
EASYTIER_REPO=https://github.com/Easul/EasyTier.git
ANDROID_PLATFORM=21
```

## Git Scope

Commit:

- Gradle wrapper and Gradle configuration
- `app/src/**`
- `scripts/**`
- `docs/**`
- `README.md` / `README.en.md`
- `.github/workflows/**`
- `LICENSE`

Do not commit:

- `.gradle/`
- `.omo/`
- `build/`
- `app/build/`
- `app/release/`
- `temp/`
- `local.properties`
- signing keystores, signing passwords, real API keys, or real private gateway domains

## CI / Release Notes

- The GitHub Actions workflow is `.github/workflows/release.yml`
- `v*` tags trigger release creation
- `workflow_dispatch` can be used for manual builds
- CI uses Java 17 and the repository Gradle wrapper
- The release job expects keystore and signing passwords in GitHub Secrets
- Release version names prefer the latest merged `v*` tag for the current commit; without a tag they fall back to `v0.0.0+<commit6>`
- Manual workflow runs use the input version, tag-triggered runs use the tag name, and `versionCode` is generated from the `origin/main` commit count so it stays monotonically increasing

## Related Documentation

- [Quick Start](quickstart.en.md)
- [Architecture](architecture.en.md)
- [Lightly Integration](lightly-integration.en.md)
