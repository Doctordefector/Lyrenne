<div align="center">

<img src=".github/lyrenne-banner.svg?v=1" width="100%"
     alt="Lyrenne Music Player, a free open source YouTube Music player for Windows, macOS and Linux">

# Lyrenne Music Player: YouTube Music for Windows, macOS and Linux

[![Latest release](https://img.shields.io/github/v/release/Doctordefector/Lyrenne?style=for-the-badge&label=download&labelColor=0a0a0a&color=A37C43)](https://github.com/Doctordefector/Lyrenne/releases/latest)
[![Portable](https://img.shields.io/badge/install-portable%20zip-A37C43?style=for-the-badge&labelColor=0a0a0a)](https://github.com/Doctordefector/Lyrenne/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-777777?style=for-the-badge&labelColor=0a0a0a)](LICENSE)
[![Windows](https://img.shields.io/badge/Windows-10%20%7C%2011-777777?style=for-the-badge&labelColor=0a0a0a)](https://github.com/Doctordefector/Lyrenne/releases/latest)
[![macOS](https://img.shields.io/badge/macOS-Apple%20Silicon%20%7C%20Intel-777777?style=for-the-badge&labelColor=0a0a0a)](#macos)
[![Linux](https://img.shields.io/badge/Linux-AppImage%20%7C%20.deb%20%7C%20.rpm-777777?style=for-the-badge&labelColor=0a0a0a)](#linux)

**A free, open source YouTube Music desktop player for Windows, macOS and Linux, built for people
who play music while they game and stream.** Your real library, your real playlists, synced
lyrics and offline downloads, plus global hotkeys and lyrics over your game, an OBS overlay, a
phone remote with no app to install, and a full-screen karaoke Stage. No ads, no telemetry.

[**Download**](https://github.com/Doctordefector/Lyrenne/releases/latest) ·
[Website](https://doctordefector.github.io/Lyrenne/) ·
[Features](#features) · [For gamers and streamers](#for-gamers-and-streamers) · [Coming from MetroList?](#coming-from-metrolist) · [Other versions](#other-versions) · [Troubleshooting](#troubleshooting) · [Build from source](#build-from-source)

</div>

---

## Other versions

| | |
|---|---|
| **PC VR** | [Lyrenne VR](https://github.com/Doctordefector/Lyrenne-VR): your music on your wrist in any SteamVR or Virtual Desktop (VDXR) game. Glance to see the song and synced lyrics, point to play, skip or change the volume. |
| **iPad** | [Lyrenne for iPad](https://github.com/Doctordefector/Lyrenne-iPad): a sideloadable version with synced lyrics. |
| **Steam Deck** | [Lyrenne for Decky](https://github.com/Doctordefector/Lyrenne-Decky): play your music in Game Mode from the Quick Access Menu while you game. |

## For gamers and streamers

| | |
|---|---|
| **Game Mode** | Global hotkeys that work while a game has focus (play/pause, skip, like, volume, seek), and a click-through overlay that shows the song or karaoke lyrics over borderless and windowed games without ever stealing focus. Settings > Game Mode |
| **Stream overlay** | An OBS Browser Source with the song, cover art and word-by-word lyrics, in card, lyrics or ticker layouts. Optional `now-playing.txt`, `.json` and `cover.jpg` for text sources and Streamer.bot. Settings > Integrations > Lyrenne Link |
| **Phone remote** | Scan a QR code and control Lyrenne from your phone's browser: play, skip, seek, search, queue and live lyrics. Off until you turn it on, and only on your own network |
| **Stage** | Full-window karaoke lyrics over a background that moves with the cover's colours. Ctrl+Shift+F, then F11 for full screen |
| **Also plays** | On your wrist in VR, and in the Steam Deck's Quick Access Menu (see [Other versions](#other-versions)) |

## Coming from MetroList?

Make a backup in MetroList (Settings > Backup and restore > Backup), then in Lyrenne choose
**Settings > Backup & Restore > Import from MetroList** (or the step offered during setup). Your liked
songs, playlists, saved albums, followed artists and play history come over in one go, from
MetroList 13 or 14. Nothing already in Lyrenne is overwritten. Playlists from Spotify, Apple Music,
Deezer, TIDAL or SoundCloud can be imported from a link, or from a pasted list of songs.

## Install

### Windows

1. Grab `Lyrenne-X.Y.Z-portable.zip` from [the latest release](https://github.com/Doctordefector/Lyrenne/releases/latest).
2. Extract it **anywhere except a cloud-synced folder** (see the warning below).
3. Run `Lyrenne.exe`. A short setup walks you through signing in and picking your settings.

When it opens a browser to sign in, choose **Accept all** on the cookie prompt. That is how Lyrenne
signs in: it reads those cookies once, then closes the browser profile. If the browser offers to
save your password afterwards you can decline, that part makes no difference.

Or install it with [Scoop](https://scoop.sh):

```powershell
scoop bucket add lyrenne https://github.com/Doctordefector/scoop-lyrenne
scoop install lyrenne
```

There is nothing else to install. VLC and ffmpeg ship inside the archive, and the app carries its
own Java runtime. Windows 10 or 11, 64-bit.

> [!WARNING]
> **Do not extract into OneDrive, Dropbox, or a synced Desktop/Documents folder.** Lyrenne is
> portable by design: the database, your login, and your downloads all live next to
> `Lyrenne.exe`. A sync client that reopens those files mid-write will corrupt the database or
> lock it on startup. `C:\Lyrenne` is a good home; the Desktop usually is not.

### macOS

Grab `Lyrenne-X.Y.Z-macos-arm64.dmg` (Apple Silicon) or `-macos-x64.dmg` (Intel) from
[the latest release](https://github.com/Doctordefector/Lyrenne/releases/latest), open it and drag
Lyrenne into Applications. VLC is included. The app is not notarized by Apple, so the first time
you open it macOS says it can't verify the developer: open **System Settings > Privacy & Security**
and click **Open Anyway** (once). Control Center, the lock screen, AirPods and the media keys all
control Lyrenne. Updates are a download of the new DMG; the app tells you when one is out.

### Linux

**AppImage (any distro):** `Lyrenne-X.Y.Z-x86_64.AppImage` or `-aarch64.AppImage` brings its own
VLC, so there is nothing else to install. Make it executable and run it. Needs glibc 2.34 or newer.

**Packages:** every release also ships a `.deb` (Debian, Ubuntu and their derivatives) and an
`.rpm` (Fedora), for 64-bit x86 and ARM. Grab the one for your distro from
[the latest release](https://github.com/Doctordefector/Lyrenne/releases/latest):

```bash
sudo apt install ./lyrenne_*_amd64.deb
```

```bash
sudo dnf install ./lyrenne-*.x86_64.rpm
```

The package installs to `/opt/lyrenne`, adds a `lyrenne` command and a menu entry, and pulls in
VLC from your distro (VLC is not bundled on Linux; the Snap and Flatpak versions of VLC can't be
used). Updates are one click from **Settings → Updates**: Lyrenne downloads the signed package and
installs it through your system's password prompt.

- Your library and settings live in `~/.local/share/lyrenne`, downloads in `~/Music/Lyrenne`.
- Sign-in uses Firefox if you have it (Snap Firefox included), otherwise Chrome/Chromium/Edge/Brave/
  Vivaldi. With none of those, or only Flatpak browsers, use **Advanced: paste a cookie** on the
  sign-in screen.
- Media keys, the GNOME/KDE media widget and `playerctl` work through MPRIS, and song changes
  show as desktop notifications. There is no tray icon on Linux, so closing the window quits.
- Car/USB export needs `ffmpeg` from your package manager.

## Features

| | |
|---|---|
| **Playback** | Streaming and local files through a bundled VLC engine, gapless queue, shuffle and repeat, Smart Shuffle that mixes songs like your playlist into it, crossfade, playback speed 0.25×–3×, sleep timer, skip silence, volume normalization, mouse wheel on any slider |
| **Sound** | 10-band equalizer with typed values and shareable presets, AutoEQ headphone correction for 8,000+ models fitted so nothing clips, SponsorBlock skipping of non-music parts and sponsor reads (private hashed lookup, off until you turn it on) with Undo and marks on the seek bar |
| **Library** | Full two-way sync with YouTube Music: liked songs, albums, artists and playlists, and every edit you make locally is pushed back to your account. Local playlists, auto playlists, sorting, search across the library or within one playlist, and grid/list views |
| **Discovery** | Home feed with continuations, Explore (new releases, moods & genres, charts), search with filters and suggestions, Start Radio, autoplay that keeps similar songs coming when your queue runs out, quick picks, podcasts |
| **Lyrics** | Synced and plain, from a five-provider chain (BetterLyrics → LrcLib → KuGou → YouTube lyrics → YouTube transcript), word-by-word highlighting where the provider has word timing, manual search when the automatic match is wrong, and size, alignment and click-to-seek options |
| **Social** | Listen Together rooms with live playback sync, Discord Rich Presence, Last.fm and ListenBrainz scrobbling |
| **Rewind** | A listening recap for any week, month, year or custom dates, built only from your own play history: top songs, artists and albums, listening clock, streaks, biggest discovery. Save any card as an image or play your top 50 |
| **Accounts** | Keep several YouTube accounts signed in and switch between them with one click, handy on a shared PC |
| **Extras** | Guided first-run setup, floating always-on-top mini player, music recognition through Shazam, listening stats and history, offline downloads with progress, played from disk wherever they are queued, car/USB export to loudness-normalized MP3, backup and restore, proxy support, region and language settings, system tray (menu bar on macOS) with a mini player, Windows and macOS Now Playing and media controls, MPRIS on Linux, media-key and keyboard shortcuts, Material 3 theming that follows your system |

Everything the app writes (database, credentials, preferences, cache, downloads) stays in the
app's own folder, and there is no telemetry. One setting is the single exception: "Name Lyrenne in
Windows media controls" adds a Start Menu shortcut, which is what lets Windows label the Now Playing
card as Lyrenne rather than "Unknown app". It is on by default, and turning it off deletes the
shortcut.

Traffic goes to YouTube, plus whichever of these you actually turn on: the lyrics providers, Last.fm
if you connect it, Discord's local pipe for Rich Presence, GitHub for update checks, and the Listen
Together relay server while you are in a room. Listen Together is the only one that involves a
third-party server holding anything, and it only sees what you are playing while a room is open.

## Why it exists

YouTube ships no desktop app for YouTube Music, and a browser tab is not a music player.

Lyrenne is not a wrapper around the website and not a repackaged mobile app. It is a native
Compose Desktop (JVM) application that talks to the same InnerTube API the mobile clients use,
with its own VLC playback engine, local database and sync layer written for the desktop.

- First released **9 March 2026**, actively developed, 80+ releases since.
- Auto-updater built in: it checks GitHub, downloads the new portable ZIP and restarts itself.
- Everything runs locally. No account of its own, no analytics, no server in the middle.

Forks are welcome; it is GPL-3.0 and that is the point.

## Troubleshooting

**The window never appears.** Lyrenne writes `lyrenne.log` next to `Lyrenne.exe` on every launch,
and a failure to start reports itself in a dialog. Send that log with a
[bug report](https://github.com/Doctordefector/Lyrenne/issues). The most common cause is a
cloud-synced install folder, so move it out of OneDrive and try again.

**"VLC not found".** The bundled copy lives in `app/resources/vlc`. If your unzip tool skipped it
(some do, on long paths), extract again with 7-Zip or Windows Explorer, or install VLC 3.x 64-bit
and Lyrenne will use that instead.

**Car / USB export does nothing.** That feature shells out to `app/resources/ffmpeg/ffmpeg.exe`.
If your antivirus quarantined it, restore it or drop any `ffmpeg.exe` next to `Lyrenne.exe`.

**Everything looks empty after signing in.** An expired YouTube session returns empty results
rather than an error. Sign out and sign in again from Settings.

**Upgrading from 2.9.3 or older?** 2.9.4 renamed the data files, so it starts with an empty
library. To keep yours, close the app and, inside its `data/` folder, rename `metrolist.db`
to `lyrenne.db`. Preferences and your login carry over on their own.

## Build from source

```bash
git clone https://github.com/Doctordefector/Lyrenne.git
cd Lyrenne
./gradlew :desktop:createDistributable
```

Needs JDK 21. The build downloads ffmpeg once (~114 MB) into `desktop/resources/windows-x64/`;
VLC is already in the tree. The runnable app lands in
`desktop/build/compose/binaries/main/app/Lyrenne/`, and `./gradlew :desktop:packagePortableZip`
produces the release archive.

`./gradlew :desktop:run` starts it straight from the source tree.

## Credits

Built and maintained by **[Andrei Chapliuk](https://andrevich.netlify.app)**.

Lyrenne began as a desktop port of [Metrolist](https://github.com/MetrolistGroup/Metrolist) by
mostafaalagamy and the Metrolist Group, and still uses their GPL-3.0 licensed InnerTube, LrcLib,
KuGou, BetterLyrics and ShazamKit modules. Lyrenne is an independent project and is not affiliated
with, endorsed by, or associated with the Metrolist Group.

Also built on [vlcj](https://github.com/caprica/vlcj),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform),
[SQLDelight](https://github.com/cashapp/sqldelight) and
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), with Windows media-session
support from [mediasession-kt](https://github.com/sayaka-sh/mediasession-kt).

## License

GPL-3.0. See [LICENSE](LICENSE). Lyrenne is not affiliated with YouTube, Google, or Alphabet.
