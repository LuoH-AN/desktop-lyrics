<div align="center">
  <img src="docs/assets/app-icon.svg" width="104" alt="桌面歌词应用图标">

# 桌面歌词 / Desktop Lyrics

**精致、实时、极简的 Android 桌面歌词软件**

Android floating lyrics overlay with MediaSession playback detection, synchronized lyrics, configurable context, and position locking.

[B站视频演示](https://www.bilibili.com/video/BV1jNu66eEkr/) · [下载最新版](https://github.com/LuoH-AN/desktop-lyrics/releases/latest) · [English](./README_EN.md) · [隐私说明](./PRIVACY.md) · [更新记录](./CHANGELOG.md)

[![Latest Release](https://img.shields.io/github/v/release/LuoH-AN/desktop-lyrics?display_name=tag&sort=semver&label=release)](https://github.com/LuoH-AN/desktop-lyrics/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/LuoH-AN/desktop-lyrics/total?label=downloads)](https://github.com/LuoH-AN/desktop-lyrics/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)
</div>

## 这是什么

桌面歌词是一款本地实时同步的 Android 歌词悬浮窗。它直接读取播放器公开的 Android MediaSession；在授予桌面歌词“通知使用权”后，即使关闭音乐软件自身的通知展示，也能持续获取当前歌曲和播放进度。

应用按需直连公共歌词源，不依赖自建服务器。桌面上只保留小歌词窗：显示歌词、拖动、锁定/解锁和关闭；字号、前后句数、颜色和背景统一在设置中调整。

## 视频演示

[![可能是最精致的安卓桌面歌词软件｜Apple Music / Spotify 实时悬浮显示](./docs/assets/desktop-lyrics-cover.png)](https://www.bilibili.com/video/BV1jNu66eEkr/)

视频展示的是旧版界面；当前源码已改为仅保留小歌词窗。点击封面或前往 B站观看：[可能是最精致的安卓桌面歌词软件｜Apple Music / Spotify 实时悬浮显示](https://www.bilibili.com/video/BV1jNu66eEkr/)。

## 核心功能

- 本机实时读取歌名、歌手、专辑、播放状态、进度和封面
- 直连 LRCLIB、QQ 音乐和网易云，并行搜索后综合歌名、歌手、专辑、时长和版本信息选择可靠歌词；必要时借助 iTunes 目录补充跨语言别名后重新匹配
- 支持 QQ 音乐与网易云逐字歌词、官方翻译，可切换原文、双语或中文显示；同一来源存在多个版本时也会继续比较完整度
- 悬浮窗仅显示歌词，支持拖动、锁定/解锁位置、关闭；不再提供展开、旋转、全屏、播放控制或点击弹出菜单
- 主页保留播放控制和常驻的桌面歌词开关，权限授权不等于自动开启悬浮窗
- 当前句前后各可显示 0–2 句，窗口高度随字号和上下文自动适应；当前长句按时长滚动
- 歌词字号支持 35%–150%，设置页提供字号、颜色、背景与上下文的示意预览
- 同步校准提供「提前 0.1 秒」「延后 0.1 秒」「恢复同步」，范围 ±5 秒，按歌曲和歌词源分别记忆
- 获取不到歌词时可手动指定标准 LRC 时间轴歌词：按「歌名 + 歌手」匹配，支持逐字标签、翻译（同一时间戳第二行）、`[offset:]`，命中后主页与悬浮窗直接使用不再联网，删除即恢复自动匹配
- 没有时间轴的纯文本歌词也能按歌曲进度平滑滚动，并明确标注“无时间轴歌词”
- 缺少官方译文时可选择离线机翻，语言包按需下载与删除；也可配置兼容 Chat Completions 的 DeepSeek、智谱 GLM、Gemini 或其他 HTTPS API
- 透明、半透明、不透明三种歌词窗背景
- 悬浮窗开启期间保持屏幕常亮，关闭后自动恢复系统息屏策略
- 歌词搜索会自动尝试多个候选，跳过空歌词、只有歌名/制作信息的平台占位内容和歌手不符的同名结果；支持纯符号歌名、跨文字标题及片头曲等附注精简，每轮平台并行查询有 10 秒等待预算；跨语言补充查询可能增加总耗时
- 设置页提供歌词源管理：按歌曲合并 QQ 音乐、网易云和 LRCLIB 的选择记录，可搜索歌曲、预览各来源版本、指定结果、恢复初始匹配或清除全部匹配缓存
- 设置界面使用黑白灰分组；清除匹配记录与缓存、删除同步记忆均需确认，并说明影响范围
- 启动设置页后每天最多静默检查一次 GitHub Releases；发现新版本时可跳转更新、稍后处理或仅忽略当前版本，也可在使用说明底部手动检查

## 兼容性

- Android 8.0（API 26）及以上
- 64 位 ARM 设备（arm64-v8a）；覆盖绝大多数现代 Android 手机，不支持纯 32 位旧机与 x86 模拟器
- 需要 Android System WebView
- 播放器需要提供标准 Android MediaSession

目前已在 vivo 设备上验证 Apple Music 与酷我音乐。代码也适配 QQ 音乐、网易云音乐、酷狗音乐、Spotify、YouTube Music、TIDAL、Musicolet、AIMP、VLC 等常见播放器。

不同手机厂商的后台省电策略可能影响长时间运行。如果悬浮窗被系统清理，请允许应用自启动，并将电池策略设为“不限制”。

## 安装与使用

1. 前往 [Releases](https://github.com/LuoH-AN/desktop-lyrics/releases/latest) 下载最新版 APK。
2. 安装并打开“桌面歌词”。
3. 依次授予“通知使用权”和“悬浮窗权限”。
4. 在主页打开「桌面歌词」开关，然后播放音乐。只在应用内看歌词时，不必打开悬浮窗。
5. 拖动歌词区域移动小窗；点击锁图标锁定/解锁位置，点击 × 关闭。锁定位置不会让解锁和关闭按钮失效。
6. 在设置中调整字号及当前句前后各显示几句；前后都设为 0 时只显示当前句（双语模式可另显示当前句译文）。
7. 歌词慢了点「提前」，歌词快了点「延后」，不需要判断偏移正负号。
8. 找不到歌词时只显示提示，不提供重试按钮。版本预览、手动选择与导入 LRC 仍在应用内设置中管理。

实际搜索流程与缓存差异见 [歌词搜索说明](./docs/lyrics-search.md)。

## 权限与隐私

| 权限 | 用途 |
| --- | --- |
| 通知使用权 | 仅用于访问其他播放器公开的 MediaSession，不读取通知正文 |
| 悬浮窗 | 在其他应用上方显示实时歌词 |
| 网络访问 | 向公共歌词/音乐平台搜索歌词和备用封面，并按低频率检查 GitHub 新版本 |
| 前台服务 | 在退到后台后保持用户主动开启的悬浮窗 |

应用不申请定位权限，不读取麦克风，不上传位置或完整播放历史。详细内容见 [PRIVACY.md](./PRIVACY.md)。

## 歌词与封面来源

应用按需查询 LRCLIB、QQ 音乐和网易云。数据、歌词和封面版权归相应平台及权利人所有。本项目不托管歌词数据库，用户应遵守所在地法律以及相关服务条款；各来源的可用性可能随地区和平台策略变化。

## 从源码构建

使用 Android Studio 打开仓库，或在已配置 Android SDK 与 JDK 17 的环境中运行：

```bash
./gradlew assembleRelease
```

如需生成签名 APK，将 `keystore.properties.example` 复制为 `keystore.properties`，然后填写自己的签名信息。真实密钥与密码已被 `.gitignore` 排除。

## 常见问题

### 它会录制或分析手机正在播放的声音吗？

不会。应用不申请麦克风权限，也不录制系统音频；歌曲信息来自播放器公开的 MediaSession。

### 为什么需要“通知使用权”？

这是 Android 提供跨应用访问 MediaSession 的系统入口。桌面歌词只使用媒体会话数据，不读取聊天或普通通知正文。

### 所有播放器都能使用吗？

只要播放器正确提供标准 MediaSession，通常就能读取。个别播放器、系统定制或省电策略可能导致兼容性差异。

### 歌词为什么偶尔需要几秒才能出现？

首次识别歌曲时需要并行查询多个公开来源、验证候选质量，必要时再补充跨语言别名重试。匹配完成后，歌词会按照本机播放进度实时同步。

## 项目信息

- 当前正式版：`1.0`（versionCode 10）
- Android 包名：`com.luoh.music.lrc`
- 维护：[@LuoH-AN](https://github.com/LuoH-AN)
- 原始项目由 B站 `@Tcrrrry` 创作，本仓库在其基础上继续开发，特此致谢

如果这个项目对你有帮助，欢迎点亮右上角的 **Star**，让更多需要 Android 桌面歌词的人看到它。
