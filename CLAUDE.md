# Lyrenne Port - Project Guide

## Overview
Porting Lyrenne (Android YouTube Music client) to desktop using Compose Desktop (JVM).
- **Upstream**: https://github.com/MetrolistGroup/Metrolist (shared modules synced to v13.6.0, 2026-07-07; upstream is in maintenance mode)
  - Sync method: `git checkout v13.6.0 -- innertube betterlyrics shazamkit lrclib kugou lastfm` from `upstream` remote, then re-apply the accountIndex patch (see InnerTube modifications below)
  - NewPipe.kt deliberately kept at pre-v13.4.1 version + NewPipeExtractor v0.26.0: upstream's LyrenneExtractor fork ships Java 25 bytecode (we target JVM 21), and desktop never calls NewPipeUtils (streams via direct-URL clients)
  - kizzy module: upstream deleted it; our copy remains in repo but is NO LONGER compiled into desktop (was never referenced — desktop Discord RPC is the named-pipe DiscordRPC.kt)
- **Desktop module**: `desktop/` folder
- **Shared modules**: `innertube/`, `lrclib/`, `betterlyrics/`, `kugou/`, `kizzy/`, `lastfm/`, `shazamkit/` (sources included directly via `kotlin.srcDir()`, not as project dependencies)
- **Codebase**: ~12,000 lines of Kotlin across 34 files + 1 protobuf file

## Architecture

### Android (reference)
- Hilt DI, Room DB, Media3 ExoPlayer, Jetpack Compose
- Entry: `app/src/main/kotlin/com/metrolist/music/MainActivity.kt` (upstream's package — see the rename section)
- Playback: `MusicService` (MediaLibraryService) + `PlayerConnection` bridge
- Database: Room v35 with `DatabaseDao` (150+ queries)
- ViewModels: 30+ in `viewmodels/`

### Desktop (our port)
- Compose Desktop, VLC (vlcj), SQLDelight, browser cookie extraction for auth
- Entry: `desktop/src/main/kotlin/com/lyrenne/desktop/Main.kt`
- Singleton managers instead of Hilt: `AuthManager`, `DatabaseHelper`, `PreferencesManager`, `DownloadManager`
- Player: `DesktopPlayer` (VLC-based via vlcj)
- DB: SQLDelight schema in `desktop/src/main/sqldelight/.../Lyrenne.sq`
- Listen Together: Protobuf + OkHttp WebSocket (`desktop/src/main/proto/listentogether/`)

## Desktop Port Status

### Fully Working
- Authentication via browser cookie extraction (Opera, Chrome, Edge, Brave, Vivaldi, Firefox)
- One login path: "Sign in with browser" (temp profile). Import-from-installed was removed in 2.5.1, see Authentication System
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
- Auto-updater (GitHub releases: Doctordefector/Lyrenne)
- Listen Together (WebSocket, protobuf messages, room create/join, bidirectional playback sync, suggestions, session persistence with 10-min grace, reconnect with exponential backoff)
- Account info display (name, handle, avatar from YouTube)
- Library search/filter across all tabs (songs, albums, artists, playlists, downloads)
- Library sorting by name, date added, play count (ascending/descending)
- Podcast support (podcast detail screen, episode playback, search integration)
- Music recognition via Shazam API (JVM microphone capture, Vibra FFT fingerprinting, shazamkit module)
- Skip Silence (VLC compressor audio filter with aggressive threshold/ratio settings)
- Normalize Audio (VLC normvol audio filter)
- Equalizer (vlcj 10-band EQ, VLC presets + custom bands + preamp, real-time)
- Listening stats (most played songs/artists/albums by period, totals) + listen history tab with clear
- Playback speed control (0.25x–3x via VLC setRate, persisted, MiniPlayer menu)
- Sleep timer (5-60 min or end-of-track, MiniPlayer menu)
- Crossfade (early-transition + fade-in approach — VLC single decoder can't overlap; setting 0-12s)
- Start Radio (RDAMVM radio queue from any song) + Auto-Queue Related Songs setting (appends near queue end)
- Explore screen (new releases, moods & genres via innertube, Charts via generic BrowseScreen FEmusic_charts)
- Generic BrowseScreen for any browseId (moods, charts drill-down)
- Local playlists (create/rename/delete, add/remove songs, move up/down reorder, LocalPlaylistScreen)
- Auto playlists (Liked Songs, Downloaded, Most Played) in Library playlists tab
- PlaylistPickerDialog with inline "New playlist" creation
- Context menus everywhere (MiniPlayer, search results, library songs: Play Next/Add Queue/Add to Playlist/Start Radio/Download/Like)
- Auto-download on like (setting)
- Search history (recorded on search, shown when idle, per-item delete + clear all, pause setting)
- Hide explicit content filter (search, home, explore, browse)
- Quick picks home section (radio seeded from last listen event, setting)
- Randomize home sections (setting)
- Lyrics: 5-provider chain (BetterLyrics → LrcLib → KuGou → YouTube lyrics → YouTube transcript)
- Lyrics customization (text size 12-32pt, alignment left/center/right, click-to-seek toggle)
- Recognition history (RecognitionHistory table, list on Ready state, play from history)
- Privacy: pause listen history + pause search history settings
- Content region/language settings (gl/hl via YouTube.locale, curated lists + system default)
- Proxy support (HTTP/SOCKS + auth via YouTube.proxy/proxyAuth, settings UI, applyNetworkPreferences() in Main.kt)
- Backup & restore (ZIP of preferences.properties + lyrenne.db + credentials.json, BackupManager, restore needs restart)
- Library grid/list view toggle (albums + artists tabs, LibraryViewMode pref)
- Play All / Shuffle All buttons (library songs tab, local/auto playlists)
- Car / USB export (`CarExport.kt`): "Export to Folder" button on Album/Playlist/LocalPlaylist writes loudness-normalized 320k MP3s named `01 - Artist - Title.mp3`; Settings → Storage → "Normalize Folder for Car / USB" runs the same pass over an existing folder into `<folder>/Normalized/`. Needs ffmpeg (bundled next to the exe or on PATH); "Force Dual Mono on Export" setting folds L+R for one-sided tracks

### Partially Implemented
- Drag-to-reorder in queue (visual drag handle + pointerInput, reorder logic exists but needs polish; local playlists use menu-based Move Up/Down instead)

### Not Yet Implemented
- AI lyrics translation (intentionally skipped — requires paid LLM API)
- Per-song EQ profiles, loudness-enhancement beyond normvol

## Key Files

### Desktop Source (`desktop/src/main/kotlin/com/lyrenne/desktop/`)

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
| download/CarExport.kt | ffmpeg-backed export/normalize to MP3 for USB/CD (loudnorm + stereo, optional dual mono) |
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
| ui/screens/LoginScreen.kt | Login flow (browser sign-in) |
| ui/screens/OnboardingScreen.kt | First-run setup wizard (7 steps) — see First-Run Onboarding |
| ui/screens/SettingsScreen.kt | Settings + account display |
| ui/screens/AlbumScreen.kt | Album detail with tracks, play all, shuffle |
| ui/screens/ArtistScreen.kt | Artist detail with sections, description |
| ui/screens/PlaylistScreen.kt | Playlist detail with pagination |
| ui/screens/ListenTogetherScreen.kt | Room create/join, user list, suggestions, connection status |
| ui/screens/PodcastScreen.kt | Podcast detail with episode list, Play All/Shuffle, context menus |
| ui/screens/RecognitionScreen.kt | Music recognition UI (Ready/Listening/Processing/Success/Error states) + recognition history |
| ui/screens/ExploreScreen.kt | New releases, moods & genres grid, Charts entry (cached in-memory) |
| ui/screens/BrowseScreen.kt | Generic browse detail for any browseId/params (moods, charts) |
| ui/screens/LocalPlaylistScreen.kt | Local playlist detail (rename/delete/reorder/remove) + AutoPlaylistScreen (Liked/Downloaded/Most Played) |
| ui/screens/StatsScreen.kt | Listening stats by period + History tab (all events, clear) |
| ui/screens/EqualizerScreen.kt | 10-band EQ UI with presets |
| backup/BackupManager.kt | ZIP export/import of preferences + DB + credentials |
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

**Only one login path exists: "Sign in with browser".** Importing cookies from an installed
browser was removed for good in v2.6.0 — Chrome/Edge/Opera 127+ encrypt *every* cookie with
app-bound `v20` keys stored as `app_bound_encrypted_key` and wrapped in SYSTEM-scoped DPAPI,
which user-space code cannot unwrap. Verified 2026-07-24 on a real profile: Chrome 41/41 and
Edge 46/46 YouTube cookies were `v20`, zero `v10`. Do not re-add an import-from-installed
feature without solving SYSTEM DPAPI first.

### How it works
1. **BrowserLoginHelper** launches Edge/Chrome/Brave with a dedicated profile at
   `<app-dir>/data/login-profile`, waits for the user to sign in and close the browser
2. A fresh profile still writes old-style `v10` cookies, which decrypt normally
3. **BrowserCookieExtractor.extractChromiumCookies()** reads that profile's cookie DB:
   Windows DPAPI (master key from Local State) + AES-256-GCM (individual cookies)
4. Modern Chromium prepends a 32-byte binding hash to decrypted values — must be stripped
5. **Domain preference**: `.youtube.com` cookies take priority over `.google.com` (critical for SIDCC, PSIDCC, PSIDTS)
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

## First-Run Onboarding

`ui/screens/OnboardingScreen.kt`, reached through `AppScreen.Onboarding`. Seven steps: welcome,
sign-in, look & sound, downloads & tray, content & startup, integrations, done.

**Sign-in is the only step with no way past it.** Playback needs a live session, so `Next` stays
disabled until `AuthManager.authState.isLoggedIn`. Everything after it only writes preferences that
already have working defaults, so pressing Next without touching anything is a valid path through,
and a "Skip the rest" button jumps to the end.

**The cookie instruction is load-bearing, not decoration.** Declining the browser's cookie prompt
means the sign-in cookies are never written, and the resulting profile is indistinguishable from one
where the user closed the browser without signing in. Both land on the same
`BrowserLoginHelper` error, which is why that message names the cause. Keep the numbered
instructions *above* the button: once the browser launches it takes focus and nobody reads the
Lyrenne window again until they are done.

**Existing installs must not see the wizard.** `onboardingCompleted` defaults to `false` in
`AppPreferences` but to **`true`** inside `loadPreferences()`. That asymmetry is the entire
migration: reaching the read means `preferences.properties` already existed, which is proof the app
has been run before. A genuinely fresh install has no file, skips the block, and gets `false`.
Do not "fix" the inconsistency by making them match.

`App.kt` picks the starting screen from a plain `.value` read, not `collectAsState()`. Collecting
would tear the wizard down mid-flight the moment it sets the flag.

To re-test it: delete `data/preferences.properties` from the copy you run. Settings → System →
"Run First-Time Setup Again" re-enters it without clearing anything.

## The login profile is not a second login, and must not outlive the sign-in

`BrowserLoginHelper` launches a browser against a throwaway profile at `data/login-profile`, reads
the cookies out of its cookie DB, and copies them into `credentials.json`. That file is what the
app authenticates with from then on; the profile is never opened again.

Until 2.10.0 nothing deleted it. It therefore sat next to the app holding a full Chromium profile
with a live Google session and its DPAPI key, and `logout()` did not touch it, so **signing out
left a working session on disk**. A measured install: 87 MB, which was 99% of the whole `data/`
folder.

`BrowserLoginHelper.clearLoginProfile()` now runs in three places, and all three are needed:

| Where | Why |
|---|---|
| After a successful extraction | The cookies are in memory by then; the profile is spent |
| `AuthManager.logout()` | Otherwise signing out is not signing out |
| `Main.runApp()` startup | Sweeps profiles left by pre-2.10.0 versions. Safe there specifically because no login can be in flight yet |

Only delete on `CookieExtractResult.Success`. The handoff path calls `readCookiesFromProfile`
repeatedly while the user is still typing their password, and deleting on a non-Success would wipe
the profile mid-login.

The one cost is that the next sign-in starts from a clean profile, so the user retypes their Google
password instead of the browser remembering them. That is the trade and it was made deliberately.

## Update download: HTTPS and GitHub only

`AutoUpdater.downloadFile` follows redirects by hand (`instanceFollowRedirects = false`). What it
fetches gets extracted over the app directory and executed, and there is no signature to fall back
on, so the transport is the entire trust chain.

`requireTrustedUrl()` gates the initial URL and **every** redirect hop: HTTPS only, host must be
`github.com`, `*.github.com` or `*.githubusercontent.com`. Relative `Location` values are resolved
against the current URL first, so a schemeless hop cannot skip the check. Without this, one hop
answering with `http://` turned the updater into a cleartext delivery channel for code that runs as
the user.

Still missing, deliberately: no checksum or signature. A published hash would only defend against a
swapped asset, not a compromised account, since both come from the same origin. Real fix is code
signing.

## The update script is generated, so paths must be single-quoted

`buildPortableUpdateScript` interpolates real filesystem paths into PowerShell. `$` is legal in a
Windows folder name, so inside `"..."` a path like `C:\Music$Library\Lyrenne` expanded to garbage
and the update copied to the wrong place, reporting it only in a log nobody reads. `$(...)` is also
a legal folder name and would have been executed.

Every path goes through `psQuote()`, which wraps in `'...'` and doubles any embedded `'`.
Single-quoted PowerShell strings expand nothing. Do not switch these back to double quotes to
interpolate something; assign a new variable at the top of the script instead.

## Rendering, caches and memory

- **Coil gets an explicit `ImageLoader`** in `Main.configureImageLoader()`. Off Android, Coil
  enables **no disk cache** unless told to, so every thumbnail was refetched from Google's CDN on
  every launch and `data/cache` measured 0 bytes on real installs. The disk cache is sized from the
  `cacheSize` preference, which until then was stored, saved and shown in Settings while nothing
  read it. The memory cache is a fixed 64 MB rather than a percentage of heap, so changing the heap
  cap cannot silently resize it.
- **`-Xmx512m` is set in `build.gradle.kts`.** Without a ceiling the JVM takes a quarter of physical
  RAM (8 GB on a 32 GB machine), GC never has a reason to run, and a measured install sat at 455 MB.
- **Lazy list keys are deliberately partial.** The Library lists (songs, albums, artists, playlists,
  downloads) are keyed on database primary keys. The queue, local playlists, search suggestions and
  home rows are **not**, and must not be: Compose throws on duplicate keys, and those lists can
  legitimately hold the same item twice. Library is also the only place with search, sort and
  filter, so that is where the entire benefit is.
- **No FPS cap exists.** Nothing configures Skiko. `FrameLimiter` is only wired into the software
  and Linux OpenGL redrawers; `Direct3DRedrawer`, the Windows default, paces off swap-chain vsync.
  If someone reports 60 Hz on a high-refresh display, check which monitor the window is on before
  anything else.

## Library Writes (two-way sync)

**Every local library edit must also push to YouTube.** Until v2.8.0 the desktop app was
read-only: adding a song to a playlist, liking a track, renaming or deleting a playlist all
wrote to SQLDelight and stopped there, so nothing appeared on YouTube. The InnerTube write
endpoints existed the whole time — nothing called them.

`sync/YouTubeWrites.kt` owns this. Call it alongside the local `DatabaseHelper` mutation:

| Local call | Must be followed by |
|---|---|
| `addSongToPlaylist` | `YouTubeWrites.addToPlaylist` |
| `removeSongFromPlaylist` | `YouTubeWrites.removeFromPlaylist` |
| `updateSongLiked` | `YouTubeWrites.likeSong` |
| `renamePlaylist` | `YouTubeWrites.renamePlaylist` |
| `deletePlaylist` | `YouTubeWrites.deletePlaylist` |
| `createLocalPlaylist` | use `YouTubeWrites.createPlaylist` instead (suspend) |

- Writes are fire-and-forget; the local DB stays the source of truth so edits work offline.
  Failures surface via `YouTubeWrites.lastError`, never rolled back — silently undoing a
  user's edit is worse than a stale remote.
- Playlists with an `LP`-prefixed id are local-only (created offline or before v2.8.0) and are
  skipped — they have no YouTube counterpart.
- Removal needs `setVideoId`, not the video id. It isn't stored, so it's resolved by re-reading
  the playlist. One extra call, but no schema migration for an id used only on delete.
- The one place a bare `DatabaseHelper` mutation is correct is `LibrarySync` pruning — that
  reflects remote state inward and must NOT push back out.

## Duration has two sources and they disagree

`SongInfo` carries the same fact twice: `durationMs` (Long, filled from the database) and
`duration` (Int, **seconds**, filled by `toPlayerSongInfo` from InnerTube). Which one is populated
depends entirely on where the song came from. Library playback fills the first; search, home, radio
and explore fill only the second. **Reading either field directly is a bug**; use
`SongInfo.knownDurationMs()`, and prefer the live `PlaybackState.duration` whenever it is non-zero,
because that comes from VLC and is authoritative.

`PlaybackState.duration` is assigned in exactly two places: `playUrl` seeds it from metadata, and
VLC's async `lengthChanged` corrects it. Before 2.10.1 only the second existed, so every track
began carrying **the previous track's duration** until VLC parsed the stream. That window is wider
on long tracks, and three things read it and got it wrong: the progress bar was mis-scaled, Discord
published an end timestamp from the wrong track and never corrected it, and Listen Together
announced the wrong length to the room.

This is why Discord now also re-sends on a duration correction, not only on song change and seek.
That is not a reintroduction of the banned position-tick resend: `lengthChanged` fires about once
per track and the send goes through the existing 700 ms debounce.

## Listen Together: isSyncing is a latch, and leaking it is silent

`isSyncing` gates the player observer (`if (isSyncing || !isInRoom) return@collectLatest`). Leak it
and this client stops sending **any** playback change to the room, forever, with no error and no
log. Only rejoining clears it. Two separate leaks existed before 2.10.1:

1. `syncToTrack` cleared the flag in its `catch` and at the end of its `try`, but the generation
   check mid-body is a plain `return@launch`, not an exception. Two sync messages arriving inside
   its 1 second delay was enough.
2. `applyPlaybackState` cleared it in a `finally`, which looks correct, but the `finally` starts
   with `delay(200)`. **A suspending call in a `finally` throws immediately once the job is
   cancelled**, so the reset below it never ran. `syncToTrack` cancels that job by design, so the
   cancelled path was the common one. It now uses `withContext(NonCancellable)`.

Any future cleanup of this flag must run in a `finally`, and must not suspend outside
`NonCancellable`. Rethrow `CancellationException` before the generic `catch` so cancellation still
propagates.

**Known remaining smell, deliberately left:** `applyPendingSyncIfReady` also sets `isSyncing` and
clears it from a detached `scope.launch`, while being called from inside both functions that
already own the flag. A boolean cannot express nesting. The correct fix is a depth counter, but it
changes sync locking and cannot be validated without two real clients in a room.

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

## Bundled binaries (VLC + ffmpeg)

| Binary | Version | In git? | Notes |
|---|---|---|---|
| VLC | 3.0.23 | yes, `desktop/resources/windows-x64/vlc/` | latest 3.0.x; 4.x not stable. Needs `plugins.dat` — see Startup Performance |
| ffmpeg | BtbN win64 **LGPL** master | **no** — fetched at build time | ~114 MB, over GitHub's 100 MB file limit |

`./gradlew :desktop:fetchFfmpeg` downloads and caches it into
`desktop/resources/windows-x64/ffmpeg/` (gitignored). `createDistributable` and
`prepareAppResources` depend on it, so a normal build just works; the download only happens
when the file is missing.

- Task matching must be lazy (`tasks.matching { ... }.configureEach`) — the Compose plugin
  registers `createDistributable` after the build script body runs
- In `build.gradle.kts`, `java.net.URI` / `java.util.zip.ZipFile` need explicit imports: bare
  `java` resolves to Gradle's `java` extension, not the package
- Chose LGPL over GPL/full purely on size. Verified it still carries everything CarExport needs:
  `libmp3lame`, `loudnorm` (EBU R128), `aformat`, `pan`, and aac/opus/mp3/flac/vorbis decoders
- Bundling ffmpeg takes the release ZIP from ~162 MB to ~206 MB

## Testing: NEVER run the app from the build folder

Use a copy extracted outside the build tree — `S:\Dev\Metrolist PC\Metrolist-App\` is set up
for this. Reason:

1. `AppPaths` writes `data/` (credentials, DB, preferences) next to `Lyrenne.exe`, so running
   `build/compose/binaries/main/app/Lyrenne/Lyrenne.exe` puts a real login in the build tree
2. `packagePortableZip` **purges that `data/`** every build — that is the credential-leak guard
3. The tester is then signed out, because nothing restores that data — 2.9.4 removed the
   `%APPDATA%` migration entirely

Net effect: every release build silently signs the tester out.
This burned most of a night on 2026-07-24, presenting as "playlist sync is broken", "can't
create playlists", and "sync fetches nothing" — all of which were just a dead login.

## Expired YouTube sessions fail SILENTLY

An expired session does not error. The API returns **HTTP 200 with an anonymous response**:
browses come back with zero items, writes return 401. Symptoms look like broken features, not
broken auth.

- Diagnose by checking for a logged-in marker (`accountName`) in a `browse` response, not by
  HTTP status. A `playlist/create` returning 401 while `browse` returns 200 is the signature.
- `AuthManager.initialize()` now validates stored cookies with `YouTube.accountInfo()` and marks
  the state signed out if they're dead. Do not remove this — without it, `credentials.json`
  merely existing was treated as proof of being signed in.
- When every sync category fails at once, it is the shared session, never four separate faults.

## Startup Performance

**CRITICAL: `desktop/resources/windows-x64/vlc/plugins/plugins.dat` must exist.**
Without it libvlc loads and interrogates all 213 plugin DLLs on every launch — measured
**11.6 s** of startup (~54 ms per DLL, Defender scans each one). With the cache: **0.57 s**.
Total app startup went 13.5 s → 2.4 s.

- The cache stores **relative** plugin paths, so it survives the app being moved (portable-safe)
- Regenerate after ever changing the bundled VLC:
  ```
  cd desktop/resources/windows-x64/vlc
  ../../../tools/vlc-cache-gen.exe "<ABSOLUTE path to that plugins dir>"
  ```
- **The path argument MUST be absolute.** With a relative path vlc-cache-gen exits 0 but
  writes a useless 24-byte empty cache
- `desktop/tools/vlc-cache-gen.exe` is from the official VLC 3.0.23 win64 build and matches
  the bundled `libvlccore.dll` version. It lives outside `resources/` so it is never shipped
- Startup order is already correct: window paints at ~1.6 s, VLC/auth/queue init happen
  off the main thread afterwards. Don't move that work back into `main()`

## The 2.9.3 to 2.9.7 rename (read before touching names or paths)

The project was Metrolist Desktop until 2.9.3. Upstream MetrolistGroup asked for disaffiliation
and is building its own desktop client, so this is now an independent project called **Lyrenne**.

**2.9.4 was a deliberate clean break.** Installs older than it do not upgrade cleanly, and that was
the chosen trade:

- `metrolist.db` became `lyrenne.db`. Users carry their library over by renaming the file; the
  release notes say so.
- The `%APPDATA%/Metrolist` migration was deleted outright.
- `AutoUpdater` stopped recognising `Metrolist.exe`, and `packagePortableZip` stopped shipping the
  compatibility launcher. The archive holds one launcher.
- Package namespace `com.metrolist.music.desktop` became **`com.lyrenne.desktop`**, protobuf with
  it. `Metrolist.sq` became `Lyrenne.sq`, which is why the generated accessor is `lyrenneQueries`.

**What still says metrolist, and must:** the `com.metrolist.*` imports for the vendored upstream
modules (innertube, lrclib, kugou, lastfm, shazamkit, betterlyrics). Those are upstream's GPL
sources pulled in via `kotlin.srcDir()`; renaming them would conflict with every future sync. The
README credit to the Metrolist Group also stays, because GPL-3.0 requires preserving it.

`app/` is upstream's Android source, kept only as a porting reference and never built. Anything
inside it legitimately still says Metrolist.

## Theming

Two hand-tuned schemes in `ui/theme/Theme.kt`, neither generated from a seed colour.

- **Dark**: the project's own bronze on near-black, matching the site and the app mark.
- **Light**: the flag of Cyprus, using the official values exactly — copper `#D57800`
  (Pantone 1385), olive `#4E5B31` (Pantone 574), white.

Two rules that keep them legible, both learned the hard way:

1. **A brand colour is a fill, not a text colour.** Copper on white is 3.1:1 and bronze on
   near-black is 4.0:1, both under the 4.5:1 AA wants for body text. Rather than distort the brand
   values, they stay exact and the *text on them* changes. Never set a brand colour as a foreground.
2. **Set every Material role explicitly.** Anything left unset falls back to Material's
   purple-tinted baseline, which both clashes and puts text on surfaces nobody has contrast-checked.
   That includes the surface container ramp, the inverse roles and the error roles.

Every text pairing in both schemes measures at or above 4.5:1. When changing a colour, re-check
rather than eyeballing it. Note also that in a **dark** scheme `primaryContainer` is a *dark* tone
carrying light text: setting it to the mid-tone brand colour made cards render as flat brown slabs.

## Library sync: empty is not the same as failed

`YouTube.library()` throws `IllegalStateException("No content found for browseId=...")` when a
browse response carries neither a `gridRenderer` nor a `musicShelfRenderer` — which is exactly what
an **empty category** looks like. Someone with no saved albums was therefore told their YouTube
session had expired.

`LibraryFetch` in `sync/LibrarySync.kt` separates the three outcomes, and `Empty` is deliberately
not `Items(emptyList())`:

- `Empty` clears the error, **and skips the prune**. An outage or a response-format change produces
  the same shape, and pruning on it would delete that whole category from the local library.
  Finding nothing is not the same as there being nothing.
- The cost is that removing your last album on YouTube is not mirrored locally until you have one
  again. Far cheaper than deleting a library.

Matched on the exception message rather than patching the vendored InnerTube module. If upstream
changes that string the symptom is the old spurious error returning, not data loss.

## Icons: two artworks, picked by size

- `icon.png` — the full mark with its gold ring. Large sizes, the site, the 256px `.ico` entry.
- `icon-small.png` — the same lyre without the ring. The tray, the window icon at 48px and below,
  and the 16/32/48 `.ico` entries.

The ring is most of the pixels at 16px, so the full mark reads as a gold box rather than a lyre.
The small variant keeps the dark tile: dropping that too would leave a white glyph on transparency,
which vanishes on a light-theme taskbar.

`patchPortableIcon` is **dead code**. It looks for Resource Hacker at a path that does not exist, so
it warns and skips every build. Its comment claiming Compose only applies `iconFile` to MSI is
outdated — jpackage embeds the `.ico` into the app-image exe fine.

## Discord Rich Presence

The name Discord shows above the activity is the **application's** name, not anything this code
sends. Until 2.9.7 the app used application `1411019391843172514`, which came across with the port
and belongs to upstream: every user's Discord announced them as running Metrolist. It now uses
Lyrenne's own application, and the small badge references an uploaded art asset by key rather than
a URL, because Discord does not reliably render external images in activity assets.

Neither the name nor the art assets can be changed from code. Both are Developer Portal actions.

### Pausing is not the same as stopping

The collector used to branch on `song != null && isPlaying`, so pausing fell through to the same
`clearPresence()` as having no track at all. The presence vanished, and to everyone else it looked
like Lyrenne had been closed.

A pause now publishes the same track **with the timestamps omitted**. That omission is the point:
Discord animates from timestamps, so leaving them would show a progress bar advancing through a
track that is not moving. Without them the entry freezes, and `small_text` reads Paused so it is
legible rather than merely stalled. Resuming re-sends with a fresh anchor, which is what brings the
progress bar back, so `lastWasPlaying` has to be part of the change check. `clearPresence()` is now
reached only when there is genuinely no track.

**The paused send is debounced 500 ms and cancelled if playback resumes, and that is not optional.**
VLC reports `stopped` between tracks with the song still loaded, so an immediate send would publish
a paused entry and then a playing one on *every skip*: two pipe writes per track change. Rapid
skipping is exactly what tripped the rate limit that rule 6 in `AGENTS.md` exists for.

## Development Notes
- VLC must be installed on the system for playback to work (bundled VLC also supported)
- Stream URLs fetched using InnerTube clients: ANDROID_VR_NO_AUTH → IOS → WEB_REMIX fallback
- This working copy IS a git clone with a working `origin`. Commit and push directly; older notes describing a robocopy-to-temp-dir push workflow are obsolete
- **Nothing is written outside the app folder.** All state lives in `<app-dir>/data/`:
  `lyrenne.db`, `credentials.json`, `preferences.properties`, `cache/`, plus the Listen Together
  session. 2.9.4 removed the last `%APPDATA%` paths and the migration that read them
- Credentials stored at `<app-dir>/data/credentials.json`
- Delete credentials.json to force re-login
- All debug println converted to Timber logging (SLF4J-backed shim)
- DatabaseHelper.database is private — use DatabaseHelper methods, not direct DB access
- SQLDelight accessor is `lyrenneQueries`, named after the `Lyrenne.sq` file (rename the file and the accessor renames with it)

## Version Management
- **Current version**: v2.10.2
- **Version must be updated in TWO places** when releasing:
  1. `desktop/build.gradle.kts` → `lyrenneVersion = "X.Y.Z"`
  2. `desktop/.../update/AutoUpdater.kt` → `CURRENT_VERSION = "X.Y.Z"`
- Both MUST match — `lyrenneVersion` controls the ZIP filename, `CURRENT_VERSION` is shown in Settings and used for update comparison

## Release Process (Step by Step)
1. **Bump version** in both places (build.gradle.kts `lyrenneVersion` + AutoUpdater.kt `CURRENT_VERSION`)
2. **Build the portable distributable**:
   ```
   ./gradlew :desktop:createDistributable
   ```
   Output: `desktop/build/compose/binaries/main/app/Lyrenne/` (folder with exe + runtime + resources)
3. **Create portable ZIP** — use the guarded task, never a manual 7z call:
   ```
   ./gradlew :desktop:packagePortableZip
   ```
   Purges runtime data, zips with 7z, and refuses to produce an archive containing
   credentials/DB/prefs or backslash entries. Output: `desktop/build/compose/binaries/main/app/Lyrenne-X.Y.Z-portable.zip`
4. **Push code**: `git push origin main`
5. **Create GitHub release** with portable ZIP only:
   ```
   gh release create vX.Y.Z Lyrenne-X.Y.Z-portable.zip --title "Lyrenne vX.Y.Z" --notes "..."
   ```
6. **Package managers: nothing to do.** The Scoop bucket at Doctordefector/scoop-lyrenne runs
   Excavator on a daily cron and bumps its own version, URL and hash. There is deliberately no
   winget listing — winget would delete the user's library on upgrade, because this app stores
   data next to the exe and winget has no `persist` equivalent. See `packaging/README.md` before
   reconsidering that.

## Distribution Strategy
- **PORTABLE ONLY** — no EXE installers, no MSI packages. Just the portable ZIP.
- Each release has exactly ONE artifact: `Lyrenne-X.Y.Z-portable.zip`
- Users extract and run — no installation needed
- Downloads, updates, preferences, and database all live next to the app (not in %APPDATA%/Roaming)
- Do NOT build `packageExe` or `packageMsi` — only `createDistributable`

## File Storage Paths
All data is fully portable — stored next to the executable via centralized `AppPaths.kt`:
- **Preferences**: `<app-dir>/data/preferences.properties`
- **Database**: `<app-dir>/data/lyrenne.db` (SQLDelight)
- **Credentials**: `<app-dir>/data/credentials.json` (plaintext, intentional)
- **Cache**: `<app-dir>/data/cache/`
- **Downloads**: `<app-dir>/Downloads/` (configurable via Settings folder picker)
- **Updates staging**: `<app-dir>/updates/` (with fallbacks to user.dir then temp)
- **Migration**: On first run, `AppPaths` auto-migrates files from old `%APPDATA%/Lyrenne` to `data/` if the data dir is empty
- **CRITICAL**: NOTHING goes to %APPDATA% or %LOCALAPPDATA% anymore. Everything lives next to the app for full portability.

## GitHub & Release
- **PUBLIC repo**: https://github.com/Doctordefector/Lyrenne — anything pushed or
  attached to a release is world-readable immediately. (This file previously claimed "private";
  it is not, and that error contributed to a credential leak in v2.6.0.)

### CRITICAL: never zip the folder you smoke-tested from
`AppPaths` writes `data/` (credentials.json, lyrenne.db, preferences.properties) next to
`Lyrenne.exe`. Running the app from `build/compose/binaries/main/app/Lyrenne/` therefore
plants **real login cookies** inside the exact folder that gets zipped. This shipped once in
v2.6.0 and was public for ~12 minutes.

**Always build the release archive with `./gradlew :desktop:packagePortableZip`.** It purges
`data/`, `Downloads/`, `updates/`, zips with 7z, then scans the archive and *fails the build*
(deleting the zip) if any credential/DB/prefs entry or backslash entry is present. Do not
hand-roll the 7z command.
- Local copy has `nul` file that breaks git — use temp dir copy for pushing
- Push workflow: robocopy to temp dir (excluding .gradle/.kotlin/build/.claude/nul), git init, commit, force push
- `gh` CLI at `C:\Program Files\GitHub CLI\gh.exe` (not in bash PATH, use full path), authenticated as Doctordefector
- **ALWAYS pass `--repo Doctordefector/Lyrenne` to every `gh release` command.** This repo has two
  remotes and bare `gh release list` resolves to `upstream` (MetrolistGroup/Metrolist), silently showing
  v13.x Android releases instead of our v2.x desktop ones
- Robocopy for push: Must use PowerShell `robocopy` (bash `robocopy` has path issues with /E flag)
- Upload ONLY the portable ZIP to each GitHub release

## Auto-Updater System

### How it works (end to end)
1. **Check**: `AutoUpdater.checkForUpdate()` hits GitHub API (`/repos/.../releases/latest`), compares `CURRENT_VERSION` against the latest tag using semver
2. **Detect portable**: Looks for `Lyrenne-*-portable.zip` in release assets — if found, uses portable update path
3. **Download**: Streams the ZIP to `<app-dir>/updates/` with progress callbacks, shown in Settings UI
4. **Extract**: `extractZip()` extracts to a timestamped staging dir (`updates/staging-<timestamp>/`), cleans up old staging dirs first
5. **Verify**: Checks extracted contents for `Lyrenne.exe` — rejects invalid packages
6. **Install**: User clicks "Install & Restart" → launches a PowerShell script that:
   - Waits for the current process to exit (polls by PID)
   - Uses `robocopy /E /IS /IT` to copy staging → app directory (overwrites everything)
   - Relaunches `Lyrenne.exe`
   - Cleans up staging dir and ZIP
7. **App restarts** on the new version

### Key file
- `desktop/.../update/AutoUpdater.kt` — entire update lifecycle (check, download, extract, install script generation)

### Critical gotchas
- **ZIP must use forward slashes**: Java's `ZipEntry.isDirectory()` only checks for trailing `/`. PowerShell's `Compress-Archive` uses `\` which breaks extraction. ALWAYS use 7z to create release ZIPs.
- **Backslash normalization**: The extractor normalizes `\` → `/` as a safety net, but don't rely on it — use 7z
- **Timestamped staging**: Staging dirs use `staging-<millis>` to avoid conflicts from previous failed extractions where `deleteRecursively()` silently failed on locked files
- **`getUpdateDirectory()`**: Returns `<app-dir>/updates/`. Resolves app dir from JAR `codeSource.location`, falls back to `user.dir`, then system temp. NEVER `%APPDATA%/Roaming`
- **`findAppDirectory()`**: Walks up from JAR location to find the Lyrenne root. For Compose Desktop distributable: `Lyrenne/app/Lyrenne.jar` → walks up 2 levels → `Lyrenne/`
- **Chicken-and-egg**: If the updater itself has a bug, users must manually download the fixed version. The running binary's updater code is what executes, not the new version's

### PowerShell update script (`lyrenne-update.ps1`)
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
