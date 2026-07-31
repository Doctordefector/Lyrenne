# Lyrenne dev guide

Setting up a local development environment. See `CLAUDE.md` for architecture and `AGENTS.md` for
the rules that exist because breaking them caused damage.

## Prerequisites

- **JDK 21.** The build sets `jvmToolchain(21)`.
- **Windows 10 or 11, 64-bit.** Lyrenne targets Windows: it bundles a Windows VLC build, patches a
  Windows exe icon, and its browser login reads Windows DPAPI-encrypted cookies. The Kotlin
  compiles elsewhere, but you cannot produce or meaningfully run a distributable off Windows.
- **7-Zip**, at `C:\Program Files\7-Zip\7z.exe`. Only needed to build a release archive.

You do **not** need the Android SDK, a keystore, protoc, or Git submodules. The protobuf compiler
is pulled by Gradle, and the only module built is `:desktop`.

## Setup

```bash
git clone https://github.com/Doctordefector/Lyrenne.git
cd Lyrenne
./gradlew :desktop:run
```

That is the whole thing. The first build downloads ffmpeg once (~114 MB) into
`desktop/resources/windows-x64/ffmpeg/`, which is gitignored because the file exceeds GitHub's
100 MB limit.

## Common tasks

| Command | What it does |
|---|---|
| `./gradlew :desktop:run` | Runs straight from the source tree. Fastest loop. |
| `./gradlew :desktop:compileKotlin` | Compile check without launching. |
| `./gradlew :desktop:createDistributable` | Builds the runnable app into `desktop/build/compose/binaries/main/app/Lyrenne/`. |
| `./gradlew :desktop:packagePortableZip` | Builds the release archive. Purges runtime data and refuses to ship an archive containing secrets. |

## Testing your changes

**Do not run the app from the distributable folder.** `AppPaths` writes `data/` next to the
executable, so launching `desktop/build/compose/binaries/main/app/Lyrenne/Lyrenne.exe` puts your
real YouTube cookies inside the folder that gets zipped for release. That leaked publicly once.

Use `./gradlew :desktop:run` for iteration, or copy the distributable somewhere outside the build
tree if you need to test the packaged form.

Note that `packagePortableZip` deletes `data/` in the build folder every time it runs, so anything
you signed into there disappears on the next release build. That is the credential guard working
as intended, not a bug.

## Third-party credentials

Last.fm scrobbling reads its API key and secret from the user's own settings at runtime. There are
no build-time secrets and no GitHub Actions secrets to configure. If you are adding an integration,
keep it that way: the release is a portable ZIP that anyone can unpack, so a key baked into the
build is a key you have published.

## Releasing

Version lives in **two** places that must match, `desktop/build.gradle.kts` (`lyrenneVersion`)
and `AutoUpdater.CURRENT_VERSION`. The full process is in `CLAUDE.md`; packaging specifics are in
`packaging/README.md`.
