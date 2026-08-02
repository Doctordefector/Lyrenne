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
- **Package namespace** is `com.lyrenne.desktop`. The `com.metrolist.*` imports you will still
  see are the vendored upstream modules (innertube, lrclib, kugou, lastfm, shazamkit,
  betterlyrics). Those are someone else's GPL sources consumed via `kotlin.srcDir()`: renaming
  them would conflict with every future upstream sync, so leave them alone.

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
5. **Renaming a user data path orphans every existing install.** 2.9.4 did exactly that on
   purpose, trading upgrade smoothness for a clean break, and shipped manual recovery steps in the
   release notes. Do not do it again casually: if a path must change, ship a migration.
6. **Discord Rich Presence fires on song change only.** An earlier version re-sent it on position
   ticks, which raced the named pipe and hit Discord's rate limit. Do not reintroduce that.
7. **Do not bump the version** unless asked. It lives in two places that must match:
   `desktop/build.gradle.kts` and `AutoUpdater.CURRENT_VERSION`.
8. **`data/login-profile` must never outlive a sign-in.** It holds a live Google session. Deleting
   only `credentials.json` on logout left that session on disk, so signing out was not signing out.
   Delete it on successful extraction, on logout, and at startup. Only on `Success` though: the
   handoff path re-reads the profile while the user is still signing in.
9. **Never let the updater off HTTPS or off GitHub.** `requireTrustedUrl()` gates the download URL
   and every redirect hop. What it fetches is executed, and nothing is signed.
10. **Paths interpolated into the generated PowerShell update script go through `psQuote()`.**
    `$` and `$(...)` are both legal in Windows folder names, so double quotes there mean silent
    wrong-directory copies at best and execution at worst.
11. **Do not blanket-add `key =` to lazy lists.** Queue and local playlists can hold the same song
    twice, and Compose throws on duplicate keys. Only collections with database primary keys are
    safe, which is the Library lists and nothing else.
12. **No em dashes.** Not in UI strings, docs, comments or commit messages. The project owner does
    not write with them and will ask for them to be removed.

## Things that look broken but are not

- **An expired YouTube session returns HTTP 200 with an anonymous response**, not an error. An empty
  library, an empty home feed and failing writes usually mean one dead login, not four bugs.
- **`plugins.dat` in the bundled VLC is mandatory.** Without it libvlc interrogates 213 plugin DLLs
  on every launch: 11.6 seconds of startup versus 0.57 with the cache.
- **ffmpeg is not in the repo.** At ~114 MB it exceeds GitHub's file limit, so `fetchFfmpeg`
  downloads it once on demand.
- **The first-run wizard not appearing is usually correct.** `onboardingCompleted` defaults to
  `false` in the data class but `true` when read from an existing `preferences.properties`. That
  asymmetry is the migration keeping current users out of the wizard. Delete the file to see it.
- **A static Compose window renders zero frames.** Nothing is stuck; it draws on invalidation only.

## Before claiming a feature is missing

`CLAUDE.md` has drifted behind the code before. Check the source before building something again.

## History

The project was called Metrolist Desktop until 2.9.2 and began as a desktop port of
[Metrolist](https://github.com/MetrolistGroup/Metrolist). It was renamed at the upstream project's
request and is independent of them; their GPL-3.0 modules are still used and credited.

2.9.3 renamed the app. 2.9.4 finished the job: the database filename, the package namespace and
the legacy `%APPDATA%` migration all changed, and the compatibility launcher was dropped. Installs
older than 2.9.4 do not upgrade cleanly by design. The only `metrolist` left in the tree is in the
vendored upstream module packages, which stay.
