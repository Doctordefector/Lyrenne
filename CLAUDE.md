# Metrolist Desktop Port - Project Guide

## Overview
Porting Metrolist (Android YouTube Music client) to desktop using Compose Desktop (JVM).
- **Upstream**: https://github.com/mostafaalagamy/Metrolist (v13.3.0)
- **Desktop module**: `desktop/` folder
- **Shared module**: `innertube/` (sources included directly in desktop build, not as project dependency)

## Architecture

### Android (reference)
- Hilt DI, Room DB, Media3 ExoPlayer, Jetpack Compose
- Entry: `app/src/main/kotlin/com/metrolist/music/MainActivity.kt`
- Playback: `MusicService` (MediaLibraryService) + `PlayerConnection` bridge
- Database: Room v35 with `DatabaseDao` (150+ queries)
- ViewModels: 30+ in `viewmodels/`
- Queue system: `YouTubeQueue`, `ListQueue`, `YouTubeAlbumRadio`, etc.

### Desktop (our port)
- Compose Desktop, VLC (vlcj), SQLDelight, browser cookie extraction for auth
- Entry: `desktop/src/main/kotlin/com/metrolist/music/desktop/Main.kt`
- Singleton managers instead of Hilt: `AuthManager`, `DatabaseHelper`, `PreferencesManager`, `DownloadManager`
- Player: `DesktopPlayer` (VLC-based, 448 lines)
- DB: SQLDelight schema in `desktop/src/main/sqldelight/.../Metrolist.sq`

## Desktop Port Status

### Fully Working
- Authentication via browser cookie extraction (Opera, Chrome, Edge, Brave, Vivaldi)
- Personalized home feed (requires correct SESSION_INDEX + X-Goog-AuthUser header)
- YouTube Music library sync (songs, albums, artists, playlists)
- Search with filters (songs, videos, albums, artists, playlists)
- Home screen with continuations (loads multiple pages of recommendations)
- Audio playback via VLC (streaming + local files)
- Download management with progress tracking
- Queue management (add, remove, shuffle, repeat)
- Media key shortcuts (play/pause, next, prev, etc.)
- Settings UI with persistence
- Material3 theme with system dark/light detection (Windows registry, macOS defaults, Linux GTK)
- Detail screens: Album, Artist, Playlist (with full navigation stack)
- Seekable progress bar in MiniPlayer
- Navigation: stack-based push/pop for detail screens
- Lyrics display (synced + plain) via lrclib with auto-scroll
- Audio quality selection (respects user preference: 128/192/256/320 kbps)
- Queue persistence across app restarts (SQLDelight)
- Discord Rich Presence via kizzy (shows current song on Discord)
- Account info display (name, handle, avatar from YouTube)

### Partially Implemented (UI exists, backend stubbed)
- Skip silence / normalize audio toggles (UI only)
- Drag-to-reorder in queue (visual handle shown, no drag binding)

### Not Yet Implemented
- Podcast support
- Listen Together
- Music recognition
- Desktop notifications
- Last.fm scrobbling

## Key Files

### Desktop Source (`desktop/src/main/kotlin/com/metrolist/music/desktop/`)
| File | Purpose |
|------|---------|
| Main.kt | Entry point, window setup |
| auth/AuthManager.kt | YouTube auth, credentials, ytcfg fetching |
| auth/BrowserCookieExtractor.kt | Chromium cookie decryption (AES-256-GCM + DPAPI) |
| db/DatabaseHelper.kt | SQLDelight DB abstraction |
| playback/DesktopPlayer.kt | VLC playback engine |
| download/DownloadManager.kt | Download queue |
| media/MediaKeyHandler.kt | Keyboard shortcuts |
| settings/PreferencesManager.kt | User preferences |
| sync/LibrarySync.kt | Library sync |
| ui/App.kt | Main navigation layout |
| ui/screens/HomeScreen.kt | Home feed with continuations |
| ui/screens/SearchScreen.kt | Search |
| ui/screens/LibraryScreen.kt | Library tabs |
| ui/screens/QueueScreen.kt | Queue overlay |
| ui/screens/LoginScreen.kt | Login flow (browser cookie picker) |
| ui/screens/SettingsScreen.kt | Settings + account display |
| ui/screens/AlbumScreen.kt | Album detail with tracks, play all, shuffle |
| ui/screens/ArtistScreen.kt | Artist detail with sections, description |
| ui/screens/PlaylistScreen.kt | Playlist detail with pagination |
| ui/screens/ErrorUtils.kt | User-friendly error messages |
| ui/components/MiniPlayer.kt | Player bar |
| ui/components/LyricsPanel.kt | Synced/plain lyrics sidebar with auto-scroll |
| lyrics/LyricsManager.kt | Lyrics fetching via lrclib |
| integration/DiscordRPC.kt | Discord Rich Presence via kizzy |
| timber/log/Timber.kt | SLF4J-based shim for Android Timber |

### InnerTube modifications (shared with Android)
| File | Change |
|------|--------|
| InnerTube.kt | Added `accountIndex` field, `X-Goog-AuthUser` header in all auth requests |
| YouTube.kt | Exposed `accountIndex` property |

### Build
- `desktop/build.gradle.kts` - Compose Desktop config, JVM 21, VLC, JavaFX, SQLDelight
- Root `settings.gradle.kts` must include `desktop` module
- Root `build.gradle.kts` must declare Compose Desktop + Kotlin Compose plugins
- **Shared module sources**: innertube, lrclib, kizzy included via `kotlin.srcDir()` (not project dependencies — Android libraries can't be consumed by JVM)
- **Timber shim**: `desktop/src/main/kotlin/timber/log/Timber.kt` provides SLF4J-backed drop-in for Android's Timber
- Dependencies: vlcj 4.8.3, Coil 3.3.0, Ktor 3.4.1, SQLDelight 2.0.2, brotli, NewPipeExtractor, org.json, Ktor CIO

## Authentication System

### How it works
1. **BrowserCookieExtractor** detects installed Chromium browsers and decrypts their cookie databases
2. Cookies are decrypted using Windows DPAPI (for master key) + AES-256-GCM (for individual cookies)
3. Modern Chromium prepends a 32-byte binding hash to decrypted values — must be stripped
4. **Domain preference**: `.youtube.com` cookies take priority over `.google.com` (critical for SIDCC, PSIDCC, PSIDTS)
5. **AuthManager.saveCredentials()** fetches YouTube Music page HTML to extract ytcfg values:
   - `DATASYNC_ID` — Gaia ID for `onBehalfOfUser` (strip `||` suffix)
   - `SESSION_INDEX` — Google account index (critical for multi-account users)
   - `visitorData` — anonymous visitor tracking ID
6. `X-Goog-AuthUser` header must match SESSION_INDEX in all authenticated API requests
7. Locked browser cookie DBs are handled via robocopy fallback on Windows

### Key gotchas
- Opera stores cookies in `%APPDATA%` (Roaming), other browsers use `%LOCALAPPDATA%`
- Without correct SESSION_INDEX, YouTube returns generic (non-personalized) content
- SAPISIDHASH = `SHA1(timestamp + " " + SAPISID + " " + origin)`, sent as `Authorization: SAPISIDHASH ts_hash`
- The ytcfg page fetch needs a current User-Agent (Chrome 137+); old ones get "browser deprecated"

## Development Notes
- VLC must be installed on the system for playback to work
- Stream URLs fetched using InnerTube clients: ANDROID_VR_NO_AUTH → IOS → WEB_REMIX fallback
- Not a git repo locally (Windows `nul` file causes issues); sync via robocopy from fresh clone
- Platform paths: Windows `%APPDATA%/Metrolist`, Mac `~/Library/Application Support/Metrolist`, Linux `~/.config/metrolist`
- Credentials stored at `<appdata>/Metrolist/credentials.json`
- Delete credentials.json to force re-login

## Priority Work Items
1. **Last.fm scrobbling** integration
2. **Drag-to-reorder** in queue
3. **Podcast support**
4. **Desktop notifications** for song changes
5. **Discord settings UI** (token input, enable/disable in Settings screen)
