# Lightly Monitor Quick Start

[中文](quickstart.md)

## Installation

1. Download the APK variant that matches the device ABI from Releases
2. Allow installation from unknown sources on Android
3. Grant VPN, network, microphone, and camera permissions as needed

Recommended variants:

| File | Device |
| --- | --- |
| `app-release-arm64-v8a-signed.apk` | 64-bit Android devices |
| `app-release-armeabi-v7a-signed.apk` | 32-bit Android devices |

## First Configuration

Monitor can join the EasyTier network in two ways:

1. **Monitor-owned VPN**: configure an EasyTier profile inside Monitor and start the VPN.
2. **Reused Lightly VPN**: start EasyTier from Lightly first. When Monitor detects an active system VPN, it reads Lightly's EasyTier Provider and does not start a second VPN.

A basic EasyTier profile usually includes:

- Network name
- Network secret
- Peer URI
- Local virtual IPv4, for example `10.126.126.20`
- Controlled endpoint signaling port, for example `19090`

## Discover Controlled Devices

When the device list is refreshed, Monitor searches in this order:

1. Probe historical controlled endpoint IP/port records first
2. If a historical endpoint is reachable, show it immediately and skip slow scanning
3. If a system VPN is active, read Lightly Provider data and only accept rows where `is_running == true`
4. If Provider data is unavailable, read Monitor's local JNI network state
5. Parse EasyTier routes and probe controlled signaling services on reachable peers
6. If route discovery finds nothing and VPN is active, scan the current VPN IPv4 subnet

The local history keeps up to 32 IP/port entries.

## Session Usage

After opening a controlled-device session, Monitor supports:

- WebRTC two-way voice
- Remote camera video
- Local microphone, speaker, and camera toggles
- AI image analysis with spoken playback
- Wake or basic control actions

AI analysis flow: capture remote frame → compress as JPEG → upload image → call Mimo Chat Completions → synthesize WAV → play through the call audio route.

## Debug Commands

Start the app:

```bash
adb shell am start -n lightly.monitor/.MainActivity
```

Filter core logs:

```bash
adb logcat -v time | grep -E "lightly.monitor|EasyTier|RtcSession|AndroidRuntime|FATAL|UnsatisfiedLinkError|dlopen"
```

Filter Mimo / AI issues:

```bash
adb logcat -v time | grep -E "lightly.monitor|SessionActivity|Mimo|AI analysis|bind socket|EPERM|mimo_analysis"
```

Check device ABI:

```bash
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.product.cpu.abilist
```

## FAQ

### Controlled device is not found

- Confirm the controlled endpoint service is running
- Confirm both devices are in the same EasyTier network or another reachable network
- Check whether the historical IP is stale
- Check whether Lightly Provider reports `is_running == true`

### AI analysis fails

- Confirm Mimo Base URL and API Key are configured
- Inspect logcat for `AI analysis failed`, `bind socket`, and `EPERM`
- Mimo requests should use the system default network and must not manually bind an Android `Network`

### Voice does not follow the call route

- Re-enter the session or toggle voice again
- Check whether the previous AI `MediaPlayer` was released
- AI playback should use `USAGE_VOICE_COMMUNICATION` and `CONTENT_TYPE_SPEECH`

## More Documentation

- [Development Guide](development.en.md)
- [Architecture](architecture.en.md)
- [Lightly Integration](lightly-integration.en.md)
