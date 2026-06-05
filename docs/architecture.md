# Lightly Monitor 架构说明

[English](architecture.en.md)

## 总览

Lightly Monitor 是一个以 Kotlin 原生 Android 为主体、结合 EasyTier JNI/FFI 与 WebRTC 的控制端应用。

整体可以分成四层：

```text
界面层（Activity / XML layout）
    ↓
会话层（设备发现、信令、WebRTC、AI 分析）
    ↓
网络层（EasyTier JNI、Lightly Provider、VPN 路由）
    ↓
平台层（Android VpnService、音频路由、MediaPlayer、DataStore）
```

## 界面层

主要文件位于 `app/src/main/java/lightly/monitor/` 与 `app/src/main/res/`：

- `MainActivity`：入口页与基础导航
- `EasyTierSettingsActivity`：EasyTier profile 配置
- `DeviceListActivity`：被控端发现与选择
- `SessionActivity`：WebRTC 会话、远端画面、AI 分析入口
- XML layout / drawable：原生 Android UI 资源

所有 UI 文案保持中文。

## 会话层

### 被控端发现

`DeviceListActivity` 负责刷新设备列表，核心依赖：

- `ControlledDeviceHistoryStore`：历史被控端 IP/端口
- `EasyTierManager`：Monitor 本地 JNI 状态与 Lightly Provider 读取
- `EasyTierNetworkInfoAnalyzer`：解析 `collectNetworkInfos(10)` 原始 JSON
- 被控端信令探测：检查候选 peer 是否开放默认信令端口

历史 endpoint 命中后会跳过慢扫描，这是为了减少控制端等待时间。

### WebRTC 会话

`SessionActivity` 负责：

- 信令连接
- 本地/远端音视频 track 管理
- 摄像头、麦克风、扬声器状态
- 连接状态展示
- AI 分析触发与结果播放

WebRTC 通话音频通过 `AudioRouteController` 进入 communication mode。

### Mimo AI 分析

AI 分析只在控制端连接远端视频后可用：

1. 从远端 renderer 截取当前画面
2. 压缩为 JPEG
3. 上传图片，避免多模态请求体过大
4. 调用 Mimo Chat Completions 流式返回中文分析
5. 合成 WAV 语音
6. 使用通话音频路由播放

`MimoApiClient` 必须使用普通 `URL.openConnection()`。不要重新引入 `ConnectivityManager` 网络选择或 `Network.openConnection(...)`。

## 网络层

### Monitor 自有 VPN

Monitor 可通过 JNI 启动 EasyTier，并把 TUN fd 交给自己的 VPN service。路由约束：

- 只路由 EasyTier overlay 地址，例如 `10.126.126.x`
- 非 overlay 的 `proxy_cidrs` 会被忽略
- 不要扩展成 full-tunnel VPN，除非明确需要

### 复用 Lightly VPN

Android 同一时间只能有一个用户 VPN。若 Lightly 已经启动 EasyTier VPN，Monitor 会读取：

```text
content://lightly.tool.easytier/network_info
```

Provider 数据必须满足 `is_running == true` 才用于发现。`raw_network_info_json` 是 Lightly 侧 `EasyTierJNI.collectNetworkInfos(10)` 的原始输出，直接交给 `EasyTierNetworkInfoAnalyzer` 解析。

不要把 `EasyTierManager.getNetworkInfo()` 全局改成读取 Lightly Provider；它表示 Monitor 本地 JNI 状态。只有复用外部 VPN 的调用点才使用 `getLightlyNetworkInfo()`。

## 平台层

- `EasyTierVpnService`：Android VPN/TUN 管理
- `EasyTierJNI`：加载 `libeasytier_ffi.so` 与 `libeasytier_android_jni.so`
- `AudioRouteController`：WebRTC 与 AI 播报的通话音频路由
- `MediaPlayer`：AI WAV 播放，开始新播报前应释放旧实例
- DataStore / SharedPreferences：profile、历史设备、Mimo 设置等本地状态

## 关键数据流

### 设备发现

```text
刷新设备列表
  → 历史 IP 探测
  → Lightly Provider 或 Monitor JNI 网络信息
  → EasyTier routes 解析
  → 信令端口探测
  → 必要时扫描 VPN 子网
```

### AI 分析

```text
远端视频帧
  → JPEG 压缩
  → 图片上传
  → Mimo Chat Completions
  → TTS WAV
  → 通话音频路由播放
```

## 相关文档

- [快速入门](quickstart.md)
- [开发指南](development.md)
- [Lightly 集成说明](lightly-integration.md)
