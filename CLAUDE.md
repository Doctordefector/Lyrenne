# Lyrenne Port - Project Guide

## Overview
Porting Lyrenne (Android YouTube Music client) to desktop using Compose Desktop (JVM).
- **Upstream**: https://github.com/MetrolistGroup/Metrolist (innertube synced to upstream/main @ 9285e7a96, after v13.6.3, on 2026-08-21; other shared modules unchanged since v13.6.0; upstream is in maintenance mode)
- **Don't sync upstream past `9285e7a96` without a plan** (checked 2026-10-26): upstream moved most of `innertube` into an external library, InnerTubeX, and deleted `shazamkit`. The documented copy-the-module sync would break stream playback, downloads and song recognition here; adopting InnerTubeX is a port of its own
  - Sync method: `git checkout upstream/main -- innertube`, delete files upstream removed, then re-apply the thumbnail empty-defaults patch (see InnerTube modifications below). The old accountIndex patch is gone: upstream replaced it with `authUser` (AuthManager sets `YouTube.authUser`)
  - Stream URLs are deobfuscated through NewPipeUtils (signatureCipher + n throttle param) in DesktopPlayer and DownloadManager, using the MetrolistExtractor fork at commit f0a00f5. That commit builds on openjdk17, which JVM 21 accepts. Do NOT bump to the fork's 3cd3341: it builds on openjdk25 and Gradle refuses it for our JVM 21 toolchain
  - kizzy module: upstream deleted it; our copy remains in repo but is NO LONGER compiled into desktop (was never referenced — desktop Discord RPC is the named-pipe DiscordRPC.kt)
- **Desktop module**: `desktop/` folder
- **Shared modules**: `innertube/`, `lrclib/`, `betterlyrics/`, `kugou/`, `kizzy/`, `lastfm/`, `shazamkit/` (sources included directly via `kotlin.srcDir()`, not as project dependencies)
- **Codebase**: ~27,000 lines of Kotlin across ~70 files in `desktop/` + 1 protobuf file
- **Platforms**: Windows (portable ZIP) and Linux (.deb/.rpm via nfpm). See Linux port

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
- Authentication via a temp-profile browser sign-in (Windows: Edge, Chrome, Brave, Firefox; Linux: Firefox incl. snap, LibreWolf, Chrome/Chromium/Edge/Brave/Vivaldi) plus a paste-cookie fallback
- One login path: "Sign in with browser" (temp profile). Import-from-installed was removed in 2.5.1, see Authentication System
- Personalized home feed with continuations (up to 5 pages)
- YouTube Music library sync (songs, albums, artists, playlists)
- Search with filters (songs, videos, albums, artists, playlists) + suggestions
- Audio playback via VLC (streaming + local files)
- Download management with progress tracking, resume after interruption, auto-retry, and per-playlist folders
- Queue management (add, remove, shuffle, repeat, play next)
- Media key shortcuts (space, Ctrl+P, Ctrl+Right/Left, Ctrl+S, Ctrl+R, hardware media keys)
- Text field focus detection — keyboard shortcuts suppressed while typing
- Settings UI with persistence (properties file)
- Material3 theme with system dark/light detection (Windows registry, macOS defaults, GNOME color-scheme/gtk-theme, KDE kdeglobals), polled off the UI thread every 30 s
- Detail screens: Album, Artist, Playlist (with full navigation stack)
- Seekable progress bar in MiniPlayer
- Lyrics display (synced + plain) with auto-scroll, word-by-word highlighting when the provider sends word timing, and manual search. See Lyrics parsing
- Audio quality selection (128/192/256/320 kbps)
- Queue persistence across app restarts (SQLDelight)
- Discord Rich Presence via named pipe IPC (Windows `\\.\pipe\discord-ipc-N`, Unix `/tmp/discord-ipc-N`)
- Last.fm scrobbling (now-playing + scrobble at 50%/240s threshold)
- Desktop notifications (AWT SystemTray pop-ups on song change)
- Auto-updater (GitHub releases: Doctordefector/Lyrenne)
- Listen Together (WebSocket, protobuf messages, room create/join, bidirectional playback sync, suggestions, session persistence with 10-min grace, reconnect with exponential backoff)
- Account info display (name, handle, avatar from YouTube)
- Several accounts signed in at once, switched with one click from Settings (issue #11). See Account switching
- Floating mini player: round always-on-top disc while the main window is minimized or in the tray (Settings > System, off by default)
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
- Start Radio (plays the song at once, then fills the queue with its RDAMVM radio) + Autoplay (on by default: similar songs queued when the queue runs out). See Autoplay
- Library song lists play on from the clicked song, like albums and playlists; Recently Played is what actually played this session
- Downloaded songs play from disk wherever they are queued, not only from the Downloads tab, which also plays its list on from the clicked song now. See Downloads play from disk
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
- Lyrics timing per song, editing or writing lyrics by hand (LRC or plain, or a loaded .lrc), and romanization under lines in other scripts. See Lyrics: timing, editing, romanization
- Recognition history (RecognitionHistory table, list on Ready state, play from history)
- Privacy: pause listen history + pause search history settings
- Content region/language settings (gl/hl via YouTube.locale, curated lists + system default)
- Proxy support (HTTP/SOCKS + auth via YouTube.proxy/proxyAuth, settings UI, applyNetworkPreferences() in Main.kt)
- Backup & restore (ZIP of preferences.properties + lyrenne.db + credentials.json, BackupManager, restore needs restart)
- Library grid/list view toggle (albums + artists tabs, LibraryViewMode pref)
- Play All / Shuffle All buttons (library songs tab, local/auto playlists)
- Search within one playlist (YouTube, local and auto playlists; issue #14). A YouTube playlist loads its remaining pages once a search starts, so it searches all of it. Move Up/Down is hidden while filtering
- Smart Shuffle on every playlist (issue #15). See Smart Shuffle
- Artist sections with more than YouTube puts on the artist page (Albums, Singles & EPs, Live, Playlists) have a "See all" opening `ArtistItemsScreen`, a paged grid of the whole section. Top songs' and Videos' See all is a playlist and opens PlaylistScreen
- Add to Playlist never blocks a YouTube playlist on the local "already added" record (issue #21): that record can hold songs that never reached YouTube (added signed out, failed push), and the playlist page shows YouTube's copy. Signed out, YouTube playlists are disabled in the picker
- Mouse wheel steps any slider (issue #13): volume 5%, seek 5 s, EQ 1 dB, lyrics size 2 pt. `ui/components/WheelStep.kt`. The tray icon itself cannot: AWT's `TrayIcon` delivers no wheel events
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
| auth/BrowserCookieExtractor.kt | Chromium (Windows AES-256-GCM + DPAPI via JNA; Linux AES-128-CBC basic-store key) and Firefox (plaintext) cookie readers, pasted-header parser |
| auth/BrowserLoginHelper.kt | OS-aware browser discovery (`LoginBrowser`), launch with temp profile, cookie polling after browser close |

#### Data & Playback
| File | Purpose |
|------|---------|
| db/DatabaseHelper.kt | SQLDelight wrapper, queue persistence, song/album/artist/playlist CRUD |
| playback/DesktopPlayer.kt | VLC playback engine, queue management, shuffle/repeat, stream URL resolution |
| download/DownloadManager.kt | Download queue, HTTP streaming with progress, m4a storage, resume, retry, per-playlist folders |
| download/SleepGuard.kt | Holds off system sleep while the download queue drains (kernel32 SetThreadExecutionState) |
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
| lyrics/LyricsManager.kt | Provider chain, manual search across all providers, caching by songId |
| lyrics/LyricsParser.kt | Every lyric format the providers send, into timed lines with optional word timing |
| lyrics/LyricsOverrides.kt | Lyrics picked or edited by hand (`data/lyrics-overrides.json`) and per-song timing (`LyricsOffsets`, `data/lyrics-offsets.json`), both on `PerSongFile` |
| lyrics/Romanizer.kt | Romanized reading of non-Latin lines: kuromoji for Japanese, `KoreanRomanizer` for Hangul, ICU for the rest |

#### UI Screens
| File | Purpose |
|------|---------|
| ui/screens/HomeScreen.kt | Home feed with continuations |
| ui/screens/SearchScreen.kt | Search with filters + suggestions |
| ui/screens/LibraryScreen.kt | Library tabs (Songs/Albums/Artists/Playlists/Downloads) |
| ui/screens/QueueScreen.kt | Queue side panel (shares the right slot with lyrics) with drag-to-reorder |
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
| ui/components/UpdateBadge.kt | Floating "update waiting" pill below the title row, navigates to Settings |
| ui/components/LyricsPanel.kt | Synced/plain lyrics sidebar with auto-scroll, word highlight, search dialog |
| ui/components/AccountSwitcher.kt | Saved accounts under the Settings account card: switch, remove, add |
| ui/components/FloatingPlayer.kt | The floating mini player disc; its window is created in Main.kt |
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
| Thumbnails.kt, ThumbnailRenderer.kt | Thumbnail fields carry empty defaults so a tile with no artwork (e.g. an empty playlist in the library grid) decodes instead of aborting the whole browse response with a MissingFieldException (issue #2). Re-apply after every upstream sync |
| InnerTube.browse, YouTube.playlist / playlistContinuation | `anonymous` flag sends a browse signed out even when `useLoginForBrowse` is on. The owner's view of a playlist with voting on stops at 100 songs with no next-page token, the signed-out view pages normally (issue #20), so `loadPlaylistFirstPage` (PlaylistScreen.kt) pages that case signed out. Re-apply after every upstream sync |

### Build
- `desktop/build.gradle.kts` — Compose Desktop config, JVM 21, SQLDelight, protobuf
- Root `settings.gradle.kts` must include `desktop` module
- Root `build.gradle.kts` must declare Compose Desktop + Kotlin Compose plugins
- **Shared module sources**: innertube, lrclib, kizzy, lastfm, shazamkit included via `kotlin.srcDir()` (not project dependencies — Android libraries can't be consumed by JVM)
- **Timber shim**: SLF4J-backed drop-in for Android's Timber
- **Protobuf**: Plugin `com.google.protobuf` v0.9.4, proto files at `desktop/src/main/proto/`, needs `DuplicatesStrategy.EXCLUDE` on processResources
- **Dependencies**: vlcj 4.8.3, Coil 3.3.0, Ktor 3.4.1, SQLDelight 2.0.2, OkHttp 4.12.0, Protobuf-java 3.25.5, brotli, MetrolistExtractor f0a00f5, org.json, SLF4J simple
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
1. **BrowserLoginHelper** launches the first browser `findLoginBrowser()` finds with a dedicated profile at
   `<data>/login-profile` (snap browsers: `~/snap/<name>/common/lyrenne-login-profile`), waits for the user to sign in and close the browser
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
9. Firefox: `cookies.sqlite` is plaintext on every OS. Linux Chromium is launched with
   `--password-store=basic` so cookies are `v10` under the fixed "peanuts" key; a `v11` cookie means
   the flag was dropped and the keyring was used, which is reported rather than attempted
10. The handoff poll's lock check uses `NOFOLLOW_LINKS`: on Linux `SingletonLock`/`lock` are
   dangling symlinks and `File.exists()` always said the browser had closed

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

`BrowserLoginHelper.pruneLoginProfile()` deletes everything in the profile except the browser's
saved-password store (Chromium `Login Data` + `Local State`, Firefox `logins.json` + `key4.db`). It
runs in three places, and all three are needed:

| Where | Why |
|---|---|
| After a successful extraction | The cookies are in memory by then; the profile is spent |
| `AuthManager.logout()` | Otherwise signing out is not signing out |
| Startup (windowed and headless) | Sweeps profiles left by older versions. Skips a profile a browser still has open (a headless restart mid sign-in) |

Only prune on `CookieExtractResult.Success`. The handoff path calls `readCookiesFromProfile`
repeatedly while the user is still typing their password, and pruning on a non-Success would wipe
the profile mid-login.

The saved password stays even through a sign-out, because whether it exists is the user's own
answer to the browser's "save password?" prompt: yes means the next sign-in is a tap, no means there
is nothing to keep. It cannot authenticate anything on its own. Until 2.20.0 sign-out deleted the
whole profile and overrode that answer. The prune also removes symlinks itself: Firefox's `lock`
dangles once the browser closes, and `deleteRecursively()` leaves dangling links behind.

## Update download: HTTPS and GitHub only

`AutoUpdater.downloadFile` follows redirects by hand (`instanceFollowRedirects = false`). What it
fetches gets extracted over the app directory and executed, and there is no signature to fall back
on, so the transport is the entire trust chain.

`requireTrustedUrl()` gates the initial URL and **every** redirect hop: HTTPS only, host must be
`github.com`, `*.github.com` or `*.githubusercontent.com`. Relative `Location` values are resolved
against the current URL first, so a schemeless hop cannot skip the check. Without this, one hop
answering with `http://` turned the updater into a cleartext delivery channel for code that runs as
the user.

A published hash would only defend against a swapped asset, not a compromised account, since both
come from the same origin. So releases are **signed**: Ed25519 over the ZIP's SHA-256 (pure Ed25519
would need the 200 MB ZIP in a 512 MB heap). The private key
(`./gradlew :desktop:generateUpdateSigningKey`, default `~/.lyrenne/update-signing.key`, also the
`LYRENNE_SIGNING_KEY` Actions secret, see Release Process), the public key
is `AutoUpdater.UPDATE_PUBLIC_KEY` (set since the Linux port). **Every release from then on must ship a
`.sig`**, or builds carrying that key refuse the update. Releasing from another PC: copy
`~/.lyrenne/update-signing.key` there (or point `LYRENNE_SIGNING_KEY` / `-PupdateSigningKey` at it).
Never generate a second pair: it would not match the key already shipped. `UpdateSignatureTest` checks
the local private key against the embedded public key.

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
- **G1 and the JIT are trimmed too** (same `jvmArgs`, so Windows and the Linux packages both get
  it): `-Xms32m`, heap free ratios 10/30, `G1PeriodicGCInterval=30000`, 2 parallel / 1 concurrent
  GC threads, `CICompilerCount=3`. Idle on Home: private 513 -> 379 MB, WS 463 -> 360 MB, max GC
  pause 13 ms. SerialGC measured 30 MB lower but froze the UI ~90 ms per full GC; do not use it.
  What remains is ~200 MB of native memory outside the JVM (Skia render surfaces, AWT).
  Measure with `JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary` and
  `jcmd <child Lyrenne.exe pid> VM.native_memory summary` (the launcher spawns a child JVM).
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

`PlaybackState.duration` is assigned in exactly two places: `playMedia` seeds it from metadata, and
VLC's async `lengthChanged` corrects it. Before 2.10.1 only the second existed, so every track
began carrying **the previous track's duration** until VLC parsed the stream. That window is wider
on long tracks, and three things read it and got it wrong: the progress bar was mis-scaled, Discord
published an end timestamp from the wrong track and never corrected it, and Listen Together
announced the wrong length to the room.

This is why Discord now also re-sends on a duration correction, not only on song change and seek.
That is not a reintroduction of the banned position-tick resend: `lengthChanged` fires about once
per track and the send goes through the existing 700 ms debounce.

## Listen Together: the sync latch, and leaking it is silent

`isSyncing` gates the player observer (`if (isSyncing || !isInRoom || prev == null)`). Leak it
and this client stops sending **any** playback change to the room, forever, with no error and no
log. Only rejoining clears it. Two separate leaks existed before 2.10.1:

1. `syncToTrack` cleared the flag in its `catch` and at the end of its `try`, but the generation
   check mid-body is a plain `return@launch`, not an exception. Two sync messages arriving inside
   its 1 second delay was enough.
2. `applyPlaybackState` cleared it in a `finally`, which looks correct, but the `finally` starts
   with `delay(200)`. **A suspending call in a `finally` throws immediately once the job is
   cancelled**, so the reset below it never ran. `syncToTrack` cancels that job by design, so the
   cancelled path was the common one. It now uses `withContext(NonCancellable)`.

**It is a depth counter now, not a boolean.** `isSyncing` reads `syncDepth > 0`; every
`beginSync()` is paired with an `endSync()` (or `endSyncSoon()`, 200 ms later) in a `finally`.
Syncs overlap: `handlePlaybackSync` used to clear the boolean 200 ms after *any* message, including
a heartbeat PLAY arriving while `applyPlaybackState` was still resolving a stream, which unmuted
the observer mid-load. An end must never suspend outside `NonCancellable`, and rethrow
`CancellationException` before the generic `catch` so cancellation still propagates. Queue edits
(`QUEUE_EDITS`) take no latch: they move nothing the observer reports, and the host applies them.

### Guests must not echo the room back

Both roles send their own changes, so anything a sync does that the observer reads as local goes
straight back to the room. Through 2.14.0 a guest re-sent **every track the host played**: the room
then reset to paused at 0:00 and everyone rebuffered, once per guest per track. Three causes, all
needed:

- `markSynced()` records the room's track and play state in `lastSyncedTrackId` /
  `lastSyncedIsPlaying` whenever a sync applies them. Without it the guest's track differed from
  the last one *it* had sent, which is the observer's test for a track change.
- `previousState` advances even while syncing. Skipping it left the comparison at the state from
  before the sync, so the sync's own pause, play or seek looked like the user's.
- PLAY is only sent when `lastSyncedIsPlaying != true`, PAUSE only when it is `true`, so VLC
  reporting a sync's play after the latch has closed is not news to the room.

The trade: a play, pause or seek the user makes inside the 200 ms after a sync is not sent. That
is a far smaller loss than the echo.

### Smaller rules

- **The host applies queue edits too.** An approved suggestion reaches the host's own queue only
  as a QUEUE_ADD the server sends to the whole room, so dropping every PlaybackSync on the host
  meant approving a suggestion did nothing. Matches upstream. Desktop still has no UI that
  *sends* a suggestion.
- **A room track that will not load is reported buffered anyway**, the guest stays paused, and
  `unavailableTrackId` stops the track-mismatch branch retrying it on every 5 s heartbeat.
  `playQueue` returns whether anything started for this. Sync with Host retries.
- **SYNC_QUEUE uses `DesktopPlayer.replaceQueue`**, which swaps the queue under the playing song.
  `playQueue` restarted it from 0:00.
- **Each WebSocket acts only while it is current** (`socketGeneration`). A socket closed by
  `forceReconnect` finished its handshake after the new one opened and tore the new one down.
- **`initialize()` runs once.** The screen calls it on every visit; it returns early after the
  first, or role collectors stacked up per visit.
- The persisted session stores the username, or rejoining after `session_not_found` never worked
  across a restart.

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
| Ctrl+Shift+F | Open or close Stage | No |
| F11 | Full screen (while Stage is open) | Yes |
| Left / Right, Space | Step through Rewind cards (while Rewind is open) | No |
| Escape | Close overlay / go back | Yes |
| Media keys | Play/Pause/Next/Prev/Stop | Yes (always work) |

Text field suppression uses `Modifier.suppressMediaKeys()` on all OutlinedTextField instances + `MediaKeyHandler.textInputActive` check in both AWT KeyEventDispatcher and Compose `onPreviewKeyEvent`.

The shortcuts live in `AppShortcuts` (App.kt) and run from the **window's** `onPreviewKeyEvent` / `onKeyEvent` in Main.kt. They used to sit on App's root Row, where they only saw keys while some node inside had focus: after a mouse click on a list row or empty space, Esc and Ctrl+L did nothing.

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

## Window sizing: dp are not pixels, and Windows scaling shrinks the desktop

`rememberWindowState` takes dp, which map to Java user-space units, and Windows display scaling
shrinks the usable desktop measured in those units. A 1080p screen offers 1920x1032 dp at 100% but
only 1536x826 at 125% and **1280x688 at 150%**. The old fixed `1200.dp x 800.dp` therefore opened a
window taller than the screen on any 1080p machine above 100% scaling, cutting off the bottom of
the app, which is where the MiniPlayer is. 125% is Windows' recommended setting on most 1080p
laptops, so this was the common case. 1366x768 was broken even at 100%.

`defaultWindowSize()` clamps to 92% of `maximumWindowBounds` (which already excludes the taskbar),
capped at the old 1200x800 so large screens are unchanged. There is deliberately **no lower bound**:
on a small display the screen is the constraint, and a minimum larger than the desktop recreates
the exact off-screen bug. The window is also centred, since a correctly sized window placed
half-off-screen is no better.

A separate `window.minimumSize` of 720x520 stops a user dragging into the broken zone: MiniPlayer's
controls are a plain `Row` of ten fixed-size buttons plus a 100 dp volume slider, roughly a 552 dp
floor, and a `Row` clips rather than wraps. That minimum is itself clamped against the opening size
for the same reason as above.

## Search returns music videos as SongItem

There is no `VideoItem` anywhere. `SongItem` is the only track-shaped result, so a music video
arrives parsed as one, with `album == null` and `musicVideoType` set. `SongItem.isVideoSong` is the
predicate, already provided by innertube, and `SearchPage` populates `musicVideoType` for search
results specifically, so it is real data rather than a theoretical field.

Before 2.10.5 the subtitle interpolated straight into a string with `?: ""`, which rendered a
dangling `"Artist • "` for anything missing its trailing half. Videos have no album, so that was
every video, and a video looked identical to the audio track of the same song. The Videos filter
therefore appeared to do nothing. It was always sending the correct parameter, byte-for-byte
identical to `FILTER_VIDEO`.

**The Videos filter cannot surface general YouTube content.** `YouTube.search()` queries
`WEB_REMIX`, which is `music.youtube.com`, where "video" means *music video*. Non-music uploads are
not in that catalogue at any filter setting. Worth knowing if someone reports it again: playback
itself is not music-specific (`getStreamUrl` uses `ANDROID_VR_NO_AUTH` and takes any audio adaptive
format), and NewPipeExtractor is already a compiled dependency, so only *discovery* is missing.
Deemed a mobile-shaped want and deliberately not built.

## Theming

Two hand-tuned schemes in `ui/theme/Theme.kt`, neither generated from a seed colour.

- **Dark**: the project's own bronze on near-black, matching the site and the app mark.
- **Light**: the flag of Cyprus — copper `#D57800` (Pantone 1385), olive `#4E5B31` (Pantone 574),
  white. `primary` is a copper *ink*, `#9A4A00` (5.6:1 on the page), not the flag copper: `primary`
  is a text colour in ~50 places (every TextButton, headers, links), and flag copper is 2.9:1 there.
- Values Material has no role for (art-wash strength, rail colour) live in `LyrenneExtras`, per
  scheme. The wash is 0.26 in dark but 0.10 in light: 0.32 over the light page turned it salmon and
  pulled primary text under 4.5:1.

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

## UI kit: one component per role

`ui/components/` holds the shared pieces every screen uses. Use them rather than hand-rolling a row,
tile or header; five hand-written copies of the song menu had drifted apart before this existed.

| Piece | Role |
|---|---|
| `TrackRow` | Every song row (Library, Search, Album, playlists, Downloads, Queue, Artist, Podcast). 56 dp, album column from 900 dp, song menu built in |
| `SongMenu` / `MenuAnchor` / `Modifier.contextMenu` | The one song menu, opened by ⋮ or right-click at the cursor. `menuExtra` adds screen-specific entries |
| `MediaTile` | Every tile in a shelf or grid; songs carry the song menu on right-click |
| `DetailHeader` / `DetailTopBar` | Album/playlist/artist/podcast header (big round Play, icon actions, ⋮) and the top bar that shows the name once the header scrolls away |
| `ScreenTitle`, `ToolHeader`, `PrimaryAction`, `SecondaryAction`, `PillTab`, `SkeletonShelf/Rows` | Titles, buttons, tabs and loading placeholders (`Kit.kt`) |
| `CollapsibleSearchField`, `PlaylistSortMenu` | Search that opens from an icon, and one sort dropdown |
| `ScrollbarLazyColumn`, `ScrollbarLazyGrid`, `lyrenneScrollbarStyle()` | Drop-in LazyColumn / LazyVerticalGrid with a draggable scrollbar on the right (issue #18). Every Library tab uses them |
| `Tip` | Tooltip; every icon-only button is wrapped in one |
| `selectedChipColors()` (Theme.kt) | Selected = the primaryContainer family everywhere, never Material's default secondaryContainer |

### Checking UI without a screen: UiSnapshot

`desktop/src/test/.../ui/UiSnapshot.kt` renders the real App headless (ImageComposeScene) to
`desktop/build/snapshots/*.png`, driven by `desktop/build/snapshots/steps.txt`
(`size W H`, `theme dark|light`, `tray`, `wait MS`, `click X Y`, `rclick X Y`, `move X Y`,
`scroll X Y DY`, `type TEXT`, `shot NAME`). It copies `build/data/lyrenne.db` (never credentials) so
it renders signed out with the dev library. Skipped unless asked for:

```
./gradlew :desktop:test --tests '*UiSnapshot*' -Psnapshots
```

`size` and `theme` rebuild the scene, so put them before navigation. There is no window, so the
window-level shortcuts (AppShortcuts) and the floating disc are not exercised.

## Sync must be triggered by signing in, not only by launching

`syncLibrary()` no-ops when not signed in. The launch sync in `Main.kt` fires roughly a second
after startup, once, so on a first run it hits that no-op: there are no credentials yet. The user
then signs in and **nothing synced their library until the next restart**, leaving an app that
looks broken on the exact run where first impressions are formed.

Onboarding made that the path every new user takes, but the gap predates it: signing in from
Settings had always behaved the same way.

`Main.kt` now also collects `AuthManager.authState`, and syncs on any transition into signed-in.
Two details are load-bearing:

- **`drop(1)`** skips the value a `StateFlow` replays on subscribe. That value is whatever
  `AuthManager.initialize()` just settled on, which the launch sync has already handled, so
  without it every startup would sync twice.
- **It is deliberately not gated on `autoSyncOnStartup`.** That setting is about launch behaviour.
  Someone who just signed in performed an explicit action and expects their library to appear.

`syncLibrary()` guards both `isSyncing` and signed-out, so overlapping triggers are safe.

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

## Library sync: writing what it fetched

- **`insertSong` is INSERT OR IGNORE**, so it never sets `liked` on a row that exists. Through 2.15.1
  a song played, downloaded or added to a playlist before being liked on YouTube, or re-liked after
  an unlike, never showed as liked. Sync now follows it with `markSongLiked`, which only touches
  unliked rows, so an existing like keeps its date. New likes get `now - index` seconds, so
  "Date liked" follows YouTube's order.
- **Song credits use `insertArtistIfMissing`**, never `insertArtist` (REPLACE). REPLACE reset a
  subscribed artist's `bookmarkedAt` and thumbnail, and when the artists fetch failed nothing wrote
  them back.
- **A like changed here mid-sync is left alone** (`YouTubeWrites.likeEditedSince`). The snapshot may
  predate the push, and applying it would re-like a song just unliked, or prune one just liked.
- `updateSongMetadata` keeps the stored duration and year when a caller passes -1 / null.
- `SyncQueriesTest` pins the SQL.

### Quick sync of liked songs

Liked songs were up to 200 sequential page requests on every launch. Launch and sign-in syncs are
now quick: `fetchLikedSongs(known)` pages newest first and stops once it has seen 20 already-synced
songs in a row **and** YouTube's total (`songCountText` on the first page) equals known + new. An
unlike on YouTube or a new like further down breaks the sum, so it pages on to a full fetch, which
is the only kind that prunes. An unparsable total never matches: worst case is the old full fetch.
The Sync button and account switches always run full. The log says which path ran
(`Liked songs: quick sync` / `full fetch, YouTube says ...`); if a real library always lands on full,
the total YouTube reports does not match the songs it returns. `LikedSongsFetchTest` pins it.

## Icons: two artworks, picked by size

- `icon.png` (512px source), the full mark with its gold ring. Every `.ico` entry at 64px and up.
- `icon-small.png` (256px source), the same lyre without the ring. The tray, the window icon at
  48px and below, and the 16/20/24/32/40/48 `.ico` entries.

The ring is most of the pixels at 16px, so the full mark reads as a gold box rather than a lyre.
The small variant keeps the dark tile: dropping that too would leave a white glyph on transparency,
which vanishes on a light-theme taskbar.

### The .ico must carry the middle sizes, or the desktop shortcut looks upscaled

`icon.ico` holds **16, 20, 24, 32, 40, 48, 64, 96, 128 and 256**, all 32bpp uncompressed BMP.

Through 2.10.11 it held only 16, 32, 48 and 256, and the desktop shortcut was visibly blurry while
the taskbar was sharp. That is not two different icons, it is two different requested sizes.
Windows picks the nearest entry by absolute difference and scales it, and the desktop's default
"Large icons" view asks for 96px: `|96-48| = 48` beats `|96-256| = 160`, so it upscaled the 48px
entry 2x. The taskbar asks for 32px, hit an exact entry, and looked fine. Anything that reports a
blurry icon at one size and a clean one at another is this, and the fix is an entry at the size
being asked for, never a bigger 256.

Regenerate with `desktop/src/main/resources` as the working directory if the artwork changes.
Keep every entry uncompressed BMP: the 256px entry has always been BMP rather than PNG-in-ICO and
there is no reason to find out which loaders in the chain disagree about that.

`patchPortableIcon` was removed: jpackage embeds the `.ico` into the app-image exe itself, verified by
enumerating the exe's RT_GROUP_ICON after a build (all 10 entries present).

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

### Delivery: one locked send, a real timeout, and a retry

Every send goes through `publish()`, which reads the player state **inside** `presenceMutex`, so a
send queued behind another cannot publish something older than what went out first.

- **`publish()` runs under `NonCancellable`, and must.** The collector calls it inline and
  `collectLatest` cancels it on the next 200 ms position tick. Through 2.18.1 a song change that had
  to wait for the lock (a pause or seek send still in flight) was cancelled there: B never went
  out, the connection was closed, and Discord clears an activity when its connection closes.
- **The reply wait is bounded by `awaitReply()`, not by `withTimeoutOrNull` around the read.**
  `RandomAccessFile` reads block in native code and nothing interrupts them, and `withContext`
  waits for its block before honouring cancellation, so the old 2 s and 5 s timeouts never fired:
  a Discord that stopped answering held the lock, and every later update, forever. The read now
  runs detached and only the wait is bounded; a timed-out connection is dropped and closed off
  thread, because closing a synchronous pipe handle can block behind the read still pending on it.
- **A failed send is retried** (`retryUntilDelivered`, 5 s doubling to 60 s), so Discord opened
  mid-track gets the presence within seconds instead of at the next song. Nothing is written
  while delivery works, and while Discord is closed each attempt fails at opening the pipe.

`DiscordRpcSmokeTest` pins all three against a fake Discord served on its own named pipe, never
the real one. Not covered: Discord restarting mid-track while the old connection still looks
alive is noticed at the next update (pause, seek, track), since checking would mean a read, and
a pending read on a synchronous pipe handle blocks writes on it.

## Development Notes
- VLC must be installed on the system for playback to work (bundled VLC also supported)
- Stream URLs come from `ContentAwareFallbackStrategy`'s client chain (innertube). Each URL is checked
  with a one-byte request (`DownloadManager.streamStatus`) before use, and a refused one moves on to the
  next client: an OK player response can carry a URL YouTube 403s (issue #17). If none pass, the first
  URL is used anyway. Every client's outcome is logged at INFO (`Stream <id>: ...` / `Download <id>: ...`)
- The check is OkHttp's request, not VLC's, so it can pass a URL VLC is then refused. VLC's `error`
  event therefore retries once per start of a song: refused at 0:00, `getStreamUrl(skip = ...)` asks the
  other clients (`lastStreamClient` remembers which gave the URL); died mid-song, a fresh URL resumes
  where it stopped. `recoveredSongId` resets in `playMedia`
- WEB_CREATOR is appended as the last client in both chains: songs uploaded to your own library need a signed-in web client (issue #22, not reproduced here: no uploads on the dev account)
- This working copy IS a git clone with a working `origin`. Commit and push directly; older notes describing a robocopy-to-temp-dir push workflow are obsolete
- **Nothing is written outside the app folder, with one exception.** All state lives in
  `<app-dir>/data/`: `lyrenne.db`, `credentials.json`, `accounts/`, `lyrics-overrides.json`,
  `preferences.properties`, `cache/`, plus the Listen Together session. 2.9.4 removed the last
  `%APPDATA%` paths and the migration that read them. The exception is `windowsMediaAppName` (on
  by default since 2.13.0), which writes a single Start Menu shortcut and deletes it again when
  switched off. See Windows media identity
- Credentials stored at `<app-dir>/data/credentials.json`
- Delete credentials.json to force re-login
- All debug println converted to Timber logging (SLF4J-backed shim)
- DatabaseHelper.database is private — use DatabaseHelper methods, not direct DB access
- SQLDelight accessor is `lyrenneQueries`, named after the `Lyrenne.sq` file (rename the file and the accessor renames with it)

## Downloads: the queue is state on disk, not a list in memory

Three separate problems shared one cause, which is that the queue only ever existed in RAM and
every operation on it ran wherever it was called from.

**Queueing was done on the UI thread, per song.** `queueDownload` did two autocommit SQLite
writes and two whole-collection copies for each song, and callers ran an `isDownloaded` database
read per song on top of that, also on the UI thread. "Download All" on a 700-track playlist was
therefore 1400 separate transactions plus 700 reads plus O(n squared) copying before Compose could
paint another frame. `queueDownloads` now takes the whole batch, runs it off-thread inside one
`DatabaseHelper.transaction`, and does the already-downloaded filter itself. Do not reintroduce
that filter at a call site.

**The Downloads tab composed the active list eagerly.** It was a plain `Column` of `Card`s, so 700
queued songs built 700 Cards whether or not they were on screen, and every progress emission
recomposed all of them. It is one `LazyColumn` now, holding both sections. Progress emissions are
also throttled to one per song per 250 ms; at three parallel downloads emitting on each 1% change
that was several hundred copies of a large map per second.

**Nothing read the queue back.** The `DownloadQueue` table has existed since the beginning and
`getPendingDownloads()` was never called, so closing the lid or quitting mid-download lost the
work silently. `DownloadManager.restoreQueue()` runs from `Main.runApp()` and picks it back up.

### What makes resuming safe

Partial files are named `<songId>-<contentLength>.part`, and that name is the entire safety
mechanism. Resuming appends to whatever partial survived; if that partial came from a different
encoding of the same video the result is a valid-looking m4a made of two unrelated halves, and
nothing notices until playback weeks later. Putting the byte count in the name means a partial can
only ever be matched to the stream it came from, and a re-resolve that returns a different format
simply finds no file and starts clean. `DownloadResumeTest` pins this. Do not "simplify" the name.

- The partial is deliberately **not** deleted when a download throws. It is the resume point.
  Stale ones are swept by `pruneStalePartials` on the next attempt and by `cancelDownload`.
- The resume offset is the `range=` URL parameter, the same one that bypasses CDN throttling, so
  a resume is `range=<offset>-<total>` rather than an HTTP `Range` header.
- `fos.fd.sync()` before the fd closes. Without it up to 512 KB sits in the write buffer that the
  next resume's offset believes is already on disk.
- Rows still marked `downloading` at startup were interrupted, not failed, so `resetStuckDownloads`
  puts them back to pending. `restoreQueue` waits 5 s so it is not competing with the window paint,
  the auth check and the launch library sync.

### Retries, cancelling, and why every state write is conditional

Three attempts at 2 s, 5 s and 15 s, then the song parks as ERROR with a Retry button. Each attempt
re-resolves the stream URL, because an expired URL and a dropped connection look identical here.

**Every write for an in-flight song goes through `updateIfTracked`.** Cancelling removes the entry
from `activeDownloads`, but the job it cancelled keeps running until the coroutine unwinds, and any
plain `downloads + (id to ...)` in that window puts the card back. "Cancel all" emptied the list and
then refilled it with CANCELLED cards nothing could clear. The only unconditional inserts are the
ones that queue a song in the first place.

Jobs are registered with `CoroutineStart.LAZY` and started after the map assignment. A download
that finished inside its own `launch` otherwise removed itself before the assignment put it back,
leaving a completed job under that song id forever and making that song unqueueable.

### Holding off sleep, without leaving a lock behind

A 700 track download outlives any normal idle timeout, so `SleepGuard.keepAwake()` runs alongside
the processing loop and is cancelled in its `finally`.

It **pings** rather than holding a lock. The obvious implementation asserts
`ES_CONTINUOUS or ES_SYSTEM_REQUIRED` and releases with `ES_CONTINUOUS`, and both halves of that
are wrong here:

- The execution state is **per-thread and dies with the thread**. Asserting it from a coroutine
  asserts it on whichever pooled `Dispatchers.IO` thread ran that resumption, and those are
  reclaimed when idle, so the lock evaporates mid-download with nothing to show for it.
- **A standing lock leaks on a crash.** Die between assert and release and the machine never sleeps
  again until reboot, with nothing in the UI to explain why. That is a worse bug than the one being
  fixed and a much harder one to notice.

`ES_SYSTEM_REQUIRED` *without* `ES_CONTINUOUS` is the documented one-shot form: it pushes the idle
timer back once and asserts nothing persistent. On a 30 s timer that has no thread affinity, needs
no release, and cannot outlive the process. Do not "optimise" it into a standing lock.

`ES_DISPLAY_REQUIRED` is deliberately not set. Nobody downloading in the background wants the
screen lit all night.

Bound with a four-line JNA interface rather than `jna-platform`'s `Kernel32`, whose declared return
type for this call has moved between `int` and `DWORD` across versions. `SleepGuardSmokeTest` calls
the real kernel32, because a wrong binding does not throw: it resolves, marshals, quietly does
nothing, and surfaces weeks later as a laptop that suspended mid-download.

Covers the download queue only. Car export and the auto-updater run their own transfers and do not
hold sleep off.

### Per-playlist folders

"Download All" on an album, a YouTube playlist or a local playlist writes into
`Downloads/<sanitized name>/`. Single-song downloads (context menus, MiniPlayer, auto-download on
like) stay flat in `Downloads/`. There is no setting, and existing flat downloads are not migrated.
The subfolder is stored on the queue row so a restored or retried download still lands in the right
place. `pruneEmptySubfolder` removes a folder once its last file goes, and refuses to touch
anything that is not a direct child of the downloads root.

`DownloadQueue` carries the full `SongInfo` (title, artist, thumbnail, album, duration) rather than
joining `Song` on restore, matching what `PlayQueue` already does: the queue has to come back after
a restart without depending on rows elsewhere surviving, and artist lives in `SongArtistMap`, not
`Song`. The columns are added to existing databases by `addMissingColumns`, the first migration in
`DatabaseHelper` that looks at columns rather than tables.

## Windows media identity

Windows names a Now Playing source by resolving the media session's **app user model id**, and its
resolver knows two kinds of thing: packaged apps, and Start Menu shortcuts carrying a matching
`System.AppUserModel.ID`. A portable build is neither, so 2.12.0 shipped Windows media controls that
worked perfectly and were labelled **"Unknown app"**.

Two pieces, and both are needed:

- `integration/WindowsAppIdentity.kt` calls `SetCurrentProcessExplicitAppUserModelID` from `main()`,
  before any window and before the media session. Without it the process carries a path-derived id.
- `integration/WindowsStartMenuShortcut.kt` writes `%APPDATA%\...\Start Menu\Programs\Lyrenne.lnk`
  carrying the same id. Behind the `windowsMediaAppName` preference because it is the only file
  Lyrenne puts outside its own folder. Rewritten on every startup so a moved portable folder
  repoints it; deleted when the setting goes off, **but only if it points at this copy**.

**It is on by default since 2.13.0, and read from a new key.** 2.12.0 shipped it off by default,
which delivered the fix to almost nobody. The old key, `windowsMediaAppName`, was written on every
save, so it records 2.12.0's default rather than anyone's choice; the preference is now stored as
`windowsMediaShortcut` and the old key is ignored. Do not "tidy" it back.

**The shell resolves the name when it draws the card**, verified on the live flyout: removing the
shortcut turns the heading into "Unknown app" and restoring it turns it back into Lyrenne, with no
restart and no re-registering of the session.

**Two copies of Lyrenne share one shortcut.** Before 2.13.0 a copy with the setting off deleted it on
every launch, unnaming the other copy. The delete now reads the target back through
`IShellLinkW::GetPath` and leaves a shortcut that belongs to another exe alone.

**`RPC_E_CHANGED_MODE` is not a failure.** CoInitializeEx(STA) answers it on a thread already in the
multi-threaded apartment, which WinRT does to pooled IO threads. The shell link object is registered
for both apartments, so it is treated like `S_FALSE`: usable, not ours to uninitialise. Treating it
as fatal silently skipped the shortcut. The smoke test pins this.

Verify with `Get-StartApps | ? { $_.AppID -like "*lyrenne*" }`. A row reading
`Lyrenne / com.lyrenne.desktop.Lyrenne` is the exact lookup the flyout performs, and is a far better
check than squinting at the flyout.

**Two traps, both of which fail silently and cost an evening each:**

1. `InitPropVariantFromString` **is not an export.** It is an inline function in the Windows SDK
   headers; `propsys.dll` ships only `InitPropVariantFrom*Vector`. Loading it by name throws at
   runtime. The PROPVARIANT is built by hand instead: `VT_LPWSTR` (31) at offset 0, and a
   `CoTaskMemAlloc`'d UTF-16 string pointer at offset 8.
2. **`Guid.GUID.toByteArray()` returns string order, not memory order.** A GUID in memory stores its
   first three fields little endian, so writing that array straight into a PROPERTYKEY produces a
   valid but meaningless key. `IPropertyStore::SetValue` accepts it and returns `S_OK`, the shortcut
   saves, and the only symptom is a name that never resolves. The fields are laid out by hand.

`WScript.Shell` cannot do any of this: the app id is a property in the link's property store, not one
of the fields that object exposes. Hence the hand-rolled `IShellLinkW` / `IPropertyStore` vtable
calls, since jna-platform binds neither.

## Lyrics parsing

`lyrics/LyricsParser.kt` is the only thing that reads lyric text. The parser it replaced (LrcLib's
`Lyrics.sentences`) understood `[mm:ss.xx]text` and nothing else, and threw on anything else, which
sent the whole song to the plain view as raw text. That was issue #10: BetterLyrics sends upstream's
word-timed extension, a `<word:start:end|word:start:end>` line (seconds) after each lyric line, and
all of it appeared on screen. The same fault swallowed any LRC with an ID tag, and misread YouTube
transcripts' three-digit milliseconds, leaving a stray digit on every line.

It never throws; an unreadable line is skipped. `{bg}` lines are attached to the lead line before
them (`LyricLine.background`) so backing vocals never take "current line" away from the lead.
`LyricsParserTest` holds a verbatim sample of each format; add one there before changing it.

Two lookup bugs that look like "no lyrics for this song":

- **Duration.** LrcLib and KuGou match on it, and every fetch used to go out with `-1`, because the
  panel read the player's duration before this track had one. The panel now waits up to 4 s for the
  live length. Measured: LrcLib finds "Risk It All" with the length and not without.
- **Swallowed cancellation.** The vendored providers wrap their work in `runCatching`, which catches
  the CancellationException a track change delivers. A cancelled lookup then ran on down the chain
  and published "No lyrics found" over the next song's state. `ensureStillWanted()` after each
  provider rethrows it.

Manual picks are stored as full text in `data/lyrics-overrides.json`, keyed by song id, because no
provider offers a stable id to refetch the same result. Backups include the file.

## Lyrics: timing, editing, romanization

Matches what upstream Android offers (per-song offset, edit lyrics, romanization), all offline.

- **Timing** is per song, in `data/lyrics-offsets.json` (`LyricsOffsets`), kept apart from the
  override file on purpose: going back to automatic lyrics, or picking others, keeps it. Positive
  means the lyrics show sooner, the same sign as LRC's `[offset:]` and upstream's `lyricsOffset`.
  It is applied in the panel only: the position handed to `SyncedLyricsView` is `position + offset`
  and a click seeks to `line - offset`. The UI says "earlier" and "later", never a signed number.
  Zero is stored as no entry. Capped at 60 s either way.
- **Editing** saves through `LyricsManager.saveEdited`, which is a manual pick with source
  `Edited` (the header says "edited by you"), so "Go back to automatic lyrics" undoes it. The text
  goes through `LyricsParser` like any provider's, so LRC, word timing and `{bg}` all work.
- **Romanization** (`lyricsRomanize`, on by default, also in the panel's menu) is worked out off the
  UI thread for the whole song at once, keyed by line text, and drawn under each line in italics.
  - Any kana anywhere in the song makes it Japanese, so kanji-only lines are read as Japanese
    rather than as Mandarin. kuromoji gives each word's reading, tokens are grouped the way romaji
    lyrics are written (auxiliaries, suffixes and て/で join the word before; は/へ/を as particles
    read wa/e/o), and ICU spells the kana. Its dictionary costs about 45 MB of heap and loads only
    when a Japanese song shows.
  - Hangul goes through `KoreanRomanizer` (Revised Romanization with the common sound changes).
    ICU's Hangul-Latin is letter for letter ("salanghae", "sip-eo"), and its Korean BGN transform
    needs decomposed jamo and still mangles finals.
  - Cyrillic uses ICU's BGN/PCGN transform for the language the song's letters point to (і/ї/є/ґ
    Ukrainian, ў Belarusian, ђ/ћ/џ Serbian, ѓ/ќ/ѕ Macedonian, else Russian). ICU's default is ISO 9,
    which writes я as "â".
  - Everything else is ICU `Any-Latin` (pinyin with tones, Greek, Indic scripts...). Arabic,
    Hebrew, Thai, Lao, Khmer, Myanmar, Syriac and Thaana get **nothing**: they leave vowels
    unwritten and a letter-by-letter spelling is unreadable. `RomanizerTest` pins sample output.
  - ICU4J and kuromoji add about 25 MB to the release. Both only need JDK modules the runtime
    already has (checked with `jdeps --print-module-deps`).

## Account switching

`credentials.json` is always the **active** account, so nothing that reads it changed. Other signed-in
accounts sit in `data/accounts/<accountId>.json`, the same format, the same plaintext. Signing in while
signed in adds an account: `saveCredentials` moves the current one aside instead of overwriting it.

- `accountId` is a hash of the DATASYNC_ID (the SAPISID cookie if the page gave none), assigned once
  and **stored**. Do not recompute it from live values: those move under a refresh, and a moving id
  splits one account into two. Pre-2.13.0 credentials get one on first load.
- Every read-modify-write of either file goes through `filesLock`. A refresh that started for the old
  account otherwise wrote its cookies over the new one after a switch.
- Saved accounts get their cookies rotated on the same 6 h cycle, or switching to one after a few weeks
  lands on a dead session. One YouTube reports signed out is marked `sessionExpired` and offers Sign in
  instead of Switch.
- `AuthState.accountId` is what the library resync and the home cache key on. `isLoggedIn` does not
  change on a switch, so anything keyed on it alone keeps showing the previous account.
- `LibrarySync.resync()` cancels **and joins** a sync in flight before starting, so the old account's
  transaction cannot land after the new one's. Pruning only clears liked and bookmarked flags, so a
  switch never deletes downloads, local playlists or history; those are shared by every account.
- Signing out removes only the active account. The rest stay.

## Floating mini player

Created in `Main.kt`, drawn by `ui/components/FloatingPlayer.kt`. Three things that are not obvious:

- **The window exists for as long as the setting is on**, and only its visibility follows the main
  window. Creating it on demand took around four seconds to appear.
- **It is built through the `create` overload of `Window`**, because `type = UTILITY` (no taskbar
  button), `isTransparent` and `focusableWindowState = false` must all be set before the window is
  first shown, and the plain overload offers none of them.
- **Drag and double-click share one pointer handler.** `WindowDraggableArea` only starts on an
  unconsumed press, and a double-click detector consumes every press, so stacked together the disc
  could never be dragged. Presses the transport buttons consume are left to them.

To screenshot it, capture with `CAPTUREBLT`. A plain BitBlt (and `Graphics.CopyFromScreen`) skips
layered windows, so the disc is invisible in the capture while being perfectly visible on screen.

## Tray panel and quitting

The right-click tray panel (`ui/components/TrayPanel.kt`, its window in `Main.kt`) lagged and Quit
froze on a laptop in power saving mode. Measured 2026-10-01 on a desktop, with the process put in
Windows efficiency mode (EcoQoS) on two E-cores as a stand-in for Energy Saver:

- **Never start a process from composition.** `isWindowsDarkTheme()` ran `reg query` on the UI
  thread every time a window first composed `LyrenneTheme`, and the tray panel did that on every
  open. From a backgrounded, idle Lyrenne that one spawn held the UI thread for 4.4 s, while the
  same command takes 33 ms from a shell. Windows throttles exactly that kind of process hardest.
  `SystemTheme` now reads it off the UI thread (seeded from Main, polled every 30 s), and on
  Windows through JNA's registry API instead of `reg.exe`.
- **The panel's window is made once**, shown and hidden after that like the floating player. A
  new window per right-click cost 0.22 to 0.30 s each time; later opens paint in 37 to 108 ms.
  (The 2026-10-01 version also pre-created it with `pack()` when the tray icon appeared, saving
  0.22 s on the first open; upstream main's rewrite doesn't, add it back if the first open lags.)
- **The panel is only composed while open**, and only `TraySeekBar` reads the position, in whole
  seconds. Collecting the whole `PlaybackState` redrew all of it five times a second.
- **`quit()` hides every window first, then releases on a `shutdown` thread**, halting after 3 s
  whatever is unfinished (queue saved first, in `player.release`). VLC's release took 213 to
  246 ms with a song playing, all of it on the UI thread with the panel frozen on screen. Every
  quit path goes through it.
- **The floating player stays hidden while quitting** (`quitting`). `quit()` hiding the main
  window would otherwise bring it up for the length of the teardown.

To drive the tray icon without a mouse, post AWT's tray message to its `SunAwtTrayIcon` window:
`WM_AWT_TRAY_NOTIFY` is `0x9834` on JDK 21, `wParam` the icon id (1), `lParam` `WM_RBUTTONDOWN` then
`WM_RBUTTONUP`. AWT reads the position from the cursor. Posting `WM_ACTIVATE` with `WA_INACTIVE` to
the panel dismisses it. `jcmd` cannot attach to the jlinked runtime, so sample stacks in-process.

## Autoplay

A song played on its own (search, home, recognition, stats, Recently Played) is a one-song queue.
Through 2.13.0 that meant silence when it ended, with Next doing nothing: the auto-queue check lived
inside `playNext()`, which a finished track only reached when there was a song after it, and the
setting was off by default anyway. Issue #12. The Library song list did the same thing, playing
one song where the list should carry on, which is the follow-up report on #11.

- **When**: `prefetchSimilarSongs()` runs on every track start and fetches only when the current
  song is the last in the queue. A fetch measured 0.6 to 3.8 s, too long a silence to leave until
  the song ends. `onTrackFinished` and `playNext` fall back to `awaitSimilarSongs()`, which joins
  the fetch in flight or starts one.
- **Seed**: a fresh `RDAMVM<id>` radio of the last song in the queue, every time. Paging one radio
  through its continuations looks more coherent but measured 5 to 14 new songs per 49-song page,
  because each page mostly repeats the last. Re-seeding from the tail brought 15 to 48.
- **Order**: autoplay's songs carry `SongInfo.fromAutoplay`, and `addToQueue` inserts in front of
  the first one after the current song. Without that, a song queued while a search result plays
  would wait behind 49 suggestions. Moving one by hand clears its flag, since it is the user's pick
  now. The queue panel draws an Autoplay header where they start. The flag is not persisted.
- **Start Radio's songs are not flagged.** A radio the user asked for is the queue itself, like an
  album. `radioStarted` keeps it topping up even with autoplay off, as it always did.
- **Switching autoplay off** removes its flagged songs still to come. Played ones stay as history.
- **Listen Together guests never autoplay** (`DesktopPlayer.autoplayAllowed`, set by
  `ListenTogetherManager`). Host and guests both send track changes, so a guest that autoplayed
  would move the whole room onto its own pick. The host autoplays and the room follows.
- **A replaced queue drops fetches in flight**: `queueGeneration` bumps on every replacement and
  the append checks it, so a slow fetch cannot land in the queue the user has since started.
- **Hide explicit content** applies to everything autoplay and Start Radio queue.
- **Repeat All holds autoplay back only on a list the user chose.** A song played on its own
  (`playedAlone`, set by `playSingle`) and a radio carry on under Repeat All; Repeat One always
  holds it back. Repeat is saved across restarts, so Repeat All left on from an album made every
  search result loop itself with nothing queued (issue #19, same reporter as #12).
- **Shuffle**: appended songs go into `originalQueue` too, even while shuffled, and so do songs the
  user adds (Add to queue in front of autoplay's picks there as well, Play next straight after the
  playing song). Left out, turning shuffle off dropped them, and with them the song playing if it
  was one of them: the current index fell back to 0 while it played on. Except when `originalQueue`
  is empty, which a restart with shuffle on or clearing the queue while shuffled leaves behind.
  Turning shuffle off keeps the queue as it is then, and adding to the empty order would make it
  just the songs added since. `hasOrderToRestore()` is that check.
- **The setting lives under a new key, `autoplay`, on by default.** The old `autoLoadRadio` was off
  by default and written on every save, the same trap as `windowsMediaShortcut`: it records the
  old default rather than a choice. Do not read the old key back.

`AutoplayTest` pins the queue rules offline with a stubbed radio (`DesktopPlayer.radioFor`).
`AutoplaySmokeTest` runs the real player against YouTube without VLC, which it tolerates.

## Smart Shuffle

`DesktopPlayer.smartShuffle` (issue #15). The playlist starts shuffled at once; YouTube's radio for three
of its songs is then fetched in parallel (`radioFor`, up to about 4 s), merged round robin, stripped of
anything already queued and of explicit songs when those are hidden, and woven in one after every three
songs still to play (`interleaveSuggestions`). Leftovers are dropped, so a very long playlist only gets
suggestions as far as the radios reach.

- Suggestions carry `SongInfo.suggested` (not persisted) and the queue panel labels them. They are
  not `fromAutoplay`: songs the user adds do not jump them, and switching autoplay off keeps them.
- The same `queueGeneration` check as autoplay drops the suggestions if the queue was replaced while
  the radios loaded, and they go into `originalQueue` under the same rule.

`SmartShuffleTest` pins it offline.

## Downloads play from disk

Through 2.13.0 every path that starts a song resolved a YouTube stream, downloaded or not: the
queue paths (`playQueue`, `playNext`, `playPrevious`, `playAtIndex`, the repeat-all loop in
`onTrackFinished`, the restored-queue branch of `togglePlayPause`) and `playSong` alike. Only a
click in the Downloads tab played the file, through a `playLocalFile` of its own. A downloaded song
reached any other way, the Downloaded auto playlist included, was streamed, which fails offline.

- **One chooser.** `mediaFor(song)` returns the download's path when
  `DownloadManager.getDownloadPathById` finds one (the database `localPath` if that file still
  exists, else the legacy `<id>.m4a`), and a stream otherwise. Every path that starts a song goes
  through it. A file deleted behind the app's back just streams.
- **One starter.** `playMedia` (formerly `playUrl`) takes either; VLC opens a path and a URL through
  the same call. So the play event, the crossfade fade-in, EQ, speed, the audio filters, the
  duration seed, `recentlyPlayed` and the autoplay prefetch all apply to files. `playLocalFile` had
  only the last three, and credited the previous song's listening time to the file's song.
- **A failed lookup streams.** The lookup catches its own failure and returns null, so a database
  problem costs the song its offline copy, not its playback. It is also what lets the tests play at
  all, since none of them opens the database.
- **The Downloads tab plays the list on** from the clicked song, like the Songs tab.

`DownloadedPlaybackTest` pins it offline, with the lookup (`DesktopPlayer.downloadedFileFor`) and
the stream (`DesktopPlayer.streamFor`) both stubbed.

## Game Mode (global hotkeys and the in-game overlay)

Control the music and see lyrics from inside a flat-screen game without alt-tabbing. Code in
`gamemode/` (plus `ui/screens/GameModeSettings.kt`); the pure parts are `Hotkeys.kt` and
`OverlayModel.kt`, pinned by `HotkeysTest` and `OverlayModelTest`. Settings > Game Mode, and three
command palette entries.

**Global hotkeys** (`GlobalHotkeys.kt`)

- **Windows: `RegisterHotKey` with no window, on a thread of its own** (`lyrenne-hotkeys`) running
  `GetMessage`. A windowless hotkey belongs to the thread that registered it: WM_HOTKEY lands in that
  thread's queue and only that thread may unregister it. So every register/unregister is a task posted
  to that thread (`PostThreadMessage` wakes it), and the queue is created with a `PeekMessage` before
  anyone may post. `MOD_NOREPEAT` on everything: holding the keys must not skip ten songs.
- **One code path for every change.** Any change to the Game Mode preferences re-registers the whole
  set; turning one off, rebinding, the master switch and quitting all unregister through it.
- **The OS refusing a combo is the conflict check.** `ERROR_HOTKEY_ALREADY_REGISTERED` (1409) means
  another app owns it, and the row says so ("Taken"). There is no other reliable way to know. Two
  actions on one combo are caught before that (`HotkeyPlan`; the first listed keeps it), as are combos
  that would break typing (no Ctrl, Alt or Win, except F13 to F24) and ones Windows keeps
  (Ctrl+Alt+Del with anything, Win+L, Alt+F4, Alt+Space).
- **Recording a combo pauses every hotkey.** A registered hotkey is swallowed before any window sees
  it, so without the pause the current combo could never be recorded again, and pressing it would also
  play or pause. The recorder listens through an AWT `KeyEventDispatcher`, not a Compose handler: the
  window's own shortcuts run first in Compose and Ctrl+K would open the palette. A combo another app
  owns never reaches the recorder at all; the hint under the row says so.
- **Defaults: Ctrl+Alt plus a navigation key, and only play/pause, next and previous on.**
  Ctrl+Alt+Home / Page Down / Page Up. Games rarely bind Ctrl+Alt, and the navigation cluster types
  nothing, which matters because AltGr arrives as Ctrl+Alt: Ctrl+Alt+E would eat the euro sign on a
  German layout, Ctrl+Alt+L the Polish l-stroke. On by default because media control is the whole
  point and these three cost nothing; any that collide show as taken instead of fighting. Like,
  volume, seek, peek and show/hide stay suggestions a switch away: they are less universal, and the
  arrow combos clash with some Intel graphics drivers' screen rotation.
- **Linux: X11 `XGrabKey` on the root window**, own display connection, polled on its own thread.
  Every combo is grabbed four times (Caps Lock and Num Lock states), or a lit Num Lock kills them all.
  A grab someone else holds fails asynchronously with BadAccess, whose default handler exits the
  process, so an error handler is installed only around the grab and its `XSync` and passes other
  connections' errors to the handler it replaced. **Not run on real Linux hardware yet**; any failure
  turns hotkeys off with a line in Settings. **Wayland** cannot do global grabs (XWayland only sees
  keys while an X window has focus), so the controls are hidden and the line points at binding
  `playerctl play-pause` in the desktop's own shortcuts, which works because Lyrenne publishes MPRIS.

**The overlay** (`OverlayWindow.kt`, `OverlayPlacement.kt`), Windows only for now

- **A Compose window through the `create` overload**, like the floating player: UTILITY type,
  undecorated, transparent, always on top, `focusableWindowState = false`, `isAutoRequestFocus =
  false`. `addNotify()` makes the native window before it is ever shown, so the extended styles are in
  place first: `WS_EX_LAYERED | WS_EX_TRANSPARENT` (clicks fall through), `WS_EX_NOACTIVATE` (never
  takes focus), `WS_EX_TOOLWINDOW` without `WS_EX_APPWINDOW` (no taskbar button, no Alt+Tab). Compose's
  transparent window is already layered, so adding the flags keeps per-pixel alpha. They are re-applied
  on every show because AWT rewrites the extended style for some changes. Verified live:
  `0x080800A8`, foreground window unchanged after showing.
- **Hidden whenever it has nothing to draw**, and created 3 s after launch. A visible window over a
  borderless game, even an empty one, makes Windows compose the game through the desktop instead of
  flipping it straight to the screen, which can cost frames. Exclusive fullscreen hides it entirely;
  the Settings line says so.
- **What shows when** is `OverlayModel`, clock-free and tested with numbers: Song toast (5 s on a song
  change), Lyrics strip (while synced lyrics play; hidden while paused), Off. Peek shows card and line
  in any mode; show/hide hides the mode, or from Off pins it; flashes confirm every hotkey for 1.4 s
  and always show. Nothing shows on its own while a Lyrenne window is active, since the real player is
  in view.
- **Placement**: the monitor the foreground window is on (`MonitorFromWindow`, matched to an AWT
  screen by its physical bounds, AWT bounds times the screen's scale), re-checked every 1.5 s while
  shown, or a chosen screen. Size is one 420x270 dp layout scaled by a density override, so DPI needs
  nothing extra: window geometry is in dp (see Window sizing).
- **Reuse, not copies**: lyrics come from `LyricsManager` with the song's `LyricsOffsets`, the line
  from `currentLineIndex`, the word fill from `highlightWords` and `rememberSmoothPosition`
  (LyricsPanel), the tint from `rememberArtTint`. The lookup moved into `fetchLyricsFor` so the
  overlay can start it while the main window is hidden, and `fetchLyrics` now drops a second request
  for a song whose lookup is still running.
- Always the dark scheme, whatever the app theme: it sits over games, not over Lyrenne.
- To screenshot it, capture with `CAPTUREBLT` (as for the floating player). To drive hotkeys, send
  `SendInput` key events from another process while another window is foreground.

## Lyrenne Link (stream overlay and phone remote)

A small HTTP server inside the windowed app (`link/`), with plain HTML/CSS/JS pages in
`resources/link/` (no build step, no CDN: it must work on a LAN with no internet). Settings,
Integrations, "Lyrenne Link" holds the overlay builder, the remote toggle and the now playing files;
the Command Palette has "Show phone remote QR" and "Copy OBS overlay URL".

- **Two features, one server.** `/overlay` is an OBS Browser Source (transparent, card, lyrics or
  ticker layout, query params `layout pos scale accent font lyrics preview`). `/remote` is the phone
  remote. Nothing listens unless one of them is on: the overlay alone binds `127.0.0.1`, the remote
  binds every interface. `LyrenneLink.initialize` restarts the server whenever the switches or the
  port change. Default port 48712 (headless mode is 48711); a busy port moves to the next free one
  of 20, and Settings and the copied URL show the real one.
- **Separate from headless mode on purpose.** `Headless.kt` is the Decky plugin's contract (token in
  a header, no browser pages, its own JSON shapes) and is untouched. Link reuses its approach
  (`com.sun.net.httpserver`, already in the jlink module list) but has its own routes.
- **Settings live in `data/link.properties`** (`LinkSettings`), not preferences.properties, so the
  pairing token never travels with a settings backup, and the feature stays out of a shared file.
- **Access levels** (`LinkRoutes`): PUBLIC (pages and assets), READ (`/api/now`, `/api/lyrics`,
  `/events`, `/art`), CONTROL (everything that changes playback, plus queue and search).
  - **READ needs no token from loopback, and needs it from the LAN.** OBS URLs end up in scene
    collections, screenshots and screen shares; a token in them would leak the remote's pairing. What
    READ returns is what the overlay puts on stream anyway. Other machines cannot reach loopback,
    browsers cannot read it cross-site (no CORS headers, Origin and `Sec-Fetch-Site` checks), and
    DNS rebinding is stopped by the Host check. A second streaming PC can add `#t=<token>`.
  - **CONTROL needs the token and the remote switched on**, even from loopback.
- **Checks on every request** (`LinkSecurity`, all unit tested): Host must be a loopback name, or one
  of this PC's addresses/hostnames while the remote is on, with our port (DNS rebinding); Origin, when
  present, must be the same origin; `Sec-Fetch-Site` must be `none` or `same-origin`; POST bodies must
  be `application/json` (a cross-site form or `text/plain` cannot be sent without a preflight, and
  preflights are never answered); the token is compared as SHA-256 digests with
  `MessageDigest.isEqual`; 10 wrong tokens from one address in a minute lock it out for a minute
  (429). No `Access-Control-*` header is ever sent. HTML pages carry a CSP with `connect-src 'self'`.
- **Pairing.** The token is 24 random bytes, base64url, made on first load. The QR code (ZXing core,
  M level, no ECI header since some phone cameras choke on it) carries
  `http://<lan-ip>:<port>/remote#t=<token>`: a fragment, so it is never sent to the server, logged, or
  leaked in a Referer. The page keeps it in localStorage and in the URL hash, and asks for
  `/manifest.webmanifest?t=` so "Add to Home Screen" opens straight back into the pairing.
  "Forget paired phones" rotates the token and drops every paired event stream at once.
  - Home-screen install on plain http: iOS honours the manifest and apple meta tags; Android Chrome
    only makes a full PWA on https, so there it is a browser shortcut. No service worker for the same
    reason (it needs a secure context).
- **Live updates are Server-Sent Events, not WebSocket**: one-way is all that is needed, it works with
  the JDK server, and `EventSource` reconnects by itself. Each stream is owned by its handler thread
  with its own queue, so a phone that stops reading stalls only itself (256 events behind and it is
  dropped). Events: `state` on a discrete change (song, play/pause, shuffle, repeat, volume, liked,
  accent, duration learned) or a detected seek, plus a 5 s heartbeat; `lyrics` when LyricsManager
  publishes; `queue` (paired pages only). Position is never streamed: pages interpolate it from the
  last report, so a 200 ms tick never floods anyone. The client watchdog replaces a stream silent for
  16 s, and reconnects at once when a sleeping phone wakes.
- **Lyrics without the panel.** The panel is what normally starts a lookup. Link starts it on song
  change while a page is connected, and when a page connects, with the same wait for the live
  duration as the panel. Only lyrics whose `songId` matches the playing song are ever sent. The
  per-song timing offset goes out with the state, so a nudge in the panel reaches the overlay.
- **Art is proxied** (`/art?v=<id>`), larger size, cached for the last 4 songs, and only ever the
  playing song's cover: it is not a general image proxy. It lets the pages work on a LAN with no
  internet and gives the export a cover. The accent colour is `ArtColor`'s, worked out in the app.
- **Commands run on the UI thread** (`Dispatchers.Main`), as the window's own buttons do, and answer
  at once; the result arrives as a `state` event. The remote volume slider writes the settings file
  once it settles, like the VR slider path.
- **Now playing files** (off by default): `now-playing.txt` ("Artist - Title", empty when nothing is
  loaded), `now-playing.json` and `cover.jpg` (re-encoded to JPEG when YouTube sends PNG or WebP),
  written via a temp file and a move. Windows refuses to replace a file another program holds open,
  so a failed move falls back to a plain write.
- **Windows Firewall.** Binding beyond loopback makes Windows ask once. The remote switch says so
  in one line before it is turned on, and the pairing dialog says where to allow it afterwards.
- **Tests**: `LinkSecurityTest`, `LinkProtocolTest` (routes, SSE encoding, state and lyrics JSON),
  `LinkServerTest` (the real server on loopback with a fake player: Host/Origin/token/429/415, SSE
  delivery and token rotation), `NowPlayingExportTest`. `LinkUiSnapshot` (-Psnapshots) renders the
  Settings section and pairing dialog.
- **Checking it live**: anonymous playback is refused by YouTube (HTTP 403 from the CDN), so a dev run
  plays nothing from search. Mark a song downloaded in `build/data/lyrenne.db` with `localPath`
  pointing at any WAV to get real playback. Headless Edge over CDP takes screenshots of the pages;
  `msedge --headless --screenshot` alone never finishes while an event stream is open.
## Move in (MetroList import, playlist links)

`movein/` and `ui/movein/MoveInUi.kt`. Two importers behind one set of windows (`MoveInHost`,
drawn once at the end of `App`, so it also covers onboarding). Entry points: the optional
onboarding step after sign-in ("Coming from MetroList?", which made the wizard eight steps), two
rows under Settings > Backup & Restore (`MoveInSettingsRows`), and two command palette actions.

### MetroList backups

- **Formats.** A 13.x backup is a ZIP of `song.db` (Room, schema 35 and earlier) plus
  `settings.preferences_pb`. A 14.x backup (the KMP rewrite) is `backup.json` + `settings.json` +
  `song.db` (Room 36 and up), sometimes `song.db-wal`. InnerTune and its forks share the Room
  family. `MetroListBackup` looks up every table and column before reading it, so a fork missing
  one imports less instead of failing. A bare `song.db` is accepted too.
- **14.x likes live in `settings.json`**, under the `likedSongsJson` key, newest first, not in the
  database. Both sources are merged. A renamed song keeps its real title (`canonicalTitle`).
- **Room dates are wall-clock time written as UTC milliseconds.** `wallClock()` turns them back
  into the LocalDateTime strings Lyrenne stores, and plays into true epoch ms for `Event`.
- **Both settings files carry the MetroList login cookie.** The 13.x protobuf is never read; of
  `settings.json` only `likedSongsJson` is. `MetroListBackupTest` checks the cookie never surfaces.
- **Skipped on purpose:** phone-local files (`isLocal`, `content://`, `file:` ids), podcast
  channels, LM / SE, and 14.x plays pulled from YouTube's history (`playTimeKnown = 0`), which were
  never played in MetroList and would only inflate stats. The 13.x `lyrics` table is a provider
  cache; only rows with provider `Manual` are the user's. 14.x has `userLyrics` and
  `isUserSelected`. Offsets (`song.lyricsOffset`, 14.x `lyrics.offsetMs`) keep their sign.
- **Merge, never overwrite.** `MoveInImporter` runs one transaction through
  `DatabaseHelper.moveInTransaction` with the `moveIn*` queries in `Lyrenne.sq`: INSERT OR IGNORE
  plus gap filling, likes only on unliked rows, credits only for songs with none. A play is
  skipped when one exists for the same song at the same millisecond, which is what makes a second
  import add nothing. A MetroList playlist gets the local id `LPML-<its id>`, so a re-import tops
  up the same playlist (new songs on the end) and keeps the user's name and order. YouTube
  playlists come over as references (id = browseId, no songs): Lyrenne opens them from YouTube.
  12,000 songs and 40,000 plays import in about 1.3 s.
- **Sync must not undo it.** Imported likes get `inLibrary` so they show under Songs as they did
  in MetroList, but sync's song prune unlikes any `inLibrary` like YouTube lacks. `MoveInLedger`
  (`data/movein.json`) records imported likes and followed artists; `LibrarySync.pruneSongs` and
  `pruneArtists` skip them, and the quick-sync `known` set leaves them out. A like leaves the
  ledger once a full sync sees it on YouTube, after which an unlike there is mirrored as usual.
  Saved albums use `inLibrary`, which sync never prunes, rather than `bookmarkedAt`, which it does.
- **Likes stay local unless asked.** "Also like them on YouTube" is off by default: MetroList
  usually synced its likes already, the account may differ, and a thousand likes are not undone by
  hand. When on, new likes go through `YouTubeWrites.likeSong` one at a time, 350 ms apart, oldest
  first so YouTube's order matches. Going through `likeSong` keeps `likeEditedSince` honest for a
  sync running meanwhile.

### Playlist links and pasted text

- `PlaylistSources` reads only what anyone can see without a login or key: Spotify's embed page
  (`__NEXT_DATA__`, first 100 songs), Apple Music's page data (`serialized-server-data`, total from
  its ld+json), Deezer's open API (paged), TIDAL's embed player (first 50, no total), SoundCloud's
  page hydration (first 5 in full, the rest as ids, filled from api-v2 with the client id that same
  page hands out, as its web player does). Every cap is reported in `limitNote` with the way
  around it (paste a CSV, e.g. from Exportify). Share links (link.deezer.com and friends) are
  followed first. Each parser is a pure function of the page text; fixtures are trimmed real pages
  captured 2026-11-15, and `PlaylistSourcesSmokeTest` checks the live pages still match.
- `TextPlaylist` takes "Artist - Title" lines (also "Title by Artist", tabs, numbering, a trailing
  "3:45") or a CSV/TSV whose columns are found by header name (Exportify, TuneMyMusic, Soundiiz,
  a Music app export).
- `TrackMatcher` scores 0..1: half title, three tenths artist, a fifth duration, minus penalties
  for a version the source did not ask for. Karaoke, cover, sped up, slowed and 8D count anywhere
  in a title; live, remix, acoustic and instrumental only in brackets or after " - ", or
  "Live Forever" would be a live version. A song off a live album counts as live. Remasters are
  neutral. Videos lose a little to songs, uploads more. Titles in scripts without spaces are
  compared by character bigrams. Above 0.72 is saved as is, 0.45 to 0.72 is highlighted with its
  alternatives, below is "not found". Songs are searched first; videos only when no song scores
  well. `TrackMatcherTest` is the table of awkward cases; add to it before retuning.
- Matching runs three searches at once and fills rows as they finish. Saving writes the local
  playlist in one transaction first; with "Also create it on YouTube" it then adds songs to the
  new YouTube playlist one at a time, in order, because concurrent adds land shuffled.

### Testing it

`./gradlew :desktop:test --tests 'com.lyrenne.desktop.movein.*'` covers the readers, importer,
parsers and matcher. The fixture backups are real Room databases built from the CREATE statements
in upstream's own schema JSON (`metrolist13-schema35.sql`, `metrolist14-schema65.sql`) and the
`-data.sql` files beside them. `MoveInSnapshot` and `MoveInEndToEnd` (both `-Psnapshots`) render
the windows in both themes and run a fixture import through the real App and database, then
the Library and Stats.
## Sound and scrobbling (ListenBrainz, SponsorBlock, AutoEQ)

Three features under `sound/`, each with its own settings and tests. All their network calls go
through `sound/HttpTransport.kt` (OkHttp, honours the Settings proxy), an interface so the tests answer
with fixtures from `desktop/src/test/resources/sound/`. `SoundEndpointsSmokeTest` checks the real
SponsorBlock and AutoEq endpoints and skips offline.

### ListenBrainz (`sound/listenbrainz/`)

- **On while a token is saved.** Settings > Integrations > ListenBrainz: the pasted token is checked
  live against `/1/validate-token`, and the button says whose it is before anything is saved.
- **Counting is by time heard, not a timer.** `ScrobbleTracker` adds up position that advanced by
  playing (steps of 3 s at most), so a seek forward counts nothing and a pause just stops the count.
  The Last.fm path waits on a timer from the start, which a single pause cancels for good; it is
  unchanged here. Threshold: half the track or 4 minutes, tracks under 30 s never count, an unknown
  length counts after 4 minutes. `listened_at` is when the listen started. Repeat One is a second listen.
- **No listen is lost to the network.** A listen goes into `data/listenbrainz-queue.json` before the
  first attempt and leaves only on HTTP 200 (ListenBrainz dedupes a resend). Retries back off from
  15 s, doubling, to 30 min, and honour `X-RateLimit-Reset-In`. A 400 on a batch is retried one by one
  so one bad listen cannot block the rest; a single refused listen is dropped. A 401 keeps everything
  and stops sending until a new token is pasted. `playing_now` is best effort and never queued.
- **Not sent:** podcast episodes (marked in `PodcastEpisodes` where episodes become queue entries,
  in memory only, so a restored queue loses the mark) and anything while "Pause listen history" is
  on. ListenBrainz is a music record, and MusicBrainz has no podcast entities to match against.

### SponsorBlock (`sound/sponsorblock/`)

- **Off by default** (it asks a third party about what plays). When on it sends only the first four
  hex digits of the video id's SHA-256 to `/api/skipSegments/<prefix>`, and picks the song's segments
  out of the answer locally. No "viewed" reports go back, for the same reason.
- **Default categories** once on: `music_offtopic`, `sponsor`, `selfpromo`, the ones that are never
  music. Intro and outro stay off: on a song, "intro" can mean the song's own opening.
- **Not skipping real music** rests on `Segment.trustedFor`: segments timed on a video of another
  length (more than 2 s off from what VLC reports) are ignored, because the timings then belong to a
  different cut; downvoted ones are ignored unless locked; one covering over 60% of the track is
  ignored unless locked. Every skip shows Undo. That is why it can stay safe with music_offtopic on,
  but the feature as a whole is still off by default under the third-party rule.
- **The skip runs on DesktopPlayer's position tick** (`SponsorBlock.onPositionTick`), decided by the
  pure `SegmentSkipper`: playing into a segment jumps to the end of it and of every segment that
  overlaps or touches it; a seek into one (`DesktopPlayer.seekTo` reports it) plays it until playback
  leaves it; a seek to under 1 s is starting over, so the intro goes again; Undo (`keep`) holds for the
  rest of that play; a skip VLC did not carry out is retried once after a second, then never.
  The player's own jumps use `skipSegmentTo`, not `seekTo`, or they would count as the user's.
- **End of track:** a jump that would land in the last 2 s or inside the crossfade window ends the
  track instead (`endTrackEarly`), so the next song starts and fades in. With nothing queued after it,
  or the sleep timer set to end of track, it jumps to the last moment and VLC's own finished event
  takes over, so autoplay and the timer behave as always. Ending is never retried: the next song's
  stream can take seconds to resolve, and a second "end" in that time would skip it too.
- **Listen Together guests do not skip** (`autoplayAllowed()` is the same "may this client choose"
  switch): the host's skip reaches them as a seek.
- Ticks in the first 0.7 s after a song change are ignored: VLC can still report the old media's time.
- UI: `SegmentSkipSnackbar` ("Skipped non-music part" with Undo, 6 s, placed in App.kt) and marks on
  the seek bar (`PlayerSlider(marks = ...)` in the dock and the tray panel), pale olive in dark and
  the flag olive in light, distinct from both the played and the unplayed track.

### AutoEQ and the equalizer (`sound/eq/`, `ui/screens/EqualizerTools.kt`)

- **Data:** `results/INDEX.md` of github.com/jaakkopasanen/AutoEq (one line per model, about
  850 KB, cached in `data/cache/autoeq/` for two weeks and used stale when offline) and each model's
  `<name> GraphicEQ.txt`. The model's folder name is its name, which the profile URL relies on.
- **VLC's equalizer, modelled from its source** (`VlcEqModel`): `out = preamp * (0.25 x + sum amp_j
  * bandpass_j(x))`, `amp_j = 0.25 (10^(dB_j/20) - 1)`, one-octave bandpasses. So **preamp 12.04 dB is
  unity**, which is why VLC's Flat preset and this app's default say 12. The screen now says so.
- **Bands come from vlcj, never a table in the code.** libvlc's API reports the ISO octaves
  (31.25 Hz to 16 kHz), not the 60 Hz to 16 kHz table VLC's own player shows. The fit, presets and
  import all take the band list as a parameter, and a preset saved against other bands is refitted.
- **The fit is least squares, not the curve read off at each centre.** The correction is normalised
  to 0 dB at 1 kHz, then the bands are solved together (damped Gauss-Newton over 20 Hz to 20 kHz,
  weighted down above 16 kHz, light ridge regularisation) against `VlcEqModel`, so overlapping bands
  are accounted for and a narrow notch does not drag a whole octave down. On oratory1990's HD 600 it
  follows the correction to 0.89 dB RMS, against 1.24 dB for reading at the centres (pinned in
  `AutoEqTest`). The preamp then takes off the highest boost the model finds anywhere, rounded down.
- Applying a profile turns the equalizer on, saves it as "<model> (AutoEQ, <source>)" in
  `data/eq-presets.json`, and logs what VLC's equalizer then holds ("Equalizer: applied ...").
- Bands and preamp take typed values (`DbField`: a click selects the number, Enter applies, Escape
  reverts, comma or dot both work). Presets save by name and export or import as text
  (`EqPresetText`): Lyrenne's own `<freq> Hz: <dB> dB` lines with the preamp relative to unity, and
  also AutoEq or Equalizer APO text (`GraphicEQ:`, or `Filter N: ON PK|LSC|HSC ...`), fitted to the bands.
- The command palette has "Correct my headphones (AutoEQ)" (opens the equalizer with the search up,
  through `AutoEqLauncher`) and "Turn SponsorBlock skipping on or off".

### Testing it live

`SoundLiveRun` (test sources) plays a music video id through real VLC, muted, with the real App
rendered headless, and writes PNGs to `build/snapshots/`. It runs only with `-Psnapshots` and a
`build/snapshots/sound-live` file (first line: the video id; a line `undo X Y` clicks the snackbar's
Undo there). YouTube bot-checks signed-out requests from a busy IP, so a `build/snapshots/sound-live.m4a`
of the video's exact length, when present, plays in its place under the video's id (make one with
`ffmpeg -f lavfi -i "sine=duration=308.381" -c:a aac sound-live.m4a` for `qrO4YZeyl0I`). The segments,
VLC, the skip, Undo and the UI are all real; only the audio is not the song. UiSnapshot's `vlc` step
loads libvlc so the equalizer renders with real bands.
## Stage and Rewind

Two full-window views drawn over the app by `ui/stage/StageRewindHost.kt`, which App.kt places once
after its main Row (it lands in Main's Surface Box, so it overlays everything without any screen
knowing). Open/closed state is app-wide (`StageMode`, `RewindMode` in `StageMode.kt`) because the
player bar, Command Palette, Stats screen, Settings and the window key handler all open them.
`handleStageRewindKey` runs first in `AppShortcuts.preview`, so Esc closes Stage or Rewind before
it closes panels or pops the detail stack.

### Stage (`ui/stage/StageScreen.kt`, `StagePalette.kt`)

- **One frame loop, read in draw.** `rememberStageClock` keeps the animation time and a smoothed
  playback position (extrapolated between the player's ~200 ms reports, capped 1 s ahead, as
  LyricsPanel does). The background, the word fill, the art's breath and the progress bar read it
  inside draw lambdas or `graphicsLayer`, so a frame redraws and recomposes nothing. Lines change by
  recomposition once per line (`derivedStateOf` on the line index). The elapsed time recomposes once
  a second. A moving mouse does not recompose either: the last-movement time is a plain field.
- **Paced at about 30 fps** (`STAGE_FRAME_MS`). Compose Desktop redraws the whole window on every
  frame, and an unpaced Stage on this machine's monitor measured 45 to 58% of one core while
  playing (normal UI while playing: 1.6%). The loop only runs while playing and not minimised;
  paused, Stage measured 1.6% of one core, the same as any still window.
- **Karaoke fill is two draws of one layout**, not an AnnotatedString per frame: the line is drawn
  dim, then drawn again in full colour clipped to the sung part, with a soft gradient edge inside
  the word being sung (`fillPoint`, `wordSpans`; RTL fills from the right). No glow: a text shadow
  under a clip rect drew a visible band. Lines without word timing light up whole.
- **Lyrics come from `LyricsManager.state`**, which LyricsPanel keeps fetching for the current song
  even while hidden, and the per-song timing offset from `LyricsOffsets`. Romanization follows the
  same preference as the panel. Plain (unsynced) lyrics count as none: Stage then shows the cover as
  a slowly turning record with rings breathing out, the title and Up next.
- **Colours are hues only, brightness is Stage's.** `StagePalette` takes up to three hues from the
  cover (12-bin hue histogram, 64 px through the shared loader, cached). `StageColors` sets their
  brightness per theme so text stays legible over any cover: dark caps blobs at HSV value 0.36,
  light makes them pastel under near-black text. `StageRewindRenderTest` checks 4.5:1 for text and
  the dimmed next line against every blob, as if it were opaque.
- **Full screen** is `WindowPlacement.Fullscreen` on the main window's state, which Main.kt provides
  as `LocalMainWindowState` (null in UiSnapshot and tests, and then the button is not shown).
  `FullscreenKeeper` restores the previous placement however Stage closes.
- Controls fade after 2.8 s of a still mouse while playing, and the pointer is hidden with them.
- The player bar shows a Stage button only when the volume slider can give up 40 dp; on a narrow
  dock it moves into the overflow menu instead, so `dockLayout`'s budget is unchanged.

### Rewind (`rewind/`, `ui/rewind/`)

- **All local.** `RewindRepository` reads the Event table (two queries at the end of Lyrenne.sq) and
  `RewindCalculator` does the rest. Nothing is fetched; artist pictures are whatever the library
  already has, with an initial as the fallback.
- **A play belongs to when it started** (`endedAt - playTime`; the Event timestamp is written when
  a song stops). Periods are calendar dates in the system zone, start inclusive, end exclusive.
  The listening clock splits each play over the hours it ran through.
- **Ties** rank by plays, then listening time, then whichever was first played earlier in the
  period, then name. Streak ties go to the most recent run.
- **Discovery needs earlier history**: an artist first played in the period, at least twice, most
  listening time. With no plays before the period (All time, or a new user) there is no discovery
  card, because every artist would be "new" and it would just repeat the top artist.
- Cards with nothing to say are skipped (`RewindText.cards`); an empty period gets a friendly
  empty state that says what to do, and mentions paused listen history if that is why.
- **Cards are their own artwork**: dark, saturated, the same in both app themes, laid out in units
  of a 360 dp wide card so one composable is the on-screen card, the 1080x1920 story PNG and the
  1080x1080 square (density 3, `compact` trims lists on the square). Pictures are preloaded into
  bitmaps (`rememberRewindImages`), never AsyncImage, so `RewindExport.render` (an
  ImageComposeScene) is complete in one frame. White text and each accent are contrast-tested
  against the gradients and the cover glow.
- **The offer** (`RewindPrompt`, tested): Dec 1 to Jan 15 offers the year; the first week of other
  months offers last month if it had 5 hours of listening (a year needs 1 hour). Each offer's key
  is stored in `rewindPromptsSeen` the moment it shows, so it appears once ever, and the Settings
  switch turns them off. Not called "Wrapped", on purpose.

Look at both with `./gradlew :desktop:test --tests '*StageRewindSnapshot*' -Psnapshots`
(fixture data, writes `build/snapshots/stage-rewind/`).
## macOS

Lyrenne ships as `Lyrenne-X.Y.Z-macos-arm64.dmg` (Apple Silicon) and `-macos-x64.dmg` (Intel).
**Nobody on the project has a Mac**, so CI is the test bench: `.github/workflows/macos.yml` builds
both on GitHub's `macos-15` and `macos-15-intel` runners, and `release.yml` calls it to put the DMGs
in the draft. Its diagnostics artifact (`diag-macos-*`: logs, `codesign`/`spctl` output and a
screenshot of the running app) is how to look at a Mac build. Release runs skip it, because the
release job attaches every artifact of the run.

- **Packaging is a script, not Gradle**: `packaging/macos/package.sh` runs after
  `createDistributable`, like nfpm for Linux. It copies VLC.app's `lib/` and `plugins/` (VideoLAN's
  official 3.0.23 DMG, pinned by sha256) into `Lyrenne.app/Contents/app/resources/vlc`, ad-hoc signs
  every dylib and then the bundle, and builds the DMG with an Applications link and the first-open
  note (`packaging/macos/first-open.txt`). The architecture comes from the launcher (`lipo`), not
  `uname`: an x64 JDK under Rosetta builds an Intel app. Copy with `ditto`, never something that
  resets mtimes: `plugins.dat` records each plugin's mtime and size, and a mismatch means a full
  plugin probe on every launch.
- **VLC lookup**: `DesktopPlayer.findBundledVlc()` puts only `vlc/lib` on `jna.library.path`. vlcj's
  macOS discovery preloads libvlccore from there and sets `VLC_PLUGIN_PATH` to `lib/../plugins`
  itself. A dev run (`gradlew run`) has no bundle and falls back to an installed VLC.app.
- **Ad-hoc signed, no Developer ID.** Gatekeeper says it cannot verify the developer (Open Anyway in
  Privacy & Security; Control-click Open on macOS 14 and older), not "damaged", which is what an
  unsigned or half-signed download gets. Anything that writes inside the bundle after signing breaks
  the seal and brings "damaged" back, which is why data is never portable on macOS.
- **Data**: `~/Library/Application Support/Lyrenne`, downloads `~/Music/Lyrenne` (`AppPaths`).
- **Sign-in**: Firefox first, then Chrome, Edge, Brave, Chromium, Vivaldi, launched with
  `--use-mock-keychain`. Mac Chromium encrypts cookies (`v10`, AES-128-CBC, PBKDF2-SHA1 with 1003
  rounds) under a password from the login Keychain ("Chrome Safe Storage"), and reading that makes
  macOS ask the user. With the flag the password is the fixed `mock_password`, so no prompt. The real
  Keychain entry (`security find-generic-password`) is only the fallback when the mock key does not
  open a cookie. Closing a Mac browser's window does not quit it, so on macOS Lyrenne watches the
  profile for a `.youtube.com` session cookie (by name, nothing decrypted) and closes the browser
  itself with SIGTERM (`BrowserLoginHelper.closesBrowserItself`). Firefox's profile lock on macOS is
  an fcntl lock on `.parentlock`, not the `lock` symlink Linux has. `MacBrowserE2ETest` runs both
  browsers on the runner.
- **App menu and Dock** (`mac/MacIntegration.kt`): About, Settings (Cmd+,) and Quit handlers. Without
  a quit handler Cmd+Q calls System.exit and skips `quit()`, queue save included. The quit handler
  never calls `performQuit`/`cancelQuit`: `quit()` exits within 3 s and macOS waits for that. Clicking
  the Dock icon reopens a hidden window.
- **Shortcuts**: Cmd instead of Ctrl (`ui/ShortcutKeys.kt`), labels too. Cmd+Q is Quit on every Mac,
  so the queue is Cmd+U there. Cmd+W closes the window like its close button.
- **Menu bar item**: the AWT tray works as one. Any click opens the panel (the Mac convention), the
  panel opens below the click when the click is in the top half of the screen, and
  `MacIntegration.activate()` brings the app forward first: an inactive app's window never gets
  focus, so it would never lose it and the panel would never close.
- **Now Playing and media keys** (`mac/MacNowPlaying.kt`): mediasession-kt has no macOS backend, so
  this drives `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter` through the Objective-C runtime
  over JNA, with a runtime-made class whose methods are JNA callbacks. `objc_msgSend` is called with
  fixed arguments: a variadic call on Apple Silicon passes them on the stack, where the method does
  not look. Published on song change, play/pause and seeks only; the system extrapolates the rest.
  The cover goes in as `MPMediaItemArtwork`, which takes an Objective-C block: a global block literal
  is laid out by hand in JNA memory (`_NSConcreteGlobalBlock`, `BLOCK_IS_GLOBAL`, a JNA callback as
  its function). Its `CGSize` argument is two doubles, which both ABIs pass like two double
  arguments. `MacNowPlayingE2ETest` and the smoke test read all of it back.
- **Updater**: never self-updates (`canSelfUpdate` is false; replacing the app in place would break
  the signature) and never reaches the PowerShell path. The update card's button reads Download and
  opens the release page.
- **Not on macOS**: bundled ffmpeg (car export looks on PATH and in Homebrew's two prefixes), the
  Windows media identity, SleepGuard, MPRIS.
- **`--smoke-play <files>`** (`SmokeTest.kt`): plays local files through libvlc with no window and
  exits 0 only if each passed one second. `--require-bundled-vlc` fails if libvlc came from outside
  the bundle. It uses the normal data folder; CI and dev only.
## Install anywhere

Users asked for winget and Flathub (MetroList's own issues #4658 and #4723 ask the same of them), so
Lyrenne installs through the channels people actually use: portable ZIP, Scoop, winget, .deb/.rpm,
AppImage, Flatpak, on x86_64 and arm64 Linux. Per-channel facts and the submission steps are in
`packaging/README.md`; this is the design.

### Data placement is decided by who owns the folder (`InstallLocation.kt`)
- winget deletes its package folder on `winget upgrade` and `winget uninstall` and has no `persist`,
  which is why the 2026-07 winget submission was withdrawn: the app kept its library and sign-in in that
  folder. Now a copy in `\WinGet\Packages\` (either scope), in a folder a winget ARP entry names as its
  InstallLocation (a `--location` install), under Program Files, or in any folder a write probe says
  the user cannot write, keeps data in `%LOCALAPPDATA%\Lyrenne` and downloads in `~\Music\Lyrenne`.
- Everything else on Windows stays portable, byte for byte the old behaviour: an unzipped copy and
  Scoop (whose `persist: data, Downloads` depends on it) keep `data/` and `Downloads/` beside the exe.
- **Order matters**: winget, then Program Files, then the write probe. Program Files is managed even
  when writable, or an elevated run would flip the library to a different place. The probe runs only
  when the path decided nothing, so a portable start costs one temp file as before.
- The old fallback (an unwritable folder sent data to the working directory, plus a warning dialog) is
  gone: data now has one stable home, so the dialog had nothing left to warn about.
- `appDir` is now always the real app folder, never the working directory, so bundled VLC and ffmpeg
  are found from a read-only install too.
- No migration between layouts: a portable user who switches to winget copies `data\*` into
  `%LOCALAPPDATA%\Lyrenne` by hand. Nobody has a winget install yet, so there is nothing to migrate.
- `InstallLocationTest` pins every row of the table. Change a row only together with the table in
  `packaging/README.md`, and think about Scoop's `persist` first.

### Updates defer to whoever installed it (`update/UpdateRoute.kt`)
Self-update only where it owns the files: a portable Windows copy, and a /opt .deb/.rpm (pkexec). winget
says "Update with: winget upgrade Lyrenne" (rewriting its folder would desync winget's version record),
Program Files and read-only folders point at the release page, a Flatpak says "flatpak update", an
AppImage says to download the new one. Linux packages pick the asset for `os.arch`
(`AutoUpdater.linuxAssetName`), since releases now carry amd64 and arm64 .deb/.rpm.

**Release asset order protects old updaters.** Every Linux build up to 2.20.5 takes the first asset
ending in `.deb`/`.rpm`, whatever its arch. `release.yml` creates the draft with the x86_64 packages
and uploads the arm64 ones in a second step, so the x86_64 asset comes first in the API listing.
Do not merge those two steps.

### Bundled libVLC on Linux (AppImage, Flatpak)
The .deb/.rpm still depend on the distro VLC. The AppImage and Flatpak carry their own and pass its
folder in `LYRENNE_VLC_DIR` (`DesktopPlayer.findBundledVlc`). Both are audio-only cuts:
- AppImage (`packaging/appimage/build-appimage.sh`, in `debian:bookworm`): Debian's security-maintained
  VLC 3.0.x, an **allowlist** of plugins (HTTPS/DASH access, mp4/mkv/ogg/wav/flac demux, faad for AAC
  instead of libavcodec, opus/vorbis/flac/mpg123, gnutls, xml, the audio filters and outputs), and their
  libraries placed by linuxdeploy with rpaths. `AppRun` must not set `LD_LIBRARY_PATH`: sign-in spawns
  the user's browser, and it would load our libraries.
- Flatpak: libVLC built from source as a module, AAC through libavcodec from the runtime's codecs-extra extension (since 25.08 the runtime carries full FFmpeg itself; apps no longer add ffmpeg-full).
- Neither may carry VLC's `control/` (dbus) module: it would register a second MPRIS player.
- The MPRIS session is named `Lyrenne` now (`org.mpris.MediaPlayer2.Lyrenne`, the name the Flatpak
  owns). mediasession-kt's default identity was `mediasessionkt`, which is what desktop widgets showed.

### `Lyrenne --smoke-test` (`LaunchCheck.kt`)
Headless check for packaged builds: prints the layout decision and data dir, opens the database there,
loads libVLC through the player's own path, plays a generated tone until the position moves, and (Linux
with a session bus) brings up the MPRIS session. Each `--smoke-url <url>` also plays that URL; CI passes
an HTTPS file (proves the bundled TLS trusts the host's certificates) and AAC/Opus/MP3/FLAC files. Output
goes to stdout and `smoke-test.txt` in the data dir (the Windows exe has no console); exit code 0 = played.
It runs before crash reporting takes stderr and never opens a window or claims the single instance.

### What CI proves on every release (`release.yml`)
- Windows: the ZIP unzipped as a portable copy, into a fake winget folder and into Program Files lands
  each in the right layout; then a real `winget install --manifest`, the app run from PATH, and
  `winget uninstall` removing the package while `lyrenne.db` survives.
- AppImage: on clean containers without VLC (Ubuntu 22.04/24.04, Debian 12, Fedora, Arch on x86_64;
  Ubuntu 22.04/24.04, Fedora on aarch64) the smoke test plays everything and the window opens.
- arm64 .deb installs on the ubuntu-24.04-arm runner and passes the smoke test; the unit tests run there too.
- Flatpak: built with flatpak-builder from that commit's distributable, smoke-tested inside the sandbox,
  window opened, linted with `flatpak-builder-lint`.

## Version Management
- **Current version**: v2.20.5
- **One place**: `desktop/build.gradle.kts` → `lyrenneVersion = "X.Y.Z"`. `processResources` expands it into
  `version.properties`, which `AutoUpdater.CURRENT_VERSION` reads. `UpdateSignatureTest` pins that they agree

## Release Process (Step by Step)

**CI does it now** (`.github/workflows/release.yml`). Bump `lyrenneVersion`, commit, push, then
`git tag vX.Y.Z && git push origin vX.Y.Z` (or Actions → release → Run workflow). It builds the ZIP on
Windows, and .deb/.rpm, AppImage and tarball for x86_64 and arm64, launch-tests every one of them (see
"Install anywhere"), test-builds the Flatpak, and attaches the packages to a **draft** release. The
`winget-manifests` artifact of that run holds the manifests for the winget-pkgs PR. Since 2.16.1 the
`LYRENNE_SIGNING_KEY` Actions secret holds the key file's contents, so CI ships the `.sig` too. Chosen
deliberately (the user releases from several PCs); the cost is that a compromised GitHub account can sign
updates. If that secret is ever deleted, sign the draft's ZIP (and the .deb/.rpm the same way) before publishing:
```
gh release download vX.Y.Z --repo Doctordefector/Lyrenne -p "*.zip" -D build/rel
./gradlew :desktop:signPortableZip -PsignZip=build/rel/Lyrenne-X.Y.Z-portable.zip
gh release upload vX.Y.Z build/rel/Lyrenne-X.Y.Z-portable.zip.sig --repo Doctordefector/Lyrenne
```
Then write the notes and publish the draft. Never publish without the `.sig`. The manual steps below
still work if CI is down.

1. **Bump version**: `lyrenneVersion` in build.gradle.kts
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
   **Sign it** (required: a public key is set): `./gradlew :desktop:signPortableZip` writes
   `Lyrenne-X.Y.Z-portable.zip.sig`; upload it alongside the ZIP. With a key configured the updater
   refuses any release without a valid `.sig`
4. **Push code**: `git push origin main`
5. **Create GitHub release** with portable ZIP only:
   ```
   gh release create vX.Y.Z Lyrenne-X.Y.Z-portable.zip --title "Lyrenne vX.Y.Z" --notes "..."
   ```
6. **Package managers.** Scoop: nothing to do (the bucket's Excavator bumps itself daily). winget and
   Flathub live in someone else's repo, so each release is a PR there: `packaging/README.md` has the
   exact steps (`New-WingetManifest.ps1`, `update-sources.sh`). CI already rendered the winget manifests
   from the attached ZIP (`winget-manifests` artifact) and test-built the Flatpak.

## Distribution Strategy
Install anywhere, from one build per OS and arch. The full table, per-channel rules and the
submission steps are in `packaging/README.md`; the design is under "Install anywhere" below.
- **Windows**: the portable ZIP (also what Scoop and winget install). Still no EXE/MSI installer: do not
  build `packageExe`/`packageMsi`, only `createDistributable`. winget is a listing of the same ZIP.
- **Linux**: .deb/.rpm (distro VLC as a dependency), an AppImage that bundles libVLC, a Flatpak
  (libVLC built as a module), and the tarball for the Steam Deck plugin. x86_64 and arm64 each.
- **Release assets**: ZIP + .sig; per arch .deb/.rpm + .sig, `.AppImage`, `linux-<arch>.tar.gz`.
- **Data placement is decided per install** (`InstallLocation`): next to the app when portable,
  `%LOCALAPPDATA%\Lyrenne` when a package manager or admin owns the folder, XDG dirs on Linux.

## File Storage Paths
`AppPaths.kt` resolves everything; `InstallLocation.kt` decides the root once (table in
`packaging/README.md`). A portable copy (unzipped ZIP, Scoop) stores everything next to the executable:
- **Preferences**: `<app-dir>/data/preferences.properties`
- **Database**: `<app-dir>/data/lyrenne.db` (SQLDelight)
- **Credentials**: `<app-dir>/data/credentials.json` (plaintext, intentional)
- **Cache**: `<app-dir>/data/cache/`
- **Downloads**: `<app-dir>/Downloads/` (configurable via Settings folder picker)
- **Updates staging**: `<app-dir>/updates/` (with fallbacks to user.dir then temp)
- **No migration** from old `%APPDATA%` paths (removed in 2.9.4, see the rename section)
- **Windows, managed** (winget package folder, Program Files, any folder the user cannot write):
  data in `%LOCALAPPDATA%\Lyrenne` (same files, no `data/` level), downloads in `~\Music\Lyrenne`.
  winget deletes its package folder on upgrade and uninstall; data inside it would go with it
- **Linux**: data in `$XDG_DATA_HOME/lyrenne` (`~/.local/share/lyrenne`), downloads in `~/Music/Lyrenne`, unless a `portable` marker file sits next to the app.
  The Flatpak's XDG_DATA_HOME is `~/.var/app/io.github.doctordefector.Lyrenne/data`
- **CRITICAL**: a portable copy writes nothing to %APPDATA% or %LOCALAPPDATA% except the Start Menu
  shortcut described under Windows media identity. Never move a portable copy's data out of its folder:
  USB-stick users and Scoop's `persist: data, Downloads` depend on it.

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
- `gh` CLI at `C:\Program Files\GitHub CLI\gh.exe` (not in bash PATH, use full path), authenticated as Doctordefector
- **ALWAYS pass `--repo Doctordefector/Lyrenne` to every `gh release` command.** This repo has two
  remotes and bare `gh release list` resolves to `upstream` (MetrolistGroup/Metrolist), silently showing
  v13.x Android releases instead of our v2.x desktop ones
- Release assets come from `release.yml` only; do not hand-upload extra files (an extra `.deb`/`.rpm`
  ahead of the x86_64 ones would be picked up by old Linux updaters, see "Install anywhere")

## Auto-Updater System

### How it works (end to end)
1. **Check**: `AutoUpdater.checkForUpdates()` hits GitHub API (`/repos/.../releases/latest`), compares `CURRENT_VERSION` against the latest tag using semver
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
- `desktop/.../update/UpdateRoute.kt`: whether this copy may update itself at all. Only a portable
  Windows copy and a /opt .deb/.rpm do; winget, Program Files, Flatpak and AppImage copies show what to
  run instead ("Update with: winget upgrade Lyrenne") in Settings and the launch notification

### Telling the user, in the app

The launch check announces through a tray toast, which is not enough on its own: it fires ten
seconds after startup, dismisses itself, and leaves nothing behind, so anyone who looked away never
found out. Note that this was never a notifications-setting problem, despite looking like one.
`DesktopNotification.notify()` does not consult `notificationsEnabled`; only the song-change
observer does. The toast was firing for everybody and being missed by anybody.

`ui/components/UpdateBadge.kt` is the persistent half: a pill overlaid on the top-right of the
content Box in `App.kt`, clicking it clears the detail stack and goes to Settings. It collects
`AutoUpdater.updateState` directly, so no screen has to know it exists, and it renders nothing
unless an update is genuinely pending.

- It covers `Downloading` as well as `UpdateAvailable`. Starting a download and navigating away
  otherwise made the indicator vanish, which reads as the update having failed.
- It hides itself on Settings, since that is where it points.
- It only ever appears when `checkUpdatesOnLaunch` is on, because nothing else populates that
  state. Someone who turned that off has opted out of update checks entirely.

**To see it during development, temporarily lower `AutoUpdater.CURRENT_VERSION`** so the check finds
the live release newer. Revert before committing: that constant is one of the two version
declarations that must match, and shipping a build that thinks it is older than it is puts every
user in a permanent update loop. Verify with `git show HEAD:<file>`, not just the working tree.

Clicking the badge deep-links to the Updates section rather than dropping the user at the top of a
long list (7 groups, Updates near the end), which is barely better than not navigating. The jump finds its target **by key**
(`SETTINGS_SECTION_UPDATES` on that `item`), never by index: Settings is 57 items and someone will
eventually insert a section above Updates, at which point an index constant would silently land
people on the wrong setting. A `LazyColumn` only knows about composed items, so the effect walks
down until the key appears, with a bounded guard in case the key is ever removed.

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

## Linux port

Plan and rationale: `AUDIT_AND_LINUX_PLAN.md`. What is in place:

- **One OS check**: `Platform.isWindows/isLinux/isMac` in `AppPaths.kt`. Do not read `os.name` elsewhere.
- **Data**: XDG dirs (see File Storage Paths). Credentials and preferences are written atomically
  (temp file + rename); on POSIX `AppPaths.writeAtomic` makes them `rw-------`. A permission, not
  encryption: the plaintext rule stands.
- **VLC**: the .deb/.rpm use the distro `libvlc`, found by vlcj `NativeDiscovery`. The AppImage and the
  Flatpak bundle their own and name its folder in `LYRENNE_VLC_DIR` (`DesktopPlayer.findBundledVlc`).
  The Snap/Flatpak VLC *apps* cannot be used by a native install. Streaming prefers Opus there (Fedora's stock VLC may lack AAC).
- **Sign-in**: Firefox first (plaintext cookies), then the Chromium family with `--password-store=basic`,
  then Flatpak browsers (SteamOS has only those), then "Advanced: paste a cookie" (`PasteCookieSection`).
  A Flatpak browser can only see its own `~/.var/app/<id>`, so its login profile lives there; the
  exported launcher in `exports/bin` runs `flatpak run` and stays up while the browser does.
- **Tray**: none on Linux, deliberately. The AWT tray is XEmbed only: on Fedora (KDE, or
  GNOME with the AppIndicator extension) the icon showed but got no clicks, and `displayMessage`
  drew a Win95-style balloon for every song change. So `trayActive` is false, the minimize-to-tray
  switch is hidden, notifications go through `notify-send`, and MPRIS covers media controls.
  `SingleInstance` (socket in the data dir) makes a relaunch show the running copy's window instead
  of starting another. A real tray would be an SNI icon over dbus-java.
- **Opening URLs/folders**: always `Platform.open()`. AWT `Desktop.browse` silently fails on Linux
  without the GNOME libs ("View Release" did nothing), so Linux uses `xdg-open`.
- **Media**: `SystemMediaSession` (was WindowsMediaSession) publishes MPRIS via mediasession-kt.
  dbus-java is excluded only from Windows-host builds; `jdk.security.auth` is in the runtime for it.
- **Discord**: Unix sockets go through `SocketChannel` (RandomAccessFile cannot open them), searching
  `$XDG_RUNTIME_DIR` incl. Flatpak/Snap Discord subdirs.
- **Updater**: a copy the `lyrenne` package installed in /opt downloads the release's `.rpm`/`.deb` for its
  arch (`linuxAssetName`), checks its `.sig`, installs it with `pkexec dnf|rpm -U|apt-get`, and restarts once the old process is gone (SingleInstance
  would hand an earlier launch back to it). Anything else on Linux only links the release page. CI signs both packages.
  `-Djdk.lang.Process.launchMechanism=FORK` (build.gradle.kts jvmArgs) is load-bearing: the default spawn
  runs `/opt/lyrenne/lib/runtime/lib/jspawnhelper`, and once a package swaps /opt under a running copy the
  new helper's version no longer matches, so every spawn failed ("Failed to exec spawn helper", seen 2.20.0).
- **Packaging**: `packaging/linux/nfpm.yaml` builds .deb/.rpm from `createDistributable` with VLC as a
  dependency (x86_64 and arm64; release.yml rewrites `arch:`). AppImage and Flatpak: "Install anywhere". CI: `.github/workflows/linux.yml` (`-Pci` skips the network smoke tests).
  `fetchFfmpeg` and `packagePortableZip` only run on a Windows host.
- **Verified in CI** (every push): unit tests on Ubuntu; the .deb installs on Ubuntu and the .rpm on
  Fedora with their VLC dependencies, the app starts under Xvfb, loads the distro libvlc, and writes
  its DB to `~/.local/share/lyrenne`. `LinuxPlaybackE2ETest` plays a file through system VLC and drives
  it over MPRIS with `playerctl`; `LinuxBrowserE2ETest` runs real Firefox and Chrome through the login
  launch/lock/cookie-read path (signed out). **Not verifiable in CI**, needs a real desktop: browser sign-in,
  streamed playback (needs a session), MPRIS widgets, tray behaviour per desktop.

## Headless mode (Steam Deck plugin)

`Lyrenne --headless` runs the player with no window and serves a localhost JSON API
(`headless/Headless.kt`). It exists for the Decky Loader plugin (repo Doctordefector/Lyrenne-Decky),
whose Python backend starts it with `systemd-run --user` and whose Quick Access panel is the only UI.

- **Auth**: binds 127.0.0.1 only; a random token per run goes to `headless.json` in the data dir
  (mode 600, with port and pid) and every request must send it as `X-Lyrenne-Token`.
- **Runtime**: needs `jdk.httpserver` in the jlink module list. The 2.19.0 runtime lacked it, so
  `--headless` on that build dies with `NoClassDefFoundError: com/sun/net/httpserver/HttpServer`.
- **Single instance**: launching the windowed app pokes the headless copy, which quits; `runApp`
  tries the claim once more after 1.5 s. A windowed copy poked this way is simply shown twice.
- **SteamOS 3.9** ships libvlc 3.0.23 (https, mkv/mp4, opus, faad, ALSA output only), so nothing
  VLC needs bundling there. It ships no Java: the tarball's own runtime is used.
- **Shipping**: release CI adds `lyrenne-<v>-linux-x86_64.tar.gz` and `-aarch64.tar.gz` (the `createDistributable` folder);
  the plugin pins its sha256. `linux.yml` smoke-tests `--headless` (token accepted, 401 without).
- Anonymous playback is bot-checked by YouTube ("Sign in to confirm you're not a bot") on every
  client in the fallback chain, so the plugin must get the user signed in before anything plays.
