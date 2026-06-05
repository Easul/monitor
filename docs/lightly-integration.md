# Lightly 集成说明

[English](lightly-integration.en.md)

本文记录 `lightly.monitor` 在 Lightly 已经持有 Android VPN 时，如何复用 sibling Lightly 应用的 EasyTier 状态。

## 背景

Android 同一时间只能有一个用户 VPN。若 Lightly 已经通过自己的 VPN 运行 EasyTier，Monitor 不应再启动第二个 VPN，而应复用当前 VPN，并通过 IPC 读取 Lightly 的 EasyTier 网络状态。

Monitor 仍必须保留本地 JNI 路径，因为它也可以独立运行并启动自己的 EasyTier VPN。

## Provider 协议

Lightly 暴露的 Provider URI：

```text
content://lightly.tool.easytier/network_info
```

Monitor 需要声明：

```xml
<uses-permission android:name="lightly.tool.permission.READ_EASYTIER_STATE" />

<queries>
    <provider android:authorities="lightly.tool.easytier" />
</queries>
```

期望字段：

| 字段 | 含义 |
| --- | --- |
| `instance_name` | Lightly EasyTier 实例名 |
| `raw_network_info_json` | 原始 `EasyTierJNI.collectNetworkInfos(10)` JSON，Monitor 直接交给 `EasyTierNetworkInfoAnalyzer` 解析 |
| `virtual_ipv4` | Lightly EasyTier 实例当前虚拟 IPv4 |
| `updated_at` | Provider 更新时间，epoch milliseconds |
| `is_running` | `1` 表示 Lightly EasyTier 实例正在运行 |
| `error_message` | Lightly 侧最近一次 EasyTier 错误 |

Monitor 在 `EasyTierManager.getLightlyNetworkInfo()` 中读取这些字段，并返回 `LightlyEasyTierNetworkInfo`。

## 发现流程

`DeviceListActivity` 的刷新顺序：

1. 从 `ControlledDeviceHistoryStore` 读取历史被控端
2. 优先探测历史 IP/端口；如果成功，直接展示结果并结束
3. 加载 Monitor 当前选中的 EasyTier profile，作为本地 JNI fallback 的实例上下文
4. 如果 `EasyTierManager.isExternalVpnActive()` 为 true，则查询 Lightly Provider
5. Provider 数据只有在 `is_running == true` 时才可用于发现
6. 如果 Provider 不可用或未运行，调用本地 `EasyTierManager.getNetworkInfo()`
7. 用 `EasyTierNetworkInfoAnalyzer.buildPeerSummaries(...)` 解析 raw JSON，并探测 reachable peers 上的被控信令服务
8. 如果 route 探测没有发现被控端且 VPN 已激活，则从 `EasyTierManager.activeVpnIpv4Hosts()` 获取当前 VPN IPv4 hosts 并扫描

相关中文状态文案示例：

```text
正在优先探测历史设备 IP…
已读取若轻共享的 EasyTier 网络信息，正在探测被控端…
EasyTier routes 中没有发现被控端，正在复用当前 VPN 扫描 10.x 子网…
```

## 路由约束

Monitor 自己的 `EasyTierVpnService` 只接受 `10.126.126.0/24` 及其更具体的 overlay 路由。非 overlay CIDR 会被忽略，避免普通外网流量进入 Monitor VPN。

Mimo 和普通 HTTP 请求不要手动绑定 Android `Network`。预期实现是普通 `URL.openConnection()`，由系统默认路由处理。

## Standalone 与复用模式边界

不要把 `EasyTierManager.getNetworkInfo()` 改成读取 Lightly Provider。这个方法表示 Monitor 本地 JNI 状态。

Lightly Provider 只能在明确需要复用外部 VPN 的调用点使用。目前主要是 `DeviceListActivity` 的被控端发现。

## 手动 QA

Lightly VPN 已激活时：

1. 打开 Monitor 设备列表
2. 确认状态提示已读取若轻共享的 EasyTier 网络信息，或先命中历史 IP
3. 确认能通过历史、route 或 VPN 子网扫描发现被控端
4. 连接被控端并确认 WebRTC 音视频可用

无外部 VPN 时：

1. 从 Monitor 启动 EasyTier
2. 确认 Monitor 获得自己的虚拟 IPv4
3. 打开设备发现，确认使用本地 JNI 网络信息

Lightly VPN 激活时验证 AI：

1. 连接有远端视频的被控端
2. 点击 AI 分析
3. logcat 不应出现 `bind socket`、`EPERM` 或 `AI analysis failed`
4. `mimo_analysis.wav` 应只在 cache 中短暂出现，语音应走当前通话音频路由
