# Lyrenne — Audit, Linux Port Plan, and UI Improvements

*Written 2026-10-08 against `main` @ `24e95af` (v2.14.0) plus the uncommitted working tree
(10 modified files + `PlaylistSearch.kt`). `./gradlew :desktop:compileKotlin :desktop:test`
passes on that tree. The app was also run (`./gradlew :desktop:run`) and inspected visually.*

---

## Contents

1. [Summary](#1-summary)
2. [Linux port](#2-linux-port)
   - 2.1 [Every Windows-specific touchpoint](#21-every-windows-specific-touchpoint)
   - 2.2 [Blocker 1 — Sign-in (Edge/Chromium + DPAPI)](#22-blocker-1--sign-in)
   - 2.3 [Blocker 2 — VLC](#23-blocker-2--vlc)
   - 2.4 [Data location](#24-data-location)
   - 2.5 [Tray and minimize-to-tray](#25-tray-and-minimize-to-tray)
   - 2.6 [Media controls (MPRIS)](#26-media-controls-mpris)
   - 2.7 [Dark-mode detection](#27-dark-mode-detection)
   - 2.8 [Auto-updater](#28-auto-updater)
   - 2.9 [Build and packaging](#29-build-and-packaging)
   - 2.10 [UI text and settings](#210-ui-text-and-settings)
   - 2.11 [Already portable](#211-already-portable)
   - 2.12 [Phased plan](#212-phased-plan)
   - 2.13 [Test matrix](#213-test-matrix)
3. [Flaws (all platforms)](#3-flaws-all-platforms)
4. [UI and design improvements](#4-ui-and-design-improvements)
5. [Documentation drift](#5-documentation-drift)
6. [Master action plan](#6-master-action-plan)
7. [Open questions](#7-open-questions)

---

## 1. Summary

Lyrenne is in good shape: well-documented, tests green, careful about secrets in release
artifacts. The Linux port is blocked by **two** things, and both are fixable without
rewriting anything:

| Blocker | Why it fails on Linux | Fix in one line |
|---|---|---|
| **Sign-in** | Browser lookup is hard-coded Windows paths; cookie key is unwrapped with Windows DPAPI via PowerShell; cipher is AES-GCM (Linux Chromium uses AES-CBC). | Add a Firefox login path (cookies are **unencrypted** in `cookies.sqlite`), plus Chromium with `--password-store=basic` (fixed, well-known key). |
| **VLC** | Bundled-VLC lookup only checks `libvlc.dll`; error text says "install VLC (64-bit)". | Use the distro's `libvlc` — vlcj's `NativeDiscovery` already finds it. Declare it as a package dependency, don't bundle. |

Everything else Linux needs (data path, tray fallback, MPRIS, theme, updater, packaging) is
small and listed below with exact locations.

Outside the port, the most important flaws are: **non-atomic preference writes performed on
every volume-slider tick**, **an updater with no integrity check**, and **back navigation
that refetches and loses scroll position**.

---

## 2. Linux port

### 2.1 Every Windows-specific touchpoint

| Area | File | Linux behaviour today | Severity |
|---|---|---|---|
| Browser discovery | `auth/BrowserLoginHelper.kt:15` | Finds nothing → "No browser found" | **Blocker** |
| Cookie key (DPAPI via `powershell`) | `auth/BrowserCookieExtractor.kt:168-205` | `Local State` has no `encrypted_key` → "Failed to decrypt" | **Blocker** |
| Cookie cipher (AES-GCM) | `auth/BrowserCookieExtractor.kt:207` | Wrong algorithm for Linux v10/v11 | **Blocker** |
| Locked-file copy (`robocopy`) | `auth/BrowserCookieExtractor.kt:124` | Only reached on copy failure; Linux has no mandatory locks so normally unused | Low |
| Handoff poll (`SingletonLock.exists()`) | `auth/BrowserLoginHelper.kt:~180` | On Linux `SingletonLock` is a *dangling symlink*; `File.exists()` follows it and returns `false` while the browser is still running | Medium |
| Browser name / error copy | `ui/screens/OnboardingScreen.kt:267, 414` | Says Edge/Chrome/Brave only | Low |
| VLC lookup | `playback/DesktopPlayer.kt:357` | Checks `libvlc.dll` only → falls through to system discovery (OK) | Low |
| VLC error text | `playback/DesktopPlayer.kt:~345` | "install VLC media player (64-bit)" — Windows wording | Low |
| Data dir next to exe | `AppPaths.kt` | `/opt/...` or AppImage mount is read-only → falls back to `user.dir` (random) | **High** |
| Minimize-to-tray | `Main.kt:378` | GNOME has no tray → window hidden, **no way to restore it** | **High** |
| Media session | `integration/WindowsMediaSession.kt:43` | Gated to Windows; D-Bus dep excluded in `build.gradle.kts` | Medium |
| Media keys (VK 176–179) | `media/MediaKeyHandler.kt:68` | AWT doesn't deliver these on Linux | Covered by MPRIS |
| Theme detection | `ui/theme/Theme.kt:265` | Reads `gtk-theme` (legacy); modern GNOME uses `color-scheme` | Low |
| Auto-updater | `update/AutoUpdater.kt` | PowerShell + robocopy script | Medium (must disable) |
| Start-menu shortcut / AppUserModelID | `integration/WindowsStartMenuShortcut.kt`, `WindowsAppIdentity.kt` | Already no-op off Windows | None |
| Sleep guard | `download/SleepGuard.kt` | Already no-op off Windows | None (optional: `systemd-inhibit`) |
| Packaging tasks | `desktop/build.gradle.kts` | `fetchFfmpeg` (win64 zip), `patchPortableIcon` (Resource Hacker), `packagePortableZip` (7-Zip path, strips non-Windows sqlite natives) | **Blocker for packaging** |
| Target formats | `desktop/build.gradle.kts` | `Msi, Exe` only | Blocker for packaging |
| Settings "Windows" section | `ui/screens/SettingsScreen.kt:718` | Shown on Linux | Low |
| Onboarding copy | `ui/screens/OnboardingScreen.kt:189` | "A YouTube Music player for Windows" | Low |
| `os.name` checks | 8+ separate copies across files | Inconsistent (`contains("win")` vs `startsWith("Windows")`) | Cleanup |

---

### 2.2 Blocker 1 — Sign-in

#### How it works today

1. `BrowserLoginHelper.findBrowserExecutable()` looks for `msedge.exe`, `chrome.exe`,
   `brave.exe` under `Program Files`, `Program Files (x86)` and `%LOCALAPPDATA%`.
2. Launches it with `--user-data-dir=<data>/login-profile` (fresh, throwaway profile) at
   `https://music.youtube.com`.
3. Waits for the browser process to exit (or polls the profile's lock files if the launcher
   handed off and exited early).
4. `BrowserCookieExtractor.extractChromiumCookies()`:
   - Reads `Local State` → `os_crypt.encrypted_key` → strips `DPAPI` prefix → unwraps with
     `ProtectedData.Unprotect` via a spawned `powershell`.
   - Copies `Default/Network/Cookies` to a temp file, reads `cookies` table, decrypts each
     `v10`/`v11` value with AES-256-GCM.
   - Strips the 32-byte domain-binding hash Chromium 128+ prepends.
5. Builds the `Cookie:` header, saves to `credentials.json`, prunes the profile.

#### Why it fails on Linux

| Step | Problem |
|---|---|
| 1 | Only Windows paths. |
| 4a | Linux Chromium has **no** `encrypted_key` in `Local State`. The key comes from the desktop keyring (`v11`) or a hard-coded password (`v10`). `decryptMasterKey()` returns `null`. |
| 4b | Linux Chromium uses **AES-128-CBC**, not AES-256-GCM. |
| 3 | `SingletonLock` on Linux is a symlink to `hostname-pid` — a target that never exists. `File.exists()` follows symlinks → always `false`. The handoff poll would read cookies too early. |

#### Plan A (recommended first): Firefox

Firefox stores cookies in **plain text** in `<profile>/cookies.sqlite` on every OS. No key,
no keyring, no DPAPI, no app-bound (v20) encryption. It is the default browser on Ubuntu,
Fedora, Debian, Mint, openSUSE and most others.

**Launch:**

```kotlin
ProcessBuilder(
    firefox.absolutePath,
    "-profile", profileDir.absolutePath,
    "-no-remote",            // separate instance, so process.isAlive tracks *this* window
    "https://music.youtube.com"
).start()
```

**Read:**

```kotlin
// cookies.sqlite is WAL-mode: copy -wal alongside it, exactly as the Chromium path already does.
"""SELECT name, value, host FROM moz_cookies
   WHERE host LIKE '%youtube.com' OR host LIKE '%.google.com'
   ORDER BY CASE WHEN host LIKE '%youtube.com' THEN 1 ELSE 2 END"""
```

Then feed the `name → value` map into the existing `buildCookieResult()` — no other change.

**Discovery (Linux):** first hit on `PATH` of `firefox`, `firefox-esr`, `librewolf`
(LibreWolf is a Firefox fork with the same profile format).

**Snap Firefox (Ubuntu's default!):** snap confinement forbids hidden directories in `$HOME`
and anything outside `$HOME`. If `/snap/bin/firefox` exists (Ubuntu's `/usr/bin/firefox` is a
wrapper that runs the snap), put the login profile at:

```
~/snap/firefox/common/lyrenne-login
```

That directory is writable by the snap and readable by Lyrenne (same user).

**Flatpak Firefox:** `flatpak run org.mozilla.firefox -profile ...` cannot see paths outside
its sandbox without `--filesystem` overrides. Skip Flatpak browsers in v1; fall back to
Plan B or C.

**Windows bonus:** Firefox on Windows (`C:\Program Files\Mozilla Firefox\firefox.exe`) works
identically. Adding it as a fallback when Edge is missing removes Lyrenne's dependency on
Chromium's encryption scheme, which has already broken once (v20 app-bound keys).

#### Plan B: Chromium-family on Linux

Discover on `PATH`: `microsoft-edge`, `microsoft-edge-stable`, `google-chrome`,
`google-chrome-stable`, `chromium`, `chromium-browser`, `brave-browser`, `vivaldi`.

Launch with one extra flag:

```
--password-store=basic
```

This tells Chromium not to use GNOME Keyring / KWallet. Cookies are then written as `v10`,
encrypted with a key derived from the fixed password `peanuts`:

```kotlin
private val linuxV10Key: ByteArray by lazy {
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        .generateSecret(PBEKeySpec("peanuts".toCharArray(), "saltysalt".toByteArray(), 1, 128))
        .encoded
}

private fun decryptLinuxV10(encrypted: ByteArray): String? {
    if (encrypted.size <= 3 || String(encrypted, 0, 3) != "v10") return null
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(linuxV10Key, "AES"),
        IvParameterSpec(ByteArray(16) { ' '.code.toByte() })   // 16 spaces
    )
    val plain = cipher.doFinal(encrypted, 3, encrypted.size - 3)
    return stripBindingHash(plain)   // existing helper — Chromium 130+ prepends SHA-256(host)
}
```

`extractChromiumCookies()` then branches: on Linux skip `decryptMasterKey()` entirely and use
`decryptLinuxV10` per cookie. A `v11` prefix means `--password-store=basic` was ignored (e.g.
a distro wrapper dropped flags) — report that as a clear error rather than "failed to decrypt".

Snap Chromium has the same hidden-directory restriction as snap Firefox: use
`~/snap/chromium/common/lyrenne-login`.

**Lock-file fix** (applies to the handoff poll on all OSes):

```kotlin
val singletonLock = profileDir.toPath().resolve("SingletonLock")
val running = Files.exists(singletonLock, LinkOption.NOFOLLOW_LINKS) || File(profileDir, "lockfile").exists()
```

#### Plan C: paste-cookie fallback

For Flatpak-only systems, immutable distros, or anyone whose browser refuses the flags:
a small "Advanced: paste cookie" field in the login screen. Instructions: open
music.youtube.com, DevTools → Network → any request → copy the `cookie` request header.
Validate it contains `SAPISID` or `__Secure-3PAPISID`, then save through the same path as a
browser login. ~20 lines; works on every platform forever.

#### Code shape

Keep it minimal — one data class, no interface hierarchy:

```kotlin
enum class BrowserKind { CHROMIUM, FIREFOX }
data class LoginBrowser(val kind: BrowserKind, val exe: File, val name: String, val profileDir: File)
```

- `findBrowserExecutable(): File?` → `findLoginBrowser(): LoginBrowser?` (OS-aware order).
- `loginWithBrowser()` builds args from `kind`.
- `readCookiesFromProfile()` dispatches: `FIREFOX` → new `extractFirefoxCookies(profileDir)`;
  `CHROMIUM` → existing `extractChromiumCookies()` (which branches internally on OS).
- `OnboardingScreen.kt:267` duplicates the browser-name logic — make it use `LoginBrowser.name`.
- `pruneLoginProfile()` keeps `Local State` + `Default/Login Data*` (Chromium layout). For a
  Firefox profile keep `logins.json` + `key4.db` instead, delete the rest.

**Discovery order:**

| OS | Order |
|---|---|
| Windows | Edge → Chrome → Brave → **Firefox** (new fallback) |
| Linux | **Firefox** (native, then snap) → Edge → Chrome → Chromium → Brave → Vivaldi |
| macOS (future) | Firefox → Chrome (Keychain-backed, harder) |

#### Acceptance criteria

- [ ] Ubuntu 24.04 (snap Firefox): sign-in completes, `credentials.json` written, library syncs.
- [ ] Fedora (native Firefox): same.
- [ ] Linux with only Chrome installed: sign-in completes via `--password-store=basic`.
- [ ] Windows with Edge: unchanged behaviour (regression check).
- [ ] Windows with Firefox only: sign-in completes.
- [ ] No browser: error names the browsers Lyrenne looks for on *this* OS and offers paste fallback.
- [ ] Login profile pruned correctly for both layouts; cleared on sign-out.

#### Tests

- Unit: `decryptLinuxV10` against a fixture blob (encrypt a known value with the peanuts key in
  the test, decrypt, compare — with and without the 32-byte host hash prefix).
- Unit: Firefox reader against a tiny fixture `cookies.sqlite` created in the test with
  `moz_cookies(name, value, host)`.
- Unit: `SingletonLock` symlink detection (create a dangling symlink in a temp dir; skip on
  Windows if symlink creation isn't permitted).
- Manual: acceptance matrix above.

---

### 2.3 Blocker 2 — VLC

#### How it works today

`DesktopPlayer.initializeVlc()`:

1. `findBundledVlc()` checks, in order: `compose.application.resources.dir/vlc`,
   `resources/windows-x64/vlc`, `user.dir/vlc` — accepting a dir only if it contains
   **`libvlc.dll`**.
2. If found, prepends it to `jna.library.path` and sets the *Java property*
   `VLC_PLUGIN_PATH`.
3. Runs vlcj `NativeDiscovery().discover()`.
4. If discovery fails, prepends to `java.library.path` — and stops.

#### Linux behaviour

- Step 1 returns `null` (no `.dll`), so step 3 runs against the system. vlcj's Linux
  strategy searches `/usr/lib`, `/usr/lib64`, `/usr/local/lib`, `/usr/lib/x86_64-linux-gnu`
  etc. and **finds a distro-installed `libvlc.so.5` without any changes**.
- If VLC isn't installed, the user sees "VLC not found. Please install VLC media player (64-bit)."
- **Snap VLC and Flatpak VLC are not discoverable** (libraries live under `/snap/...` or inside
  the Flatpak runtime).

#### Bugs found in this function (all platforms)

- `System.setProperty("VLC_PLUGIN_PATH", ...)` has no effect — libvlc reads the *environment
  variable*. It works on Windows today only because libvlc finds `plugins/` next to
  `libvlc.dll` by itself (and vlcj's discovery sets the plugin path when it discovers a dir).
  Delete the line.
- The `java.library.path` fallback is dead: the JVM caches that property at startup, and the
  code never retries `discover()` afterwards. Delete it.

#### Plan

1. **Don't bundle VLC on Linux.** Bundling libvlc + ~300 plugins + their transitive `.so`
   deps (libav*, gnutls, libxml2…) into an AppImage is fragile and large. Depend on the distro
   package instead.
2. Platform-aware lookup:
   ```kotlin
   val libName = if (isWindows) "libvlc.dll" else "libvlc.so"   // Linux: system discovery only
   ```
   On Linux, skip the bundled-dir step entirely and go straight to `NativeDiscovery`.
3. Platform-aware error text:
   - Windows (bundled build): "VLC failed to load from the app folder. Re-extract the ZIP."
   - Linux: "Lyrenne needs VLC. Install it with your package manager, e.g.
     `sudo apt install vlc` / `sudo dnf install vlc` / `sudo pacman -S vlc`.
     The Snap and Flatpak versions of VLC can't be used."
4. Package dependencies:

| Format | Dependency |
|---|---|
| `.deb` (Debian/Ubuntu/Mint) | `libvlc5, vlc-plugin-base` (or simply `vlc`) |
| `.rpm` (Fedora) | `vlc` (official repos ship a codec-limited build; RPM Fusion has the full one) |
| AUR (Arch) | `vlc` — Arch split plugins into separate packages in 2025; depend on the set that includes http/https access, adaptive streaming and the Opus/AAC decoders (or `vlc-plugins-all`) |
| AppImage | Cannot declare deps — show the install hint at first launch |

5. **Codec check.** YouTube Music serves Opus/WebM (itag 251) and AAC/M4A (itag 140). Opus is
   available everywhere. Verify AAC on Fedora's stock `vlc`; if missing, prefer itag 251 on Linux.

#### Alternative considered (not recommended now)

Replace VLC on Linux with GStreamer (`gst1-java-core`) or libmpv via JNA. Both are present
on almost every desktop install, but it means a second playback backend implementing EQ,
crossfade, speed, normalize, skip-silence and the volume taper. Revisit only if VLC
packaging becomes a real support burden.

#### Acceptance criteria

- [ ] Ubuntu 24.04 with `apt install vlc`: streaming, local files, EQ, speed, normalize, skip silence all work.
- [ ] Fedora stock `vlc`: streaming works (confirm AAC or force Opus).
- [ ] VLC missing: clear Linux-specific message, app still navigable.
- [ ] Windows bundled VLC: unchanged.

---

### 2.4 Data location

`AppPaths.getAppDirectory()` puts `data/` next to the jar's grandparent directory, falling back
to `user.dir` when it isn't writable. On Linux `.deb`/`.rpm` install to `/opt/lyrenne`
(root-owned) and an AppImage mounts read-only, so data would land in whatever directory the
user launched from.

**Plan:**

```kotlin
val dataDir: File by lazy {
    val dir = when {
        isWindows || File(appDir, "portable").exists() -> File(appDir, "data")   // current behaviour
        else -> File(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "lyrenne")
    }
    dir.mkdirs(); dir
}
```

- Windows: unchanged (portable-only distribution).
- Linux: `~/.local/share/lyrenne/` (preferences, DB, credentials, cache).
- Linux default downloads: `~/Music/Lyrenne` (`xdg-user-dir MUSIC` if available).
- Updates dir: not used on Linux (updater disabled).
- Credentials file on Linux: create with `rw-------` (`PosixFilePermissions`), since `~/.local/share`
  may be readable by other users on some setups. This is a file *permission*, not encryption — the
  CLAUDE.md rule "cookies MUST be stored in plaintext" still holds; the content is unchanged.

---

### 2.5 Tray and minimize-to-tray

**Bug (Linux, and Windows if tray init fails):** `Main.kt:378` hides the window on close when
`prefs.minimizeToTray` is true, regardless of whether a tray icon exists.
`DesktopNotification.initialize()` already logs "System tray not supported" and returns —
GNOME (the default on Ubuntu/Fedora) has no AWT-compatible tray. Result: user clicks ×,
window disappears, music keeps playing, **no way to bring it back** except killing the process.

**Fix (one condition):**

```kotlin
if (prefs.minimizeToTray && DesktopNotification.trayActive) { windowVisible = false } else { /* exit */ }
```

Expose `trayActive` from `DesktopNotification` (true only after `SystemTray.add()` succeeded).
Also hide the "Minimize to tray" switch in Settings when the tray is unavailable.

Notifications on Linux: AWT `TrayIcon.displayMessage` needs the tray too. Optional upgrade:
`notify-send` via `ProcessBuilder` when the tray is absent (present on virtually every desktop).

---

### 2.6 Media controls (MPRIS)

`dev.toastbits:mediasession` is cross-platform: Windows SMTC **and Linux MPRIS** via
D-Bus. Lyrenne disables the Linux half twice:

- `build.gradle.kts` excludes `com.github.hypfvieh` (dbus-java).
- `WindowsMediaSession.kt:43` returns early when not on Windows.

**Plan:** remove the exclude on Linux builds (or always — it's a few hundred KB), lift the
`onWindows` gate, and rename the object to `SystemMediaSession`. The existing callback wiring
(`onPlay`, `onNext`, `onSetPosition`, …) is already platform-neutral. This gives GNOME/KDE
media widgets, lock-screen controls, `playerctl`, and hardware media keys (which replace the
Windows-only VK 176–179 handling in `MediaKeyHandler`).

Keep `WindowsStartMenuShortcut` / `WindowsAppIdentity` Windows-only. On Linux the MPRIS
identity comes from the `.desktop` file name (`lyrenne.desktop`) — ship one in the packages.

---

### 2.7 Dark-mode detection

`Theme.kt:265` runs `gsettings get org.gnome.desktop.interface gtk-theme` and checks for
"dark". Since GNOME 42 the dark preference is `color-scheme` (`'prefer-dark'`) and the GTK
theme name is often just `Adwaita`.

**Plan:** check `color-scheme` first, fall back to `gtk-theme`. For KDE, read
`~/.config/kdeglobals` `[General] ColorScheme` (contains "Dark"). Best long-term option:
the XDG Desktop Portal `org.freedesktop.appearance color-scheme` over D-Bus — becomes cheap
once dbus-java is on the classpath for MPRIS (2.6).

Also see Flaw 5: the check runs synchronously during composition.

---

### 2.8 Auto-updater

`AutoUpdater` downloads a ZIP from GitHub and applies it through a generated PowerShell script
using `robocopy`. None of that exists on Linux, and overwriting a package-managed install
under `/opt` would be wrong anyway.

**Plan:** on Linux, `checkForUpdates()` may still query GitHub and show "Version X is
available" with a link to the release page, but `downloadAndInstall()`/`applyUpdate()` are
disabled. Package managers (apt repo / COPR / AUR) do the installing.

---

### 2.9 Build and packaging

| Item | Change |
|---|---|
| `targetFormats(Msi, Exe)` | Per OS: Windows → none needed (portable ZIP via `createDistributable`); Linux → `Deb, Rpm` (+ `AppImage` if wanted). Compose builds only the current OS's formats. |
| `linux { }` block | `packageName = "lyrenne"`, `iconFile = icon.png`, `debMaintainer`, `menuGroup = "AudioVideo"`, `appCategory = "Audio"`, `shortcut = true`. |
| `.deb` / `.rpm` deps | Compose doesn't expose a "Depends" field directly; either post-process the package (`dpkg-deb -R`, edit `DEBIAN/control`, rebuild) or build packages with `nfpm`/`fpm` from `createDistributable` output. `nfpm` is simplest: one YAML with `depends: [libvlc5, vlc-plugin-base]`. |
| `appResourcesRootDir` (`resources/windows-x64/...`) | Compose already picks `resources/<os>-<arch>/` per platform; a `resources/linux-x64/` dir can stay empty (no VLC, no ffmpeg). |
| `fetchFfmpeg` | Guard with `onlyIf { isWindows }`. Linux uses `ffmpeg` from `PATH` (`CarExport.findFfmpeg()` already falls back to `PATH`); add `ffmpeg` as a *recommended* package dep. |
| `patchPortableIcon`, `packagePortableZip` | Windows-only; guard with `onlyIf`. |
| sqlite-jdbc native stripping | Windows ZIP keeps only `Windows/x86_64`. A Linux package should keep only `Linux/x86_64` (+ `Linux/aarch64` if building ARM). Parametrise the kept prefix. |
| CI | There is no workflow in `.github/`. Add one job on `ubuntu-latest`: `./gradlew :desktop:test :desktop:packageDeb :desktop:packageRpm`, upload artifacts. Keep Windows releases manual as today. |
| JVM modules | `java.sql, java.naming, java.net.http, jdk.unsupported` are fine on Linux. Add `jdk.security.auth` only if dbus-java needs Unix-socket credentials (check at runtime). |
| `.desktop` file | Compose generates one for `.deb`/`.rpm`. Make sure `StartupWMClass` matches the AWT window class (set `-Dsun.awt.X11.XToolkit.appName`/`awt.appName`? — verify) so the dock groups the window with its launcher. |

---

### 2.10 UI text and settings

- `OnboardingScreen.kt:189` — "A YouTube Music player for Windows" → "for your desktop", or OS-specific.
- `OnboardingScreen.kt:414` — browser list per OS (see 2.2).
- `SettingsScreen.kt:718` — show the "Windows" section only on Windows.
- "Minimize to tray" switch hidden when no tray (2.5).
- "Prevent sleep while downloading" (if surfaced) — hide on Linux, or implement with
  `systemd-inhibit --what=sleep --why="Lyrenne download" sleep infinity` held as a child process.
- `CarExport.FFMPEG_MISSING` says "put ffmpeg.exe next to Lyrenne.exe" → Linux: "install ffmpeg".

---

### 2.11 Already portable

No work needed:

- Discord RPC (`/tmp/discord-ipc-N`, plus `$XDG_RUNTIME_DIR` — **verify**: modern Discord puts the
  socket in `$XDG_RUNTIME_DIR/discord-ipc-N`; Flatpak Discord in `$XDG_RUNTIME_DIR/app/com.discordapp.Discord/`. Add both to the search).
- Last.fm, Listen Together (OkHttp WebSocket), innertube, lyrics providers, Shazam recognition
  (Java Sound capture works with PulseAudio/PipeWire).
- SQLDelight / sqlite-jdbc (ships Linux natives — just don't strip them).
- `NativeFileDialog` (`java.awt.FileDialog` → GTK dialog on Linux). Folder pickers use Swing.
- `WindowsAppIdentity`, `WindowsStartMenuShortcut`, `SleepGuard` — already no-ops off Windows.

---

### 2.12 Phased plan

**Phase 0 — groundwork (Windows-safe, ship in a normal release)**

1. One `isWindows`/`isLinux` definition (e.g. in `AppPaths.kt`) replacing the 8+ `os.name` checks.
2. Fix the tray-close bug (2.5) — it affects Windows too when the tray fails.
3. Delete the dead `VLC_PLUGIN_PATH` property and `java.library.path` fallback (2.3).
4. `SingletonLock` NOFOLLOW_LINKS fix (2.2).
5. Add Firefox as a Windows fallback login browser (2.2 Plan A) — exercises the Firefox reader on
   the platform you can test today.

*Exit criterion:* Windows behaviour unchanged; tests green; Firefox sign-in works on Windows.

**Phase 1 — runs from source on Linux**

1. XDG data dir (2.4).
2. Linux VLC lookup + error text (2.3).
3. Updater disabled on Linux (2.8).
4. Theme detection (2.7).
5. Hide Windows-only settings, fix copy (2.10).
6. Gradle task guards so `./gradlew :desktop:run` works on Linux (2.9).

*Exit criterion:* `./gradlew :desktop:run` on Ubuntu 24.04 starts, every screen navigable, a
local audio file plays through system VLC, no crashes, data in `~/.local/share/lyrenne`.
(Streaming needs a signed-in session — unsigned playback returns HTTP 403 — so it is verified in
Phase 2.)

**Phase 2 — sign-in on Linux**

1. Firefox native + snap (2.2 Plan A).
2. Chromium family with `--password-store=basic` (Plan B).
3. Paste-cookie fallback (Plan C).
4. Unit tests listed in 2.2.

*Exit criterion:* acceptance list in 2.2 passes.

**Phase 3 — desktop integration**

1. MPRIS (2.6).
2. `notify-send` fallback for notifications (2.5).
3. Discord socket paths (2.11).

**Phase 4 — packaging and release**

1. `.deb` and `.rpm` with VLC dependency (2.9); optional AppImage.
2. Linux CI job.
3. README: Linux install section; CLAUDE.md: Linux notes.
4. Optional: AUR `PKGBUILD`, COPR repo, apt repo.

**Effort guide** (one developer familiar with the code): Phase 0 ≈ 1 day, Phase 1 ≈ 1–2 days,
Phase 2 ≈ 2 days, Phase 3 ≈ 1 day, Phase 4 ≈ 1–2 days.

---

### 2.13 Test matrix

| Distro | Desktop | Session | Browser for login | VLC source | Notes |
|---|---|---|---|---|---|
| Ubuntu 24.04 | GNOME | Wayland | **snap** Firefox | apt `vlc` | Primary target; no tray |
| Fedora 41+ | GNOME | Wayland | native Firefox | Fedora `vlc` | Check AAC |
| Kubuntu / KDE neon | Plasma 6 | Wayland | Firefox / Chrome | apt | Tray **works** here |
| Arch | any | X11 | Chromium | `vlc` + plugins | Tests Plan B |
| Linux Mint 22 | Cinnamon | X11 | Firefox (deb) | apt | Tray works |
| WSLg (Windows) | — | — | — | apt | Quick smoke tests from this machine |

Compose Desktop on Wayland runs through XWayland — fine, but check HiDPI scaling
(`GDK_SCALE`, `-Dsun.java2d.uiScale`) on a 4K screen.

---

## 3. Flaws (all platforms)

Ordered by impact. Each has location, problem, consequence, fix, effort (S < 1h, M < ½ day, L > ½ day).

### F1. Preference writes are non-atomic, unsynchronised, and happen per slider tick — **High, S**

- **Where:** `settings/PreferencesManager.kt:273` (`prefsFile.outputStream().use { props.store(...) }`),
  called from every setter; `ui/components/MiniPlayer.kt:486` calls `setVolume` from
  `Slider.onValueChange`.
- **Problem:** dragging the volume slider rewrites the whole properties file dozens of times a
  second on the UI thread. Writes truncate-then-write in place. `savePreferences()` is also
  called from IO threads (Listen Together, media keys) with no lock.
- **Consequence:** UI stutter on slow disks; a crash or power loss mid-write leaves a truncated
  file → defaults load → Last.fm session key, download path, EQ and every other setting lost.
  Two concurrent writers can interleave.
- **Fix:**
  ```kotlin
  @Synchronized private fun savePreferences() {
      ...
      val tmp = File(prefsFile.parentFile, prefsFile.name + ".tmp")
      tmp.outputStream().use { props.store(it, "Lyrenne Preferences") }
      Files.move(tmp.toPath(), prefsFile.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
  }
  ```
  And in the volume slider: update the player and an in-memory value in `onValueChange`,
  persist in `onValueChangeFinished` (the tray slider at `TrayPanel.kt:130` already has one).
  Apply the same temp+move pattern to `credentials.json` writes in `AuthManager` if not already.

### F2. Auto-updater has no integrity check — **High (security), M**

- **Where:** `update/AutoUpdater.kt` (`requireTrustedUrl` at ~398).
- **Problem:** downloads are restricted to HTTPS on `github.com`/`*.githubusercontent.com`, but
  the ZIP is not verified. Anyone who gains write access to the GitHub repo (stolen token,
  compromised account) can push a release that every install fetches and runs.
- CLAUDE.md ("Update download: HTTPS and GitHub only") already records this as a known gap and
  rightly rejects a bare checksum: a hash published from the same origin doesn't help against a
  compromised account. So:
- **Fix:**
  - Sign the ZIP with **minisign**/Ed25519; embed the public key in the app; verify the
    detached signature before extracting. The signing key stays offline, so a compromised
    GitHub account can't ship code. Verification is ~30 lines with JDK 15+ `Signature.getInstance("Ed25519")`.
- Add a step to the release process in CLAUDE.md.

### F3. App directory writability check is unreliable — **Medium, S**

- **Where:** `AppPaths.kt:58` (`appDir.canWrite()`), fallback to `user.dir`.
- **Problem:** on Windows, `File.canWrite()` on a directory only reflects the read-only
  attribute, not ACLs. Unzipped into `C:\Program Files\` it returns true, then `mkdirs()`
  silently fails. When it does return false, data goes to the *working directory*, which
  depends on how the app was launched.
- **Fix:** probe with a real write (`Files.createTempFile(dir.toPath(), ".probe", null)` then
  delete). If it fails, show a one-time dialog: "Lyrenne can't write to its folder. Move it to
  a folder you own (e.g. Documents)." On Linux see 2.4.

### F4. Dead plaintext secret: `discordToken` — **Low (security hygiene), S**

- **Where:** `settings/PreferencesManager.kt` (load/save). No other reference in the codebase.
- **Problem:** a Discord user token field is persisted in plaintext but never used (desktop
  Rich Presence uses the local IPC pipe, which needs no token). If anything ever fills it in,
  it sits unprotected in `preferences.properties`.
- **Fix:** delete the field, its load and its save. On load, `props.remove("discordToken")` once.

### F5. System theme check blocks composition and never updates — **Medium, S**

- **Where:** `ui/theme/Theme.kt:237` (`isSystemDarkTheme`), called from `LyrenneTheme` at
  `Theme.kt:295`, which is used in three windows (`Main.kt:500, 554, 604`).
- **Problem:** spawns `reg query` (or `gsettings`/`defaults`) synchronously on the UI thread each
  time `LyrenneTheme` composes with `ThemeMode.SYSTEM`. Switching Windows between light and dark
  while Lyrenne is open does nothing until restart.
- **Fix:** a single `StateFlow<Boolean>` in a small object, filled off the UI thread at startup
  and refreshed every ~30 s (or on window focus gain). `LyrenneTheme` reads
  `collectAsState()`. One process per poll for the whole app instead of one per window.

### F6. Back navigation refetches and loses scroll — **Medium (UX), S**

- **Where:** `ui/App.kt:369-480` — screens are swapped with a plain `when`.
- **Problem:** going Album → Artist → back recreates `AlbumScreen`: `LaunchedEffect(browseId)`
  refetches, and the list scrolls to the top. Same when switching tabs (Library loses its
  scroll and filters).
- **Fix:**
  ```kotlin
  val stateHolder = rememberSaveableStateHolder()
  stateHolder.SaveableStateProvider(key = currentDetail?.toString() ?: currentScreen.name) {
      when (...) { ... }   // existing code unchanged
  }
  ```
  `rememberLazyListState()` is saveable, so scroll positions come back for free. To skip the
  refetch, hold each screen's loaded data in `rememberSaveable` (desktop has no Bundle limits —
  the holder keeps objects in memory). Remove the entry from the holder
  (`stateHolder.removeState(key)`) when it's popped off the detail stack for good.

### F7. DPAPI through a spawned PowerShell — **Low, S**

- **Where:** `auth/BrowserCookieExtractor.kt:182` (`decryptWithDPAPI`).
- **Problem:** spawns `powershell.exe` (slow start, ~300–800 ms; blocked on some locked-down
  machines by execution policy/AppLocker). For legacy non-`v1x` cookies it spawns **one per cookie**.
- **Fix:** `jna-platform` is already a dependency:
  ```kotlin
  com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(encrypted)
  ```
  Same result, in-process, microseconds.

### F8. Dead VLC fallback code — **Low, S**

See 2.3: `VLC_PLUGIN_PATH` as a Java property and the post-startup `java.library.path`
mutation do nothing. Delete both so the next reader doesn't trust them.

### F9. Version number defined twice — **Low, S**

- **Where:** `desktop/build.gradle.kts` (`lyrenneVersion`) and `update/AutoUpdater.kt:29`
  (`CURRENT_VERSION`). A comment says both are "checked on every release" — by hand.
- **Fix:** generate a resource at build time:
  ```kotlin
  tasks.processResources { filesMatching("version.properties") { expand("version" to lyrenneVersion) } }
  ```
  with `src/main/resources/version.properties` containing `version=${version}`; `AutoUpdater`
  reads it. One source of truth.

### F10. Minimize-to-tray with no tray hides the app — **High on Linux, S**

See 2.5. Listed here too because a failed tray init on Windows triggers it as well.

### F11. Unused build configuration — **Low, S**

`targetFormats(TargetFormat.Msi, TargetFormat.Exe)` and the `windows { menuGroup, upgradeUuid, dirChooser, shortcut, menu }`
block configure installers that the release process forbids building. Either remove them or
leave a comment that they're intentionally unused, so nobody runs `packageMsi` by habit.

`patchPortableIcon` is also dead: CLAUDE.md ("Icons") notes it looks for Resource Hacker at a
path that doesn't exist and skips every build, and that jpackage already embeds the `.ico`.
Delete the task.

### F12. Broad exception swallowing — **Watch item**

There are ~121 `catch (e: Exception)` / `catch (_: Exception)` blocks. Most are deliberate and
logged. The risk to check: inside `suspend` functions, catching `Exception` also catches
`CancellationException`, which keeps a cancelled coroutine running. Grep for catch-all blocks
inside `suspend fun` / `launch {}` bodies and rethrow `CancellationException` where found.

---

## 4. UI and design improvements

Observed on the running dev build (1366×768-class window, dark theme, signed out).

### U1. Slider tracks are invisible — **High visual impact, S**

- **Where:** seek bar and volume slider in `ui/components/MiniPlayer.kt`.
- **Seen:** the inactive part of both tracks is near-black on black; the seek bar reads as two
  orange dots with nothing between them. The volume slider is a thick, fully-filled pill with a
  tall thumb pressed against its right edge.
- **Fix:** `SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant)`
  (or `onSurface.copy(alpha = 0.24f)`); thinner track (4 dp) and a small round thumb (12 dp) that
  grows on hover/drag. Re-check contrast for the inactive track against the bar background
  (≥ 3:1 for non-text UI per WCAG 1.4.11).

### U2. Restored track shows `0:00 / 0:00` — **Medium, S**

- **Seen:** after restart the restored song shows zero duration until played.
- **Cause:** `PlaybackState.duration` is seeded only in `playMedia` and corrected by VLC's
  `lengthChanged` (CLAUDE.md "Duration has two sources"). `restoreQueue()` loads metadata without
  calling `playMedia`, so nothing seeds it.
- **Fix:** in `restoreQueue()`, set `duration = song.knownDurationMs()` for the restored current
  song (use the existing helper — never read `durationMs`/`duration` directly). VLC's value
  overrides it once the track loads.

### U3. Signed-out Library shows an error — **Medium, S**

- **Seen:** red error banner "Not logged in" stacked above an empty state that says "Sync your
  library or play songs to add them".
- **Cause:** `sync/LibrarySync.kt:65` reports signed-out as an *error*.
- **Fix:** treat signed-out as a normal state. Replace both with one empty state: icon,
  "Sign in to see your YouTube Music library", primary button **Sign in**, secondary text
  "Downloads and local playlists work without signing in". Keep red for real failures.

### U4. Two sign-in entry points in the navigation rail — **Low, S**

- **Seen:** avatar button at the top of the rail and a "Sign In" item at the bottom.
- **Fix:** keep the avatar (it becomes the account switcher when signed in); remove the bottom
  item, or show it only when signed out and drop the avatar placeholder then. One place.

### U5. Shelves clip cards with no scroll affordance — **Medium, M**

- **Seen:** the last visible card on each home shelf is cut in half at the right edge. Nothing
  says the row scrolls horizontally; mouse users without a horizontal wheel are stuck.
- **Where:** `ui/components/ScrollableRow.kt` has no arrows or fades.
- **Fix:** on hover, show circular ‹ › buttons at both ends (hidden when at the start/end;
  scroll by one viewport width with `animateScrollBy`), plus a 24–32 dp gradient fade into the
  background at the scrollable edges. Alternatively size cards so a whole number fit
  (`(width - gaps) / n`) at each breakpoint.

### U6. Icon-only player controls have no tooltips — **Medium, S**

- **Seen:** queue, lyrics, speed, sleep timer, shuffle, repeat — icons only.
- **Where:** `MiniPlayer.kt` has no `TooltipArea` usage.
- **Fix:** wrap each `IconButton` in `TooltipArea` (Compose Desktop) with the action name and its
  shortcut, e.g. "Shuffle (Ctrl+S)". The `contentDescription`s already exist for most — reuse them.
  Also show active state for speed ≠ 1× and an armed sleep timer (tinted icon or small badge).

### U7. Mini player artwork — **Low, S**

- **Seen:** music-video thumbnails are 16:9 frames cropped into a square, so the thumbnail is
  a dim, mostly black slice.
- **Fix:** for video-sourced items, prefer the square album art when available; otherwise
  `ContentScale.Crop` centred on the middle third, or show the 16:9 frame letterboxed on a
  blurred copy of itself.

### U8. Settings is one long list — **Low, M**

- **Where:** `SettingsScreen.kt` (1,554 lines, ~12 sections).
- **Fix:** at widths ≥ 900 dp, a two-pane layout: section index on the left (Account,
  Appearance, Audio, Playback, Lyrics, Downloads, Integrations, System, About), content on the
  right with click-to-scroll (`LazyListState.animateScrollToItem`). Add a filter field that
  hides non-matching rows. Hide platform-specific sections per OS (2.10).

### U9. Consistency pass — **Low, M**

Small things worth one sweep:

- Rail labels are ~11 sp; at 125–150 % Windows scaling they're fine, at 100 % on a 1080p
  monitor they're small. Consider 12 sp and slightly larger icons (24 dp).
- The rail's lower group (EQ, Stats, Recognize, Together) is visually identical to the main
  nav — a thin divider or a smaller "Tools" label separates destinations from tools.
- Keyboard focus indication: verify a visible focus ring on cards and rail items when tabbing
  (accessibility basics; Compose Desktop draws none by default for custom clickables).
- Empty and loading states: use one shared component (icon + title + body + optional action)
  everywhere instead of per-screen variants.

---

## 5. Documentation drift

| Doc | Says | Reality |
|---|---|---|
| `CLAUDE.md` Overview | "~12,000 lines of Kotlin across 34 files" | ~27,300 lines across 68 files in `desktop/` alone |
| `CLAUDE.md` Fully Working | "Authentication via browser cookie extraction (Opera, Chrome, Edge, Brave, Vivaldi, Firefox)" | Login only finds Edge, Chrome, Brave. No Opera, Vivaldi or Firefox code exists |
| `CLAUDE.md` Key Files | `BrowserCookieExtractor` — "Chromium + Firefox cookie DB detection" | Chromium only. Firefox support is *proposed* in 2.2 Plan A |
| `CLAUDE.md` Development Notes vs GitHub & Release | "robocopy-to-temp-dir push workflow is obsolete" vs. "Push workflow: robocopy to temp dir … force push" | Contradictory; the second block (and the `nul` file note) should go |
| `CLAUDE.md` Priority Work Items | "Context menus", "Play All / Shuffle All" | Both listed as done under Fully Working; section is stale |
| `CLAUDE.md` Auto-Updater | `checkForUpdate()` | Function is `checkForUpdates()` |
| `CLAUDE.md` File Storage Paths | "On first run, AppPaths auto-migrates files from old %APPDATA%" | `AppPaths.kt` header: "There is deliberately no migration" |
| `CLAUDE.md` Distribution | "Do NOT build packageExe or packageMsi" | `build.gradle.kts` still configures them (F11) |
| `CLAUDE.md` Fully Working | "Material3 theme with system dark/light detection (… Linux GTK)" | Linux detection reads a legacy key (2.7) |
| `desktop/build.gradle.kts` dependency comment | "Lyrenne is Windows-only" | Update once the Linux port starts |

---

## 6. Master action plan

Priority order. "Phase" refers to the Linux plan (2.12); items without a phase are
cross-platform fixes that can ship in any release.

| # | Item | Ref | Effort | Phase |
|---|---|---|---|---|
| 1 | Atomic, synchronised prefs; persist volume on drag end | F1 | S | — |
| 2 | Tray-close guard | F10 / 2.5 | S | 0 |
| 3 | Updater signature verification (minisign/Ed25519) | F2 | M | — |
| 4 | SaveableStateHolder for navigation | F6 | S | — |
| 5 | Slider track colours | U1 | S | — |
| 6 | Signed-out Library empty state | U3 | S | — |
| 7 | Restored-track duration | U2 | S | — |
| 8 | Tooltips on player controls | U6 | S | — |
| 9 | Single `isWindows`; delete dead VLC code; NOFOLLOW lock check | 2.12 / F8 | S | 0 |
| 10 | Firefox login path (Windows fallback first) | 2.2 A | M | 0 |
| 11 | Theme check off the UI thread + live updates | F5 / 2.7 | S | 1 |
| 12 | XDG data dir on Linux | 2.4 | S | 1 |
| 13 | Linux VLC lookup + messages | 2.3 | S | 1 |
| 14 | Updater disabled on Linux; Gradle task guards | 2.8 / 2.9 | S | 1 |
| 15 | Linux Firefox (native + snap) + Chromium `--password-store=basic` | 2.2 A/B | M | 2 |
| 16 | Paste-cookie fallback | 2.2 C | S | 2 |
| 17 | MPRIS via mediasession | 2.6 | S | 3 |
| 18 | `.deb`/`.rpm` with VLC deps; Linux CI | 2.9 | M | 4 |
| 19 | Shelf arrows/fades | U5 | M | — |
| 20 | Settings two-pane + filter | U8 | M | — |
| 21 | DPAPI via JNA | F7 | S | — |
| 22 | Version single-sourced | F9 | S | — |
| 23 | Remove `discordToken`; remove unused installer config | F4 / F11 | S | — |
| 24 | Doc drift fixes | §5 | S | — |
| 25 | `CancellationException` audit | F12 | M | — |

---

## 7. Open questions

1. **Linux distribution format:** `.deb` + `.rpm` only, or also AppImage / Flatpak? Flatpak
   would need VLC via a Flatpak extension and can't launch the host browser with a custom
   profile — it effectively forces the paste-cookie login. Recommendation: `.deb`/`.rpm` (+ AUR)
   first.
2. **Portable mode on Linux:** keep a `portable` marker-file escape hatch (data next to the
   app) for tarball users, or XDG only?
3. **Firefox on Windows:** fallback only (recommended), or make it the preferred browser
   everywhere to stop depending on Chromium's cookie encryption?
4. **ARM64 Linux** (Raspberry Pi, Asahi): in scope? Needs `Linux/aarch64` sqlite natives kept
   and an ARM CI runner; VLC is available on both.
5. **Update signing key:** who holds it, and where is it stored offline?

### Decisions (2026-10-08)

1. **Format:** `.deb` + `.rpm` (nfpm, VLC as a dependency). Fedora is a supported target and is
   tested in CI. No AppImage/Flatpak for now.
2. **Portable mode on Linux:** kept. A `portable` file next to the app puts data beside it;
   otherwise XDG dirs.
3. **Firefox on Windows:** fallback only, after Edge, Chrome and Brave.
4. **ARM64 Linux:** out of scope for now (amd64 packages only).
5. **Update signing key:** held by the maintainer at `~/.lyrenne/update-signing.key`, copied to their
   second dev machine. Public key embedded in `AutoUpdater.UPDATE_PUBLIC_KEY` from 2.15.0.

---

## 8. Status (implemented in 2.15.0)

Every item in the master action plan (§6) is implemented. Deliberately skipped: shelf edge fades
(arrows cover the affordance), stripping foreign sqlite natives from the Linux packages (~9 MB),
a shared empty/loading component, `systemd-inhibit` for sleep, an AppImage, apt/COPR repositories.

Verified automatically on every push (`.github/workflows/linux.yml`):

| Criterion | How |
|---|---|
| Unit tests on Linux (incl. Linux v10 decrypt, Firefox reader, dangling `SingletonLock`) | `build` job, Ubuntu |
| `.deb` installs with its VLC dependency; app starts; system libvlc found; data in `~/.local/share/lyrenne` | `build` job, Xvfb |
| Same for `.rpm` on Fedora with Fedora's own `vlc` | `fedora` job, `fedora:latest` container |
| Local file plays through system VLC; EQ, speed, normalize, skip-silence apply without error | `e2e` job, `LinuxPlaybackE2ETest` |
| MPRIS visible to and controllable from `playerctl` | `e2e` job |
| Distro VLC has every plugin a YouTube stream needs (HTTPS, adaptive, MP4/MKV, Opus, avcodec) | `e2e` job |
| Sign-in plumbing with real **Firefox**: found first on Linux, launched with Lyrenne's arguments and profile, lock seen while running and cleared after, cookies read from `cookies.sqlite` | `e2e` job, `LinuxBrowserE2ETest` |
| Same with real **Chrome** + `--password-store=basic`: cookies decrypt as `v10` (AES-128-CBC), not sent to the keyring | `e2e` job, `LinuxBrowserE2ETest` |

The browser runs are signed out (CI has no Google account), so they prove everything except that the
cookies carry a live session. That last step is the same code path as Windows (`buildCookieResult`,
`AuthManager.saveCredentials`).

Not automatable, needs a person on a Linux desktop: signing in with a real Google account,
streamed playback (needs that session), tray and media widgets on specific desktops (GNOME, KDE,
Cinnamon), HiDPI scaling.
