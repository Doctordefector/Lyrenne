# Working on Lyrenne as an AI agent

Lyrenne is a YouTube Music player for Windows, written in Kotlin on Compose Desktop (JVM). It is
**not** an Android app. Any documentation describing this repo as an Android project with Hilt,
Room and Media3 is stale and describes the upstream project it began as.

`CLAUDE.md` is the detailed project guide. This file is the short version plus the traps.

## What this actually is

- **Target**: Windows 10 and 11 desktop, JVM 21. There is no mobile build.
- **Modules**: only `:desktop` is a Gradle project. The `innertube`, `lrclib`, `betterlyrics`,
  `kugou`, `lastfm` and `shazamkit` folders are Android library modules whose *sources* are pulled
  in via `kotlin.srcDir()`, because a JVM target cannot consume Android libraries as project
  dependencies. `app/` is the original Android app, kept only as a porting reference, never built.
- **Stack**: Compose Desktop UI, VLC through vlcj for playback, SQLDelight for the database,
  singleton managers instead of dependency injection.
- **Package namespace** is still `com.metrolist.music.desktop`. Deliberate: it is invisible to
  users, and renaming it would churn every file plus the SQLDelight and protobuf output for
  nothing.

## Build and test

```bash
./gradlew :desktop:compileKotlin      # fast check
./gradlew :desktop:createDistributable # runnable app
./gradlew :desktop:packagePortableZip  # release archive
```

There is no APK, no emulator and no `:app:assembleFossDebug`.

## Rules that are not style preferences

Each of these exists because breaking it caused real damage.

1. **Never run the app from `desktop/build/compose/binaries/main/app/Lyrenne/`.** `AppPaths` writes
   `data/` next to the executable, so running it there plants real login cookies in the exact
   folder that gets zipped for release. This shipped publicly once. Test from a copy outside the
   build tree.
2. **Never hand-roll the release ZIP.** `packagePortableZip` purges runtime dirs and then fails the
   build if the archive contains credentials, the database, preferences or a log.
3. **Never use PowerShell `Compress-Archive`.** It writes backslash entry names, which breaks
   Java's `ZipEntry.isDirectory()` and therefore the auto-updater. Use 7-Zip.
4. **Do not encrypt or obfuscate `credentials.json`.** Plaintext is intentional and required.
5. **Do not rename `metrolist.db`,** or any other user data path, to match branding. Every existing
   install keeps its library under that name and renaming it orphans them all.
6. **Discord Rich Presence fires on song change only.** An earlier version re-sent it on position
   ticks, which raced the named pipe and hit Discord's rate limit. Do not reintroduce that.
7. **Do not bump the version** unless asked. It lives in two places that must match:
   `desktop/build.gradle.kts` and `AutoUpdater.CURRENT_VERSION`.

## Things that look broken but are not

- **An expired YouTube session returns HTTP 200 with an anonymous response**, not an error. An empty
  library, an empty home feed and failing writes usually mean one dead login, not four bugs.
- **`plugins.dat` in the bundled VLC is mandatory.** Without it libvlc interrogates 213 plugin DLLs
  on every launch: 11.6 seconds of startup versus 0.57 with the cache.
- **ffmpeg is not in the repo.** At ~114 MB it exceeds GitHub's file limit, so `fetchFfmpeg`
  downloads it once on demand.

## Before claiming a feature is missing

`CLAUDE.md` has drifted behind the code before. Check the source before building something again.

## History

The project was called Metrolist Desktop until 2.9.2 and began as a desktop port of
[Metrolist](https://github.com/MetrolistGroup/Metrolist). It was renamed at the upstream project's
request and is independent of them; their GPL-3.0 modules are still used and credited. Expect the
old name in the package namespace, in `metrolist.db`, and in compatibility paths in `AutoUpdater`.
All of those are intentional.
