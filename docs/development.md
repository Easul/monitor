# Lightly Monitor 开发指南

[English](development.en.md)

## 环境要求

- Java 17
- Android SDK，compile SDK 34
- Android Gradle Plugin 8.1
- Gradle wrapper（使用仓库内 `./gradlew`）
- Android NDK / Rust / cargo-ndk（仅在重新构建 EasyTier native 库时需要）
- ADB（用于安装和手动验证）

## 目录结构

```text
monitor/
├── app/                         # Android 应用源码与资源
│   └── src/main/java/lightly/monitor/
├── docs/                        # 中文/英文文档
├── scripts/                     # release 与 native 构建脚本
├── temp/                        # 本地签名材料等临时文件，不提交
├── .github/workflows/           # GitHub Actions
├── build.gradle
└── settings.gradle
```

## 常用命令

```bash
# 调试构建
./gradlew :app:assembleDebug

# 安装调试包并保留应用数据
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 发布构建，默认生成 64 位和 32 位签名 APK
scripts/package_release_apks.sh

# 只构建某个 ABI
ABIS="arm64-v8a" scripts/package_release_apks.sh
ABIS="armeabi-v7a" scripts/package_release_apks.sh

# 重新构建 Android 5/API 21 EasyTier native 库
scripts/build_easytier_android5.sh
```

## Release 签名

本地 release 签名材料放在已忽略的 `temp/signing/` 下：

```text
temp/signing/signing.properties
temp/signing/lightly-monitor-release.jks
```

`signing.properties` 示例：

```properties
storeFile=temp/signing/lightly-monitor-release.jks
storePassword=change-me
keyAlias=monitor
keyPassword=change-me
```

`package_release_apks.sh` 会执行 Gradle release 构建、`zipalign`、`apksigner sign`、签名验证，并在可用时用 `aapt dump badging` 检查 `native-code` 是否包含目标 ABI。

## EasyTier native 构建

`build_easytier_android5.sh` 用于重新构建 EasyTier native 库并复制到：

```text
app/src/main/jniLibs/arm64-v8a/
app/src/main/jniLibs/armeabi-v7a/
```

脚本默认行为：

- 如果 `build/EasyTier` 不存在，克隆配置的 EasyTier 源仓库到本地构建目录
- 对 EasyTier checkout 应用 Android 5 兼容补丁
- 使用 Android platform 21 构建 `arm64-v8a` 与 `armeabi-v7a`
- 验证 `libeasytier_ffi.so` 不导入 `getifaddrs` / `freeifaddrs`
- 验证 `libeasytier_android_jni.so` 带有 `DT_NEEDED: libeasytier_ffi.so`
- 默认构建 Debug APK

常用环境变量：

```bash
ABIS="arm64-v8a armeabi-v7a"
BUILD_APK=0
EASYTIER_DIR=build/EasyTier
EASYTIER_REPO=https://github.com/Easul/EasyTier.git
ANDROID_PLATFORM=21
```

## Git / 提交范围

应提交：

- Gradle wrapper 与 Gradle 配置
- `app/src/**`
- `scripts/**`
- `docs/**`
- `README.md` / `README.en.md`
- `.github/workflows/**`
- `LICENSE`

不应提交：

- `.gradle/`
- `.omo/`
- `build/`
- `app/build/`
- `app/release/`
- `temp/`
- `local.properties`
- 签名 keystore、签名密码、真实 API Key、真实私有网关域名

## CI / 发布说明

- GitHub Actions 工作流位于 `.github/workflows/release.yml`
- `v*` 标签会触发 release 创建
- `workflow_dispatch` 可手动触发构建
- CI 使用 Java 17 和仓库内 Gradle wrapper
- release job 依赖 GitHub Secrets 中的 keystore 与签名密码
- release 版本名优先使用当前提交已合并的最新 `v*` tag；无 tag 时回退到 `v0.0.0+<commit6>`
- workflow 手动触发时使用输入版本，tag 触发时使用 tag 名，并用 `origin/main` 提交数生成单调递增的 `versionCode`

## 相关文档

- [快速入门](quickstart.md)
- [架构说明](architecture.md)
- [Lightly 集成说明](lightly-integration.md)
