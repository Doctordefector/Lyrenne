# Packaging

Every way Lyrenne is installed, what each one needs per release, and how to submit the ones that
live in someone else's registry.

| Channel | Arch | Where it comes from | Per release |
|---|---|---|---|
| Portable ZIP (Windows) | x64 | release asset, `packagePortableZip` | CI |
| Scoop | x64 | [Doctordefector/scoop-lyrenne](https://github.com/Doctordefector/scoop-lyrenne) | nothing (Excavator) |
| winget | x64 | `packaging/winget/` | open a winget-pkgs PR (below) |
| .deb / .rpm | x86_64, arm64 | release assets, `packaging/linux/nfpm.yaml` | CI |
| AppImage | x86_64, aarch64 | release assets, `packaging/appimage/` | CI |
| Flatpak (Flathub) | x86_64, aarch64 | `packaging/flatpak/`, then the flathub repo | bump the flathub repo (below) |
| Linux tarball (Steam Deck plugin) | x86_64, aarch64 | release assets | CI |

## Where data lives, per channel

This is the part that decides whether an upgrade can lose someone's library, so it comes first.
`InstallLocation.kt` makes the decision once at startup; `InstallLocationTest` pins every row.

| Install | Data | Downloads | Updates |
|---|---|---|---|
| Unzipped portable copy, Scoop | `<app>\data` | `<app>\Downloads` | in-app updater |
| winget (`...\WinGet\Packages\...`, or `--location`, recognised from its ARP entry) | `%LOCALAPPDATA%\Lyrenne` | `~\Music\Lyrenne` | "Update with: winget upgrade Lyrenne" |
| Program Files, any folder the user cannot write | `%LOCALAPPDATA%\Lyrenne` | `~\Music\Lyrenne` | points at the release page |
| .deb / .rpm | `~/.local/share/lyrenne` | `~/Music/Lyrenne` | in-app (pkexec) |
| AppImage | `~/.local/share/lyrenne` | `~/Music/Lyrenne` | "Download the new AppImage" |
| Flatpak | `~/.var/app/io.github.doctordefector.Lyrenne/data/lyrenne` | `~/Music/Lyrenne` | "Update with: flatpak update" |

`Lyrenne --smoke-test` prints the decision (and plays a tone through libVLC), which is how CI
checks each row on a real install.

## Scoop

```powershell
scoop bucket add lyrenne https://github.com/Doctordefector/scoop-lyrenne
scoop install lyrenne
```

**Nothing to do per release.** The bucket's Excavator workflow polls daily and bumps version, URL and
hash itself. `packaging/scoop/` holds no manifest on purpose: a second copy would go stale the first
time Excavator ran.

- **Zip root is `Lyrenne/`**, hence `extract_dir: Lyrenne`. Change the zip layout and the manifest breaks.
- **`persist: data, Downloads` is load-bearing.** A Scoop install is writable and outside any winget
  path, so it stays portable and keeps data in those two folders, which `persist` carries across
  `scoop update`. If a future version changes where portable copies store things, update `persist` in
  the bucket or every Scoop user is signed out and loses their library.
- **Hash case**: Scoop wants lowercase SHA256. `gh release view vX.Y.Z --repo Doctordefector/Lyrenne --json assets --jq '.assets[].digest'`.
- The in-app updater still works on a Scoop copy but desynchronises Scoop's version record, so the
  bucket's `notes` point users at `scoop update lyrenne`.

**Scoop Extras is blocked until 100 stars.** Their package-request template has a required box
("at least 100 stars and/or 50 forks"). PR [#18429](https://github.com/ScoopInstaller/Extras/pull/18429)
was closed rather than tick it falsely; the manifest itself passed their CI. Once the repo crosses 100
stars: open a Package Request issue on ScoopInstaller/Extras, then a PR adding `bucket/lyrenne.json`
(reuse the bucket's manifest) titled `lyrenne: Add version X.Y.Z`. Keep the personal bucket alive.

## winget

Package `Doctordefector.Lyrenne`, a portable ZIP with a nested portable `Lyrenne/Lyrenne.exe`, and
`ArchiveBinariesDependOnPath: true` so winget puts the app folder on PATH rather than symlinking the
exe (the launcher needs `app/` and `runtime/` beside it). Users get `winget install Lyrenne`, then
`lyrenne` from Run or a terminal; the first launch writes the Start Menu shortcut as usual.

### Why it was withdrawn once, and why it is safe now

A 2026-07 submission ([winget-pkgs#410566](https://github.com/microsoft/winget-pkgs/pull/410566)) was
withdrawn on purpose: winget installs into `%LOCALAPPDATA%\Microsoft\WinGet\Packages\<id>\` and
deletes that folder on `winget upgrade` and `winget uninstall`, and the app kept its database,
sign-in and downloads in that folder. winget has no `persist`, so there was no manifest-side fix.

The fix is app-side: a copy running from a winget folder keeps everything in `%LOCALAPPDATA%\Lyrenne`
instead (table above). CI proves it end to end on every release: `winget install --manifest`, run
`lyrenne --smoke-test` from PATH, check the layout says WINGET, `winget uninstall`, check the package
folder is gone and `lyrenne.db` is still there.

**Never submit a version that predates that fix.** `New-WingetManifest.ps1` refuses a ZIP without the
`InstallLocation` class, so it cannot be done by accident. The first safe version is the first release
built from a commit containing `desktop/src/main/kotlin/com/lyrenne/desktop/InstallLocation.kt`.

### Generating the manifests

```powershell
./packaging/winget/New-WingetManifest.ps1 -Version X.Y.Z
```

Downloads the published ZIP, hashes it, checks it (layout, and the fix above), fills
`packaging/winget/templates/`, writes `packaging/winget/out/manifests/d/Doctordefector/Lyrenne/X.Y.Z/`
(the winget-pkgs layout) and runs `winget validate`. The release CI also uploads a `winget-manifests`
artifact rendered from the exact ZIP it attached, so the hash is right before the release is published.

### Submitting (first time, then every release)

1. Publish the GitHub release (the URL in the manifest must resolve).
2. `./packaging/winget/New-WingetManifest.ps1 -Version X.Y.Z` and check it ends with
   `Manifest validation succeeded.`
3. Optional but asked for in the PR template: test the install locally. This needs an admin terminal
   once: `winget settings --enable LocalManifestFiles`, then
   `winget install --manifest packaging\winget\out\manifests\d\Doctordefector\Lyrenne\X.Y.Z`,
   launch `lyrenne`, check Settings shows data in `%LOCALAPPDATA%\Lyrenne`, `winget uninstall Doctordefector.Lyrenne`.
4. Fork [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs), copy the `X.Y.Z` folder to
   `manifests/d/Doctordefector/Lyrenne/X.Y.Z/` in the fork, commit, and open a PR titled
   `New package: Doctordefector.Lyrenne version X.Y.Z` (later releases: `New version: ...`).
   Fill the template's checklist; sign the CLA when the bot asks. Mention in the description that data
   lives in `%LOCALAPPDATA%\Lyrenne` and survives upgrade and uninstall (reviewers who saw #410566 may ask).
5. Later releases can be automated with `wingetcreate update Doctordefector.Lyrenne -v X.Y.Z -u <zip url> --submit`
   or the `vedantmgoyal9/winget-releaser` action, once the package exists. Not set up yet: it needs a
   classic PAT with `public_repo`, which is the user's call.

## Linux: .deb and .rpm (x86_64 and arm64)

`packaging/linux/nfpm.yaml`, built from `createDistributable` with the distro's VLC as a dependency.
arm64 builds on GitHub's free `ubuntu-24.04-arm` runners from the same file, with `arch:` rewritten
to `arm64` (nfpm turns that into `arm64` for deb and `aarch64` for rpm). The in-app updater picks the
asset by extension, and on arm64 the release has both arches, so it matches on the arch too
(`AutoUpdater`, `.deb`/`.rpm` selection).

## AppImage (x86_64 and aarch64)

`packaging/appimage/build-appimage.sh`, run in a `debian:bookworm` container after `createDistributable`.
One file that runs on any distro with no VLC installed: it carries libVLC 3.0.x from Debian (security
maintained), the plugins an audio player needs (no GUI, video output, Lua, or the D-Bus control
module, which would announce a second MPRIS player), and every library those link, placed with
linuxdeploy so their rpaths point inside the image. The base system (glibc, ALSA, X11, GL, fonts) is
left to the host, per the AppImage excludelist.

- `AppRun` sets `LYRENNE_VLC_DIR` and `VLC_PLUGIN_PATH` and does **not** set `LD_LIBRARY_PATH`: the app
  spawns the user's browser (sign-in), `xdg-open` and `pkexec`, which must load the host's libraries.
- The build prints the newest glibc symbol anything inside needs; that is the oldest distro it runs on.
- `plugins.dat` is generated at build time; squashfs keeps mtimes, so it stays valid.
- CI runs it on clean `ubuntu:22.04`, `ubuntu:24.04`, `debian:bookworm`, `fedora`, `archlinux`
  (x86_64) and `ubuntu:22.04`, `ubuntu:24.04`, `fedora` (aarch64) containers: `--smoke-test` plays a
  file and an HTTPS stream through the bundled libVLC (which also proves its TLS stack trusts the
  host's certificates), then the window opens under Xvfb.
- Updates: the app says "Download the new AppImage from the release page". Self-updating the file in
  place (`$APPIMAGE`) would be a natural next step; the release would need an `.AppImage.sig`.

## Flatpak (Flathub)

`packaging/flatpak/io.github.doctordefector.Lyrenne.yml`, on `org.freedesktop.Platform` 25.08.

- **libVLC** is built from the VideoLAN tarball as a module, configured down to what an audio player
  needs. AAC decodes through the runtime's `ffmpeg-full` extension; Opus needs nothing extra.
  `vlc-ignore-time-for-cache.patch` comes from Flathub's own VLC: ostree resets mtimes on install, so
  without it the plugin cache is always stale.
- **The app** is the release's Linux tarball (app plus its own Java runtime), per arch via
  `lyrenne-sources.json`. Flathub prefers building from source but accepts redistributing an open
  source project's own release binaries; a Gradle offline build would need every Maven dependency
  listed as a source, which can be done later if reviewers insist.
- **Permissions**, kept minimal: network; X11 + IPC (AWT has no Wayland backend, it runs on XWayland);
  DRI; PulseAudio (PipeWire serves it); `xdg-music/Lyrenne:create` for downloads and no other home
  access; own `org.mpris.MediaPlayer2.Lyrenne` (the app names its MPRIS session that since this
  change; the library default was `mediasessionkt`); notifications; Discord's IPC socket.
- **Sign-in is the known gap.** The browser sign-in launches a browser with a fresh profile and reads
  its cookies, and a sandboxed app can neither start host programs nor read their profiles without
  `--talk-name=org.freedesktop.Flatpak` (a sandbox escape Flathub reviewers push back on). In the
  Flatpak the sign-in screen finds no browser and offers "Advanced: paste a cookie" instead.
- CI builds it with `flatpak-builder` from that commit's distributable, runs `--smoke-test` inside the
  sandbox (plays, streams, data under `~/.var/app/...`, MPRIS name owned), opens the window, and runs
  `flatpak-builder-lint`.

### Submitting to Flathub (first time)

1. Publish a release that contains this work and has both Linux tarballs
   (`lyrenne-X.Y.Z-linux-x86_64.tar.gz`, `lyrenne-X.Y.Z-linux-aarch64.tar.gz`).
2. `sh packaging/flatpak/update-sources.sh X.Y.Z` and add `<release version="X.Y.Z" date="...">` to the
   metainfo. Commit.
3. Check it builds and lints locally (or read the `flatpak` job of that release's CI run):
   ```
   flatpak install -y flathub org.flatpak.Builder
   flatpak run --command=flathub-build org.flatpak.Builder --install packaging/flatpak/io.github.doctordefector.Lyrenne.yml
   flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest packaging/flatpak/io.github.doctordefector.Lyrenne.yml
   flatpak run --command=flatpak-builder-lint org.flatpak.Builder repo repo
   ```
4. Fork [flathub/flathub](https://github.com/flathub/flathub), branch from **`new-pr`** (not master),
   add the contents of `packaging/flatpak/` at the top level of the fork, and open a PR **against the
   `new-pr` branch** titled `Add io.github.doctordefector.Lyrenne`. Follow their checklist (the app id
   matches the GitHub account; you are the upstream developer).
5. A reviewer comments; a bot builds it when someone writes `bot, build`. Expect questions about the
   prebuilt tarball and the Discord/notification permissions; the reasons are in the manifest comments.
6. Once merged you get `flathub/io.github.doctordefector.Lyrenne`. Every later release: run
   `update-sources.sh`, add the metainfo `<release>`, and commit both to that repo (a PR there builds a
   test repo first). Verified ownership of the `io.github.doctordefector` id is done on flathub.org
   once, by signing in with GitHub.

## History

- 2026-07-31: personal Scoop bucket created. Extras PR closed pending the star threshold. winget
  PR opened, then withdrawn over the data-loss issue.
- 2026-11: data moved out of package-manager folders (`InstallLocation`); winget manifests, AppImage,
  Flatpak manifest and arm64 builds added, all launch-tested in release CI.
