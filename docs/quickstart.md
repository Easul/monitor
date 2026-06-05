# Lightly Monitor 快速入门

[English](quickstart.en.md)

## 安装

1. 从 Releases 下载适合设备架构的 APK
2. 在 Android 上允许安装未知来源应用
3. 安装后按需授予 VPN、网络、麦克风、摄像头等权限

推荐包名：

| 文件 | 适用设备 |
| --- | --- |
| `app-release-arm64-v8a-signed.apk` | 64 位 Android 设备 |
| `app-release-armeabi-v7a-signed.apk` | 32 位 Android 设备 |

## 首次配置

Monitor 可以用两种方式进入 EasyTier 网络：

1. **Monitor 自有 VPN**：在 Monitor 内配置 EasyTier profile 并启动 VPN。
2. **复用 Lightly VPN**：先在 Lightly 中启动 EasyTier。Monitor 检测到系统 VPN 后读取 Lightly 暴露的 EasyTier Provider，不再启动第二个 VPN。

基础 EasyTier 配置通常包含：

- 网络名
- 网络密钥
- 节点 URI
- 本机虚拟 IPv4，例如 `10.126.126.20`
- 被控端默认信令端口，例如 `19090`

## 发现被控端

打开设备列表并点击刷新后，Monitor 会按下面顺序查找被控端：

1. 优先探测本地历史被控端 IP/端口
2. 如果历史设备可连通，直接展示并跳过慢扫描
3. 如果系统 VPN 已激活，读取 Lightly Provider，并只接受 `is_running == true` 的数据
4. 如果没有可用 Provider 数据，读取 Monitor 本地 JNI 网络状态
5. 解析 EasyTier routes，探测 reachable peers 上的被控信令服务
6. 如果 route 没有结果且 VPN 已激活，扫描当前 VPN IPv4 子网

历史记录最多保留 32 个 IP/端口。

## 会话使用

进入被控端会话后可以使用：

- WebRTC 双向语音
- 远端摄像头画面
- 本地麦克风、扬声器、摄像头开关
- AI 画面分析与语音播报
- 唤醒或基础控制操作

AI 分析流程：截取远端画面 → 压缩为 JPEG → 上传图片 → 调用 Mimo Chat Completions → 合成 WAV → 通过通话音频路由播放。

## 调试命令

启动应用：

```bash
adb shell am start -n lightly.monitor/.MainActivity
```

过滤核心日志：

```bash
adb logcat -v time | grep -E "lightly.monitor|EasyTier|RtcSession|AndroidRuntime|FATAL|UnsatisfiedLinkError|dlopen"
```

过滤 Mimo / AI 问题：

```bash
adb logcat -v time | grep -E "lightly.monitor|SessionActivity|Mimo|AI analysis|bind socket|EPERM|mimo_analysis"
```

检查设备 ABI：

```bash
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.product.cpu.abilist
```

## 常见问题

### 找不到被控端

- 确认被控端服务正在运行
- 确认双方位于同一 EasyTier 网络或同一可达网络
- 检查历史 IP 是否过期
- 检查 Lightly Provider 是否返回 `is_running == true`

### AI 分析失败

- 确认 Mimo Base URL 和 API Key 已在设置中填写
- 查看 logcat 中的 `AI analysis failed`、`bind socket`、`EPERM`
- Mimo 请求应走系统默认网络，不应手动绑定 Android `Network`

### 语音没有走通话路由

- 重新进入会话或重启语音开关
- 检查是否释放了上一段 AI `MediaPlayer`
- AI 播报应使用 `USAGE_VOICE_COMMUNICATION` 与 `CONTENT_TYPE_SPEECH`

## 更多文档

- [开发指南](development.md)
- [架构说明](architecture.md)
- [Lightly 集成说明](lightly-integration.md)
