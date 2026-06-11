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

1. Show locally saved controlled endpoint IP/port records first so common devices are selectable without waiting for a scan
2. Probe those historical devices first; when reachable, the returned controlled-device name refreshes the list
3. If historical devices are unreachable, scan the fixed `10.126.126.100-150` overlay range on the default signaling port
4. If the default port finds nothing, probe the supported fallback controlled endpoint port range
5. Newly discovered devices are saved locally and shown first on later refreshes

You can enter `10.126.126.x` or `10.126.126.x:port` and tap “保存被控设备 IP” to save a controlled endpoint manually. Use the `×` button in the list to delete a saved device. The local history keeps up to 32 IP/port entries.

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
- If the controlled endpoint is outside `10.126.126.100-150`, enter and save its IP/port manually

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
