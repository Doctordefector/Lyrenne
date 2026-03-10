# Metrolist Desktop Port - Project Guide

## Overview
Porting Metrolist (Android YouTube Music client) to desktop using Compose Desktop (JVM).
- **Upstream**: https://github.com/mostafaalagamy/Metrolist (v13.3.0)
- **Desktop module**: `desktop/` folder
- **Shared modules**: `innertube/`, `lrclib/`, `kizzy/`, `lastfm/`, `shazamkit/` (sources included directly via `kotlin.srcDir()`, not as project dependencies)
- **Codebase**: ~12,000 lines of Kotlin across 34 files + 1 protobuf file

## Architecture

### Android (reference)
- Hilt DI, Room DB, Media3 ExoPlayer, Jetpack Compose
- Entry: `app/src/main/kotlin/com/metrolist/music/MainActivity.kt`
- Playback: `MusicService` (MediaLibraryService) + `PlayerConnection` bridge
- Database: Room v35 with `DatabaseDao` (150+ queries)
- ViewModels: 30+ in `viewmodels/`

### Desktop (our port)
- Compose Desktop, VLC (vlcj), SQLDelight, browser cookie extraction for auth
- Entry: `desktop/src/main/kotlin/com/metrolist/music/desktop/Main.kt`
- Singleton managers instead of Hilt: `AuthManager`, `DatabaseHelper`, `PreferencesManager`, `DownloadManager`
- Player: `DesktopPlayer` (VLC-based via vlcj)
- DB: SQLDelight schema in `desktop/src/main/sqldelight/.../Metrolist.sq`
- Listen Together: Protobuf + OkHttp WebSocket (`desktop/src/main/proto/listentogether/`)

## Desktop Port Status

### Fully Working
- Authentication via browser cookie extraction (Opera, Chrome, Edge, Brave, Vivaldi, Firefox)
- Two login paths: "New Browser" (temp profile) or "Import from Installed" (extract cookies)
- Personalized home feed with continuations (up to 5 pages)
- YouTube Music library sync (songs, albums, artists, playlists)
- Search with filters (songs, videos, albums, artists, playlists) + suggestions
- Audio playback via VLC (streaming + local files)
- Download management with progress tracking
- Queue management (add, remove, shuffle, repeat, play next)
- Media key shortcuts (space, Ctrl+P, Ctrl+Right/Left, Ctrl+S, Ctrl+R, hardware media keys)
- Text field focus detection — keyboard shortcuts suppressed while typing
- Settings UI with persistence (properties file)
- Material3 theme with system dark/light detection (Windows registry, macOS defaults, Linux GTK)
- Detail screens: Album, Artist, Playlist (with full navigation stack)
- Seekable progress bar in MiniPlayer
- Lyrics display (synced + plain) via lrclib with auto-scroll
- Audio quality selection (128/192/256/320 kbps)
- Queue persistence across app restarts (SQLDelight)
- Discord Rich Presence via named pipe IPC (Windows `\\.\pipe\discord-ipc-N`, Unix `/tmp/discord-ipc-N`)
- Last.fm scrobbling (now-playing + scrobble at 50%/240s threshold)
- Desktop notifications (AWT SystemTray pop-ups on song change)
- Auto-updater (GitHub releases: Doctordefector/Metrolist-Desktop)
- Listen Together (WebSocket, protobuf messages, room create/join, bidirectional playback sync, suggestions, session persistence with 10-min grace, reconnect with exponential backoff)
- Account info display (name, handle, avatar from YouTube)
- Library search/filter across all tabs (songs, albums, artists, playlists, downloads)
- Library sorting by name, date added, play count (ascending/descending)
- Podcast support (podcast detail screen, episode playback, search integration)
- Music recognition via Shazam API (JVM microphone capture, Vibra FFT fingerprinting, shazamkit module)
- Skip Silence (VLC compressor audio filter with aggressive threshold/ratio settings)
- Normalize Audio (VLC normvol audio filter)

### Partially Implemented
- Drag-to-reorder in queue (visual drag handle + pointerInput, reorder logic exists but needs polish)

### Not Yet Implemented
- Context menus on MiniPlayer / search results (Play Next, Add to Queue, Add to Playlist)
- Play All / Shuffle All buttons in Library songs tab

## Key Files

### Desktop Source (`desktop/src/main/kotlin/com/metrolist/music/desktop/`)

#### Core
| File | Purpose |
|------|---------|
| Main.kt | Entry point, window setup, service initialization chain |
| ui/App.kt | Main navigation (Home/Search/Library/Settings), detail screen stack, keyboard shortcuts |

#### Authentication
| File | Purpose |
|------|---------|
| auth/AuthManager.kt | YouTube auth, credentials persistence, ytcfg/SESSION_INDEX/DATASYNC_ID, SAPISIDHASH |
| auth/BrowserCookieExtractor.kt | Chromium + Firefox cookie DB detection & decryption (AES-256-GCM + DPAPI) |
| auth/BrowserLoginHelper.kt | Browser launching with temp profile, cookie polling after browser close |

#### Data & Playback
| File | Purpose |
|------|---------|
| db/DatabaseHelper.kt | SQLDelight wrapper, queue persistence, song/album/artist/playlist CRUD |
| playback/DesktopPlayer.kt | VLC playback engine, queue management, shuffle/repeat, stream URL resolution |
| download/DownloadManager.kt | Download queue, HTTP streaming with progress, m4a storage |
| sync/LibrarySync.kt | YouTube library sync (liked songs, albums, artists, playlists) with pagination |
| settings/PreferencesManager.kt | Properties file persistence for all user preferences |

#### Media & Input
| File | Purpose |
|------|---------|
| media/MediaKeyHandler.kt | AWT KeyboardFocusManager shortcuts, text field focus suppression via AtomicInteger counter |

#### Integrations
| File | Purpose |
|------|---------|
| integration/DiscordRPC.kt | Named pipe IPC, frame protocol, presence updates on song change |
| integration/LastFmManager.kt | Scrobble scheduling, now-playing updates, API auth |
| notification/DesktopNotification.kt | AWT SystemTray notifications on song change |
| update/AutoUpdater.kt | GitHub API version check, ZIP download/extract, PowerShell launcher |

#### Listen Together
| File | Purpose |
|------|---------|
| listentogether/ListenTogetherClient.kt | WebSocket client, room state, roles, reconnect logic, session persistence |
| listentogether/ListenTogetherManager.kt | Bridges WebSocket events to DesktopPlayer, sync debouncing, position tolerance |
| listentogether/Protocol.kt | Message type constants, TrackInfo/UserInfo data classes |
| listentogether/MessageCodec.kt | Protobuf encoding/decoding with optional GZIP compression |

#### Lyrics
| File | Purpose |
|------|---------|
| lyrics/LyricsManager.kt | LrcLib fetch, LRC parsing, caching by songId |

#### UI Screens
| File | Purpose |
|------|---------|
| ui/screens/HomeScreen.kt | Home feed with continuations |
| ui/screens/SearchScreen.kt | Search with filters + suggestions |
| ui/screens/LibraryScreen.kt | Library tabs (Songs/Albums/Artists/Playlists/Downloads) |
| ui/screens/QueueScreen.kt | Queue overlay with drag-to-reorder |
| ui/screens/LoginScreen.kt | Login flow (new browser or import cookies) |
| ui/screens/SettingsScreen.kt | Settings + account display |
| ui/screens/AlbumScreen.kt | Album detail with tracks, play all, shuffle |
| ui/screens/ArtistScreen.kt | Artist detail with sections, description |
| ui/screens/PlaylistScreen.kt | Playlist detail with pagination |
| ui/screens/ListenTogetherScreen.kt | Room create/join, user list, suggestions, connection status |
| ui/screens/PodcastScreen.kt | Podcast detail with episode list, Play All/Shuffle, context menus |
| ui/screens/RecognitionScreen.kt | Music recognition UI (Ready/Listening/Processing/Success/Error states) |
| ui/screens/ErrorUtils.kt | User-friendly error messages |

#### UI Components
| File | Purpose |
|------|---------|
| ui/components/MiniPlayer.kt | Player bar with seek, controls, volume, queue/lyrics buttons |
| ui/components/LyricsPanel.kt | Synced/plain lyrics sidebar with auto-scroll |
| ui/theme/Theme.kt | Material3 color schemes, system theme detection |

#### Music Recognition
| File | Purpose |
|------|---------|
| recognition/DesktopMusicRecognizer.kt | JVM microphone capture, 44.1→16kHz resampling, Vibra FFT fingerprinting, Shazam API |

#### Logging
| File | Purpose |
|------|---------|
| timber/log/Timber.kt | SLF4J-backed drop-in shim for Android's Timber |

### InnerTube modifications (shared with Android)
| File | Change |
|------|--------|
| InnerTube.kt | Added `accountIndex` field, `X-Goog-AuthUser` header in all auth requests |
| YouTube.kt | Exposed `accountIndex` property |

### Build
- `desktop/build.gradle.kts` — Compose Desktop config, JVM 21, SQLDelight, protobuf
- Root `settings.gradle.kts` must include `desktop` module
- Root `build.gradle.kts` must declare Compose Desktop + Kotlin Compose plugins
- **Shared module sources**: innertube, lrclib, kizzy, lastfm, shazamkit included via `kotlin.srcDir()` (not project dependencies — Android libraries can't be consumed by JVM)
- **Timber shim**: SLF4J-backed drop-in for Android's Timber
- **Protobuf**: Plugin `com.google.protobuf` v0.9.4, proto files at `desktop/src/main/proto/`, needs `DuplicatesStrategy.EXCLUDE` on processResources
- **Dependencies**: vlcj 4.8.3, Coil 3.3.0, Ktor 3.4.1, SQLDelight 2.0.2, OkHttp 4.12.0, Protobuf-java 3.25.5, brotli, NewPipeExtractor, org.json, SLF4J simple
- **Distribution**: Portable ZIP only (no EXE/MSI installers)
- **CRITICAL: ZIP creation**: NEVER use PowerShell `Compress-Archive` — it uses backslashes in entry names which breaks Java's `ZipEntry.isDirectory()`. Use `7z a -tzip` instead

## Authentication System

### How it works
1. **BrowserCookieExtractor** detects installed Chromium + Firefox browsers and decrypts their cookie databases
2. Chromium cookies decrypted using Windows DPAPI (for master key) + AES-256-GCM (for individual cookies)
3. Modern Chromium prepends a 32-byte binding hash to decrypted values — must be stripped
4. **Domain preference**: `.youtube.com` cookies take priority over `.google.com` (critical for SIDCC, PSIDCC, PSIDTS)
5. **BrowserLoginHelper** can also launch a browser with a temp profile for fresh sign-in
6. **AuthManager.saveCredentials()** fetches YouTube Music page HTML to extract ytcfg values:
   - `DATASYNC_ID` — Gaia ID for `onBehalfOfUser` (strip `||` suffix)
   - `SESSION_INDEX` — Google account index (critical for multi-account users)
   - `visitorData` — anonymous visitor tracking ID
7. `X-Goog-AuthUser` header must match SESSION_INDEX in all authenticated API requests
8. Locked browser cookie DBs are handled via robocopy fallback on Windows

### Key gotchas
- Opera stores cookies in `%APPDATA%` (Roaming), other browsers use `%LOCALAPPDATA%`
- Without correct SESSION_INDEX, YouTube returns generic (non-personalized) content
- SAPISIDHASH = `SHA1(timestamp + " " + SAPISID + " " + origin)`, sent as `Authorization: SAPISIDHASH ts_hash`
- The ytcfg page fetch needs a current User-Agent (Chrome 137+); old ones get "browser deprecated"
- **CRITICAL: Cookies MUST be stored in plaintext.** DO NOT encrypt, hash, obfuscate, or add any "secure storage" layer to cookie/credential storage. Plaintext storage in credentials.json is intentional and required — never change this.

## Listen Together System

### Architecture
- **Server**: `wss://metroserverx.meowery.eu/ws` (WebSocket)
- **Protocol**: Protobuf messages with optional GZIP compression (>100 bytes), defined in `listentogether.proto`
- **Roles**: HOST (creates room) and GUEST (joins room) — both can send playback actions and track changes
- **Sync**: 1-second debounce threshold, 2-3 second position tolerance, buffering wait logic
- **Session**: 10-minute grace period on disconnect, reconnect with exponential backoff (1s-120s)
- **Message types**: CREATE_ROOM, JOIN_ROOM, PLAYBACK_ACTION, SUGGEST_TRACK, APPROVE_JOIN, KICK_USER, SYNC_STATE, etc.

## Keyboard Shortcuts
| Shortcut | Action | Works in text fields? |
|----------|--------|----------------------|
| Space | Play/Pause | No |
| Ctrl+P | Play/Pause | No |
| Ctrl+Right | Next track | No |
| Ctrl+Left | Previous track | No |
| Ctrl+S | Toggle shuffle | No |
| Ctrl+R | Toggle repeat | No |
| Ctrl+F | Focus search | No |
| Ctrl+Q | Toggle queue | No |
| Ctrl+L | Toggle lyrics | No |
| Escape | Close overlay / go back | Yes |
| Media keys | Play/Pause/Next/Prev/Stop | Yes (always work) |

Text field suppression uses `Modifier.suppressMediaKeys()` on all OutlinedTextField instances + `MediaKeyHandler.textInputActive` check in both AWT KeyEventDispatcher and Compose `onPreviewKeyEvent`.

## Development Notes
- VLC must be installed on the system for playback to work (bundled VLC also supported)
- Stream URLs fetched using InnerTube clients: ANDROID_VR_NO_AUTH → IOS → WEB_REMIX fallback
- Not a git repo locally (Windows `nul` file causes issues); sync via robocopy from fresh clone
- Platform paths: Windows `%APPDATA%/Metrolist`, Mac `~/Library/Application Support/Metrolist`, Linux `~/.config/metrolist`
- Credentials stored at `<appdata>/Metrolist/credentials.json`
- Delete credentials.json to force re-login
- All debug println converted to Timber logging (SLF4J-backed shim)
- DatabaseHelper.database is private — use DatabaseHelper methods, not direct DB access
- SQLDelight accessor is `metrolistQueries` (not `metrolistDatabaseQueries`)

## Version Management
- **Current version**: v2.3.0
- **Version must be updated in TWO places** when releasing:
  1. `desktop/build.gradle.kts` → `packageVersion = "X.Y.Z"`
  2. `desktop/.../update/AutoUpdater.kt` → `CURRENT_VERSION = "X.Y.Z"`
- Both MUST match — `packageVersion` controls the ZIP filename, `CURRENT_VERSION` is shown in Settings and used for update comparison

## Release Process (Step by Step)
1. **Bump version** in both places (build.gradle.kts `packageVersion` + AutoUpdater.kt `CURRENT_VERSION`)
2. **Build the portable distributable**:
   ```
   ./gradlew :desktop:createDistributable
   ```
   Output: `desktop/build/compose/binaries/main/app/Metrolist/` (folder with exe + runtime + resources)
3. **Create portable ZIP** (7z — NEVER use Compress-Archive, it creates backslash entries that break Java):
   ```powershell
   cd desktop/build/compose/binaries/main/app
   7z a -tzip "../Metrolist-X.Y.Z-portable.zip" "Metrolist/*"
   ```
4. **Push code** to GitHub (robocopy to temp dir, git init, commit, force push)
5. **Create GitHub release** with portable ZIP only:
   ```
   gh release create vX.Y.Z Metrolist-X.Y.Z-portable.zip --title "Metrolist Desktop vX.Y.Z" --notes "..."
   ```

## Distribution Strategy
- **PORTABLE ONLY** — no EXE installers, no MSI packages. Just the portable ZIP.
- Each release has exactly ONE artifact: `Metrolist-X.Y.Z-portable.zip`
- Users extract and run — no installation needed
- Downloads, updates, preferences, and database all live next to the app (not in %APPDATA%/Roaming)
- Do NOT build `packageExe` or `packageMsi` — only `createDistributable`

## File Storage Paths
- **Downloads**: `<app-dir>/Downloads/` (configurable via Settings folder picker)
- **Updates staging**: `<app-dir>/updates/` (with fallbacks to user.dir then temp)
- **Preferences**: `%APPDATA%/Metrolist/preferences.properties` (this one stays in AppData — it's config, not data)
- **Database**: `%APPDATA%/Metrolist/metrolist.db` (SQLDelight)
- **Credentials**: `%APPDATA%/Metrolist/credentials.json` (plaintext, intentional)
- **Cache**: `%LOCALAPPDATA%/Metrolist/Cache/`
- **CRITICAL**: Downloads and update staging must NEVER go to %APPDATA%/Roaming. They default to the app directory.

## GitHub & Release
- **Private repo**: https://github.com/Doctordefector/Metrolist-Desktop
- Local copy has `nul` file that breaks git — use temp dir copy for pushing
- Push workflow: robocopy to temp dir (excluding .gradle/.kotlin/build/.claude/nul), git init, commit, force push
- `gh` CLI at `C:\Program Files\GitHub CLI\gh.exe` (not in bash PATH, use full path), authenticated as Doctordefector
- Robocopy for push: Must use PowerShell `robocopy` (bash `robocopy` has path issues with /E flag)
- Upload ONLY the portable ZIP to each GitHub release

## Auto-Updater System

### How it works (end to end)
1. **Check**: `AutoUpdater.checkForUpdate()` hits GitHub API (`/repos/.../releases/latest`), compares `CURRENT_VERSION` against the latest tag using semver
2. **Detect portable**: Looks for `Metrolist-*-portable.zip` in release assets — if found, uses portable update path
3. **Download**: Streams the ZIP to `<app-dir>/updates/` with progress callbacks, shown in Settings UI
4. **Extract**: `extractZip()` extracts to a timestamped staging dir (`updates/staging-<timestamp>/`), cleans up old staging dirs first
5. **Verify**: Checks extracted contents for `Metrolist.exe` — rejects invalid packages
6. **Install**: User clicks "Install & Restart" → launches a PowerShell script that:
   - Waits for the current process to exit (polls by PID)
   - Uses `robocopy /E /IS /IT` to copy staging → app directory (overwrites everything)
   - Relaunches `Metrolist.exe`
   - Cleans up staging dir and ZIP
7. **App restarts** on the new version

### Key file
- `desktop/.../update/AutoUpdater.kt` — entire update lifecycle (check, download, extract, install script generation)

### Critical gotchas
- **ZIP must use forward slashes**: Java's `ZipEntry.isDirectory()` only checks for trailing `/`. PowerShell's `Compress-Archive` uses `\` which breaks extraction. ALWAYS use 7z to create release ZIPs.
- **Backslash normalization**: The extractor normalizes `\` → `/` as a safety net, but don't rely on it — use 7z
- **Timestamped staging**: Staging dirs use `staging-<millis>` to avoid conflicts from previous failed extractions where `deleteRecursively()` silently failed on locked files
- **`getUpdateDirectory()`**: Returns `<app-dir>/updates/`. Resolves app dir from JAR `codeSource.location`, falls back to `user.dir`, then system temp. NEVER `%APPDATA%/Roaming`
- **`findAppDirectory()`**: Walks up from JAR location to find the Metrolist root. For Compose Desktop distributable: `Metrolist/app/Metrolist.jar` → walks up 2 levels → `Metrolist/`
- **Chicken-and-egg**: If the updater itself has a bug, users must manually download the fixed version. The running binary's updater code is what executes, not the new version's

### PowerShell update script (`metrolist-update.ps1`)
Generated dynamically by `buildPortableUpdateScript()`. Key steps:
```powershell
# Wait for app to exit
while (Get-Process -Id $PID -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 1 }
# Copy new files over old
robocopy "$sourcePath" "$destPath" /E /IS /IT
# Restart
Start-Process "$exePath"
# Cleanup
Remove-Item "$stagingRoot" -Recurse -Force
```

## Priority Work Items
1. **Context menus** — Play Next, Add to Queue, Add to Playlist on MiniPlayer / search results
2. **Play All / Shuffle All** — buttons in Library songs tab
