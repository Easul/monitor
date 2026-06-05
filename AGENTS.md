## Scope and identity

- This project is the native Android Monitor app with package `lightly.monitor`.
- Do not modify the sibling `/home/easul/workspace/flutter/lightly` project unless the user explicitly asks.
- Keep UI/user-facing messages in Chinese.
- Use semantic English commit messages such as `fix: ...` or `feat: ...` when the user asks to commit.

## Lightly / EasyTier integration pitfalls

- Monitor can run standalone or reuse the sibling Lightly app's VPN. Preserve both paths.
- `EasyTierManager.getNetworkInfo()` is the local JNI path. Do not globally repoint it to Lightly's Provider.
- Use `EasyTierManager.getLightlyNetworkInfo()` only when a system VPN is active and Provider data reports `is_running == true`.
- Lightly Provider URI: `content://lightly.tool.easytier/network_info`.
- Required permission: `lightly.tool.permission.READ_EASYTIER_STATE`.
- Required Provider query authority: `lightly.tool.easytier`.
- Raw Provider column `raw_network_info_json` is already `EasyTierJNI.collectNetworkInfos(10)` output and should be parsed with `EasyTierNetworkInfoAnalyzer`.
- Historical controlled endpoints are intentionally probed before route/subnet discovery. Do not remove this shortcut unless replacing it with a faster equivalent.

## VPN routing pitfalls

- Only EasyTier overlay addresses like `10.126.126.x` should be routed through Monitor's own VPN service.
- `EasyTierVpnService` deliberately ignores non-overlay `proxy_cidrs`. Do not expand it into a full-tunnel VPN unless the user explicitly asks.
- General internet traffic, image uploads, and Mimo API requests should remain direct/default-network traffic.

## Mimo networking pitfalls

- `MimoApiClient` must use plain `URL.openConnection()`.
- Do not reintroduce `ConnectivityManager` network selection or `Network.openConnection(...)` for Mimo requests.
- On some Android/MIUI devices, manual network binding while a VPN is active fails with `bind socket to network ... EPERM` even when browser/curl access works.
- If AI analysis fails, inspect logcat for `AI analysis failed`, `bind socket`, `EPERM`, gateway HTTP errors, and `mimo_analysis.wav` cache activity.

## Audio route pitfalls

- WebRTC call audio uses communication mode through `AudioRouteController`.
- AI synthesized speech should enter communication mode and use `AudioAttributes.USAGE_VOICE_COMMUNICATION` plus `CONTENT_TYPE_SPEECH` so it follows the call route.
- Release/stop the previous AI `MediaPlayer` before starting a new TTS playback and delete `mimo_analysis.wav` from cache on completion/error/destroy.

## Build and release notes

- LSP diagnostics may be unavailable if the local IntelliJ server build is expired; use Gradle verification as the reliable signal in that case.
- Normal debug verification: `./gradlew :app:assembleDebug`.
- Unit test task may be `NO-SOURCE`; report that accurately rather than inventing test coverage.
- Release signing material lives under `temp/signing/`.
- When asked to build both 32-bit and 64-bit APKs, produce signed `armeabi-v7a` and `arm64-v8a` artifacts and verify signing plus `native-code` metadata.
- When repeatedly installing through ADB, use `adb install -r` to preserve data and keep Android `versionCode` monotonically increasing.
