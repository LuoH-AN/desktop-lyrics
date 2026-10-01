<div align="center">
  <img src="docs/assets/app-icon.svg" width="104" alt="Desktop Lyrics app icon">

# Desktop Lyrics for Android

**A polished, real-time, minimal floating lyrics overlay for Android.**

[Video demo](https://www.bilibili.com/video/BV1jNu66eEkr/) · [Download](https://github.com/LuoH-AN/desktop-lyrics/releases/latest) · [简体中文](./README.md) · [Privacy](./PRIVACY.md) · [Changelog](./CHANGELOG.md)

[![Latest Release](https://img.shields.io/github/v/release/LuoH-AN/desktop-lyrics?display_name=tag&sort=semver&label=release)](https://github.com/LuoH-AN/desktop-lyrics/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/LuoH-AN/desktop-lyrics/total?label=downloads)](https://github.com/LuoH-AN/desktop-lyrics/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)
</div>

## What is Desktop Lyrics?

Desktop Lyrics is a real-time Android floating lyrics overlay. It reads the active player's public Android MediaSession locally, so it can keep receiving track metadata and playback progress even when the player's own notification is hidden. Notification access must still be granted to Desktop Lyrics itself.

The app queries public lyrics providers directly and does not require a private backend.

## Video demo

[![Desktop Lyrics for Android — real-time floating lyrics for Apple Music and Spotify](./docs/assets/desktop-lyrics-cover.png)](https://www.bilibili.com/video/BV1jNu66eEkr/)

The video shows an earlier UI; the current source uses only a compact lyrics window. Watch on Bilibili: [Desktop Lyrics for Android — Apple Music / Spotify real-time floating lyrics](https://www.bilibili.com/video/BV1jNu66eEkr/).

## Highlights

- Native Material UI across the player, settings, managers, and editors, with native lyric text rendering and a restrained monochrome theme
- Local, real-time track title, artist, album, playback state, progress, and artwork
- Parallel searches across LRCLIB, QQ Music, and NetEase Cloud Music, ranked by title, artist, album, duration, and version metadata; an iTunes catalog lookup can supply localized aliases when every direct search fails
- QQ Music and NetEase word-timed lyrics and official translations, with original, bilingual, or translated display modes
- A compact, lyrics-only overlay with drag, position lock/unlock, and close controls; no expanded mode, overlay full-screen view, rotation, or popup menu
- A lyric icon just left of “⋯” in the bottom player toggles the overlay and highlights when active; detailed settings remain under “⋯ → Settings”
- Show 0–2 preceding and following lines, with automatic height and time-aware scrolling of the current line
- Adjustable font size from 35% to 150%, with an appearance/context preview in settings
- Explicit “0.1 s earlier”, “0.1 s later”, and reset actions within ±5 seconds, remembered per track and source
- When no lyrics can be found, manually supply a standard LRC file: matched by title + artist, supports word-timed tags, translations (a second line under the same timestamp), and `[offset:]`; once set, both the home screen and the overlay use it without going online, and deleting it restores automatic matching
- Untimed plain lyrics remain usable with a clear label and smooth progress-based scrolling
- Optional on-device translation with downloadable ML Kit language packs, plus editable DeepSeek, GLM, Gemini, or custom Chat Completions-compatible API profiles
- Transparent, translucent, and opaque overlay backgrounds
- Keeps the screen awake while the overlay is visible and releases that behavior when closed
- Tries multiple candidates, rejects empty, title-only, credit-only, and provider-placeholder lyrics, supports symbol-only titles and cross-script aliases, with a 10-second budget per parallel provider round; identity lookup and subsequent rounds can take longer overall
- Manage remembered lyric choices by song, search the history, preview QQ Music, NetEase, and LRCLIB versions, choose a result, restore the initial match, or clear the complete matching cache
- Grouped monochrome settings and confirmation before clearing matching records or deleting synchronization memories
- A lightweight GitHub Releases check runs at most once per day when opening settings; update prompts can be postponed or ignored for that release, and a manual check remains available

## Requirements and compatibility

- Android 8.0 (API 26) or later
- A 64-bit ARM device (arm64-v8a); 32-bit-only devices and x86 emulators are not supported by the release APK
- Android System WebView (retained only for the overlay's hidden matching/cache compatibility engine, not visible UI)
- A music player that exposes a standard Android MediaSession

Apple Music and Kuwo Music have been tested on a vivo device. The implementation also recognizes common players such as QQ Music, NetEase Cloud Music, Kugou Music, Spotify, YouTube Music, TIDAL, Musicolet, AIMP, and VLC.

Long-running behavior may vary with vendor-specific battery restrictions. If the overlay is stopped in the background, allow auto-start and set the app's battery policy to unrestricted.

## Install and use

1. Download the latest APK from [Releases](https://github.com/LuoH-AN/desktop-lyrics/releases/latest).
2. Install and open Desktop Lyrics.
3. Grant Notification Access and the Display over other apps permission.
4. Tap the lyric icon to the left of “⋯” in the bottom player to enable the overlay; tap it again to disable it. Detailed controls remain under “⋯ → Settings”. Granting permission does not itself enable the overlay.
5. Drag the lyrics area to move the window. Use the lock icon to lock/unlock its position, or × to close it.
6. Adjust font size and preceding/following line counts in settings. Zero context shows only the current line, plus its translation when enabled.
7. If lyrics lag, make them earlier; if they lead, make them later.
8. Missing lyrics show a message without a retry button. Version management and custom LRC import remain available in the app settings.

## Permissions and privacy

| Permission | Purpose |
| --- | --- |
| Notification Access | Access public MediaSession data; notification message bodies are not read |
| Display over other apps | Show the floating lyrics overlay |
| Network access | Search public lyrics/artwork providers and perform a low-frequency GitHub release check |
| Foreground service | Keep a user-started overlay running in the background |

Desktop Lyrics does not request location or microphone permission, upload location data, or upload a complete listening history. When all direct lyrics searches fail, an iTunes Search request may be used only to resolve localized track and artist aliases. Optional translation sends lyrics only to the translation mode or API selected by the user. See [PRIVACY.md](./PRIVACY.md) for details.

## Lyrics and artwork providers

The app queries LRCLIB, QQ Music, and NetEase Cloud Music on demand. Lyrics, artwork, and related data belong to their respective platforms and rights holders. This repository does not host a lyrics database. Provider availability may vary by region and platform policy.

## Build from source

Open the project in Android Studio, or use JDK 17 with an Android SDK:

```bash
./gradlew assembleRelease
```

To build a signed APK, copy `keystore.properties.example` to `keystore.properties` and supply your own signing information. Real keys and passwords are excluded by `.gitignore`.

## Project information

- Current release: `1.06` (versionCode 106)
- Android package: `com.tcrrry.desktoplyrics`
- Author: Bilibili `@Tcrrrry`

If Desktop Lyrics is useful to you, consider giving the repository a **Star** so more Android users can discover it.
