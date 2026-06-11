# Lightly Monitor

[English](README.en.md)

Lightly Monitor 是一款原生 Android 看护/监控端应用，用于发现被控设备、建立信令连接、进行 WebRTC 双向语音、查看远端摄像头画面，并在控制端触发 Mimo AI 画面分析与语音播报。

它可以独立启动 EasyTier VPN，也可以在同签名 Lightly 应用已经持有系统 VPN 时复用其 EasyTier 状态。

## 功能概览

- **设备发现**：优先展示并探测已保存被控端，支持手动保存/删除 IP，并扫描 `10.126.126.100-150` overlay 地址段发现在线设备
- **音视频会话**：通过 WebRTC 连接被控端，支持双向语音与远端摄像头画面
- **AI 分析**：截取远端画面，调用 Mimo 网关返回中文分析，并通过通话音频路由播报
- **EasyTier 集成**：支持 Monitor 自有 VPN 与复用 Lightly VPN 两种模式
- **Android 5+ 兼容**：EasyTier native 库按 API 21 目标构建，支持 `arm64-v8a` 与 `armeabi-v7a`

## 安装

前往 GitHub Releases 下载 APK。

推荐：

- `app-release-arm64-v8a-signed.apk`：64 位 Android 设备
- `app-release-armeabi-v7a-signed.apk`：32 位 Android 设备

要求：

- Android 5.0 及以上
- 如果复用 Lightly VPN，需要与 Lightly 使用同一签名证书

## 快速开始

1. 安装并打开 Monitor
2. 在 EasyTier 设置中填写网络名、密钥、节点 URI 与本机虚拟 IPv4
3. 启动 Monitor 自有 EasyTier，或先在 Lightly 中启动 EasyTier VPN
4. 打开设备列表并刷新，选择发现到的被控端；也可以手动输入 `10.126.126.x[:端口]` 后保存，后续会优先显示并探测
5. 进入会话页后按需开启语音、远端摄像头或 AI 分析

## 文档

### 中文

- [快速入门](docs/quickstart.md)
- [开发指南](docs/development.md)
- [架构说明](docs/architecture.md)
- [Lightly 集成说明](docs/lightly-integration.md)

### English

- [Quick Start](docs/quickstart.en.md)
- [Development Guide](docs/development.en.md)
- [Architecture](docs/architecture.en.md)
- [Lightly Integration](docs/lightly-integration.en.md)

## 技术栈

- Kotlin / Android SDK
- Android Gradle Plugin 8.1
- Java 17
- EasyTier JNI / FFI native libraries
- WebRTC Android SDK
- Android DataStore

## 运行与构建

调试构建：

```bash
./gradlew :app:assembleDebug
```

安装调试包并保留数据：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

签名发布构建：

```bash
scripts/package_release_apks.sh
```

重新构建 EasyTier Android 5+ native 库：

```bash
scripts/build_easytier_android5.sh
```

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
