# Lightly Integration

[中文](lightly-integration.md)

This document explains how `lightly.monitor` reuses the EasyTier state from the sibling Lightly app when Lightly already owns the Android VPN.

## Background

Android allows only one user VPN at a time. If Lightly is already running EasyTier through its VPN, Monitor should not start a second VPN. Instead, it reuses the current VPN and reads Lightly's EasyTier network state through IPC.

Monitor must still keep its local JNI path because it can also run standalone and start its own EasyTier VPN.

## Provider Protocol

Lightly exposes this Provider URI:

```text
content://lightly.tool.easytier/network_info
```

Monitor must declare:

```xml
<uses-permission android:name="lightly.tool.permission.READ_EASYTIER_STATE" />

<queries>
    <provider android:authorities="lightly.tool.easytier" />
</queries>
```

Expected columns:

| Column | Meaning |
| --- | --- |
| `instance_name` | Lightly EasyTier instance name |
| `raw_network_info_json` | Raw `EasyTierJNI.collectNetworkInfos(10)` JSON, parsed by Monitor with `EasyTierNetworkInfoAnalyzer` |
| `virtual_ipv4` | Current virtual IPv4 of the Lightly EasyTier instance |
| `updated_at` | Provider update timestamp, epoch milliseconds |
| `is_running` | `1` means the Lightly EasyTier instance is running |
| `error_message` | Latest EasyTier error from Lightly |

Monitor reads these fields in `EasyTierManager.getLightlyNetworkInfo()` and returns `LightlyEasyTierNetworkInfo`.

## Discovery Flow

`DeviceListActivity` refreshes in this order:

1. Read historical controlled endpoints from `ControlledDeviceHistoryStore`
2. Probe historical IP/port records first; if one succeeds, show it and stop
3. Load Monitor's selected EasyTier profile as the local JNI fallback context
4. If `EasyTierManager.isExternalVpnActive()` is true, query Lightly Provider
5. Provider data is usable only when `is_running == true`
6. If Provider data is unavailable or not running, call local `EasyTierManager.getNetworkInfo()`
7. Parse raw JSON with `EasyTierNetworkInfoAnalyzer.buildPeerSummaries(...)` and probe controlled signaling services on reachable peers
8. If route probing finds nothing and VPN is active, get current VPN IPv4 hosts from `EasyTierManager.activeVpnIpv4Hosts()` and scan them

Example Chinese status messages:

```text
正在优先探测历史设备 IP…
已读取若轻共享的 EasyTier 网络信息，正在探测被控端…
EasyTier routes 中没有发现被控端，正在复用当前 VPN 扫描 10.x 子网…
```

## Routing Constraints

Monitor's own `EasyTierVpnService` only accepts `10.126.126.0/24` and more specific overlay routes. Non-overlay CIDRs are ignored so ordinary internet traffic does not enter Monitor VPN.

Mimo and ordinary HTTP requests should not manually bind an Android `Network`. The expected implementation is plain `URL.openConnection()` and system-default routing.

## Standalone vs Reuse Boundaries

Do not change `EasyTierManager.getNetworkInfo()` to read Lightly Provider. That method represents Monitor's local JNI state.

Lightly Provider should only be used at call sites that explicitly reuse an external VPN. Today the primary call site is controlled-device discovery in `DeviceListActivity`.

## Manual QA

When Lightly VPN is active:

1. Open Monitor's device list
2. Confirm the status says Monitor read shared EasyTier information from Lightly, or that a historical IP was hit first
3. Confirm a controlled device can be discovered through history, routes, or VPN subnet scanning
4. Connect to the controlled device and verify WebRTC audio/video

When no external VPN is active:

1. Start EasyTier from Monitor
2. Confirm Monitor receives its own virtual IPv4
3. Open device discovery and confirm local JNI network info is used

When validating AI with Lightly VPN active:

1. Connect to a controlled device with remote video
2. Tap AI analysis
3. logcat should not show `bind socket`, `EPERM`, or `AI analysis failed`
4. `mimo_analysis.wav` should only appear briefly in cache, and speech should follow the current call audio route
