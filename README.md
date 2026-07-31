<div align="center">

<img src=".github/lyrenne-banner.svg?v=1" width="100%"
     alt="Lyrenne, a free open source YouTube Music player for Windows">

# Lyrenne: a YouTube Music Player for Windows

[![Latest release](https://img.shields.io/github/v/release/Doctordefector/Lyrenne?style=for-the-badge&label=download&labelColor=0a0a0a&color=A37C43)](https://github.com/Doctordefector/Lyrenne/releases/latest)
[![Portable](https://img.shields.io/badge/install-portable%20zip-A37C43?style=for-the-badge&labelColor=0a0a0a)](https://github.com/Doctordefector/Lyrenne/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-777777?style=for-the-badge&labelColor=0a0a0a)](LICENSE)
[![Windows](https://img.shields.io/badge/Windows-10%20%7C%2011-777777?style=for-the-badge&labelColor=0a0a0a)](https://github.com/Doctordefector/Lyrenne/releases/latest)

**A free, open source YouTube Music desktop player for Windows 10 and 11.**
Your real library, your real playlists, synced lyrics and offline downloads. No ads, no installer,
no telemetry. Unzip it and it runs.

[**Download**](https://github.com/Doctordefector/Lyrenne/releases/latest) ·
[Website](https://doctordefector.github.io/Lyrenne/) ·
[Features](#features) · [Troubleshooting](#troubleshooting) · [Build from source](#build-from-source)

</div>

---

## Install

1. Grab `Lyrenne-X.Y.Z-portable.zip` from [the latest release](https://github.com/Doctordefector/Lyrenne/releases/latest).
2. Extract it **anywhere except a cloud-synced folder** (see the warning below).
3. Run `Lyrenne.exe` and sign in with your browser.

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

## Features

| | |
|---|---|
| **Playback** | Streaming and local files through a bundled VLC engine, gapless queue, shuffle and repeat, crossfade, playback speed 0.25×–3×, sleep timer, 10-band equalizer with presets, skip silence, volume normalization |
| **Library** | Full two-way sync with YouTube Music: liked songs, albums, artists and playlists, and every edit you make locally is pushed back to your account. Local playlists, auto playlists, sorting, search and grid/list views |
| **Discovery** | Home feed with continuations, Explore (new releases, moods & genres, charts), search with filters and suggestions, radio and auto-queue, quick picks, podcasts |
| **Lyrics** | Synced and plain, from a five-provider chain (BetterLyrics → LrcLib → KuGou → YouTube lyrics → YouTube transcript), with size, alignment and click-to-seek options |
| **Social** | Listen Together rooms with live playback sync, Discord Rich Presence, Last.fm scrobbling |
| **Extras** | Music recognition through Shazam, listening stats and history, downloads with progress, car/USB export to loudness-normalized MP3, backup and restore, proxy support, region and language settings, system tray with a mini player, media-key and keyboard shortcuts, Material 3 theming that follows your system |

Everything the app writes (database, credentials, preferences, cache, downloads) stays in the
app's own folder. Nothing is written to `%APPDATA%`, and nothing is sent anywhere but YouTube.

## Why it exists

YouTube ships no desktop app for YouTube Music, and a browser tab is not a music player.

Lyrenne is not a wrapper around the website and not a repackaged mobile app. It is a native
Compose Desktop (JVM) application that talks to the same InnerTube API the mobile clients use,
with its own VLC playback engine, local database and sync layer written for the desktop.

- First released **9 March 2026**, actively developed, 50+ releases since.
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

**I used to have Metrolist Desktop.** Same app, renamed at 2.9.2. Your library and login carry
over: the data lives next to the executable, so keep using the same folder. Old installs get one
last in-place update onto the renamed build.

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
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor).

## License

GPL-3.0. See [LICENSE](LICENSE). Lyrenne is not affiliated with YouTube, Google, or Alphabet.
