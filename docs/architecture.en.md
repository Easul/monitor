# Lightly Monitor Architecture

[中文](architecture.md)

## Overview

Lightly Monitor is a native Kotlin Android controller app that combines EasyTier JNI/FFI integration with WebRTC sessions.

The app can be viewed as four layers:

```text
UI layer (Activity / XML layout)
    ↓
Session layer (device discovery, signaling, WebRTC, AI analysis)
    ↓
Network layer (EasyTier JNI, Lightly Provider, VPN routing)
    ↓
Platform layer (Android VpnService, audio route, MediaPlayer, DataStore)
```

## UI Layer

Main files live under `app/src/main/java/lightly/monitor/` and `app/src/main/res/`:

- `MainActivity`: entry screen and basic navigation
- `EasyTierSettingsActivity`: EasyTier profile configuration
- `DeviceListActivity`: controlled-device discovery and selection
- `SessionActivity`: WebRTC session, remote video, and AI analysis entry point
- XML layout / drawable resources: native Android UI resources

All user-facing UI strings stay in Chinese.

## Session Layer

### Controlled-Device Discovery

`DeviceListActivity` refreshes the device list. Main dependencies:

- `ControlledDeviceHistoryStore`: historical controlled endpoint IP/port records
- Controlled endpoint probing: checks whether historical devices, manually saved devices, and the fixed overlay range expose a signaling port

The device list shows locally saved endpoints before probing online state. When an endpoint responds, the name from `probe_response` refreshes the display. If historical endpoints are unreachable, Monitor scans `10.126.126.100-150`; when the default port finds nothing, it probes fallback ports. Manual save and list deletion update local history through `ControlledDeviceHistoryStore`.

### WebRTC Session

`SessionActivity` handles:

- Signaling connection
- Local and remote media track management
- Camera, microphone, and speaker state
- Connection status display
- AI analysis trigger and result playback

WebRTC call audio enters communication mode through `AudioRouteController`.

### Mimo AI Analysis

AI analysis is available after the controller is connected to a remote video stream:

1. Capture the current frame from the remote renderer
2. Compress it as JPEG
3. Upload the image to avoid oversized multimodal request bodies
4. Call Mimo Chat Completions and stream Chinese analysis text
5. Synthesize WAV speech
6. Play it through the call audio route

`MimoApiClient` must use plain `URL.openConnection()`. Do not reintroduce `ConnectivityManager` network selection or `Network.openConnection(...)`.

## Network Layer

### Monitor-Owned VPN

Monitor can start EasyTier through JNI and pass the TUN fd to its own VPN service. Routing constraints:

- Only route EasyTier overlay addresses such as `10.126.126.x`
- Ignore non-overlay `proxy_cidrs`
- Do not turn it into a full-tunnel VPN unless explicitly required

### Reused Lightly VPN

Android allows only one user VPN at a time. If Lightly already runs EasyTier VPN, Monitor reads:

```text
content://lightly.tool.easytier/network_info
```

Provider data is used for discovery only when `is_running == true`. `raw_network_info_json` is Lightly's raw `EasyTierJNI.collectNetworkInfos(10)` output and is parsed directly by `EasyTierNetworkInfoAnalyzer`.

Do not globally change `EasyTierManager.getNetworkInfo()` to read Lightly Provider; it represents Monitor's local JNI state. Only call `getLightlyNetworkInfo()` at explicit external-VPN reuse call sites.

## Platform Layer

- `EasyTierVpnService`: Android VPN/TUN management
- `EasyTierJNI`: loads `libeasytier_ffi.so` and `libeasytier_android_jni.so`
- `AudioRouteController`: call audio route for WebRTC and AI speech playback
- `MediaPlayer`: AI WAV playback; release the old instance before starting a new one
- DataStore / SharedPreferences: local profiles, device history, and Mimo settings

## Key Data Flows

### Device Discovery

```text
Refresh device list
  → Show saved IP/port records
  → Probe historical devices
  → Scan fixed overlay range (10.126.126.100-150)
  → Probe fallback ports when the default port finds nothing
  → Save newly discovered devices
```

### AI Analysis

```text
Remote video frame
  → JPEG compression
  → Image upload
  → Mimo Chat Completions
  → TTS WAV
  → Call audio route playback
```

## Related Documentation

- [Quick Start](quickstart.en.md)
- [Development Guide](development.en.md)
- [Lightly Integration](lightly-integration.en.md)
