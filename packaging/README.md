# Packaging: winget and Scoop

Everything you need to know about the two package-manager listings for Metrolist Desktop.

## TL;DR per release

| | Action needed | Why |
|---|---|---|
| **Scoop** | **Nothing.** | The bucket's Excavator workflow polls daily, bumps version/URL/hash and commits itself. |
| **winget** | **One PR per release.** | winget-pkgs stores a frozen manifest per version. Nothing auto-bumps it. |

So the release checklist gains exactly one step, and it is winget-only.

---

## How users install

```powershell
winget install Doctordefector.MetrolistDesktop
```

```powershell
scoop bucket add metrolist https://github.com/Doctordefector/scoop-metrolist
scoop install metrolist-desktop
```

Both land the same portable ZIP. Neither needs admin rights: winget puts portable packages under `%LOCALAPPDATA%\Microsoft\WinGet\Packages\`, Scoop under `~\scoop\apps\metrolist-desktop\`. Because the app is portable and writes next to its own exe, both install locations work without changes.

---

## Where each manifest actually lives

| Thing | Location | Who edits it |
|---|---|---|
| winget manifests (source of truth) | `packaging/winget/` in this repo | You, once per release |
| winget manifests (published) | `microsoft/winget-pkgs` → `manifests/d/Doctordefector/MetrolistDesktop/<version>/` | Via PR from your fork |
| Scoop manifest (published) | [Doctordefector/scoop-metrolist](https://github.com/Doctordefector/scoop-metrolist) → `bucket/metrolist-desktop.json` | Excavator, automatically |

`packaging/scoop/` intentionally holds no manifest. Keeping a second copy here would go stale the instant Excavator ran.

---

## Bumping winget for a new release

### The easy way

Install [wingetcreate](https://github.com/microsoft/winget-create) once (`winget install Microsoft.WingetCreate`), then after the GitHub release is published:

```powershell
wingetcreate update Doctordefector.MetrolistDesktop --version 3.0.0 --urls https://github.com/Doctordefector/Metrolist-Desktop/releases/download/v3.0.0/Metrolist-3.0.0-portable.zip --submit
```

It downloads the ZIP, computes the SHA256, clones the manifest forward, and opens the PR. It will ask for a GitHub token the first time.

### The manual way

1. Copy `packaging/winget/*` and bump `PackageVersion` in **all three** files.
2. Update in `Doctordefector.MetrolistDesktop.installer.yaml`:
   - `InstallerUrl` → new release asset
   - `InstallerSha256` → see below
   - `ReleaseDate` → the release's publish date
3. Update `ReleaseNotesUrl` in the locale file to the **specific tag**, not `/releases/latest`. A versioned manifest pointing at "latest" drifts and becomes wrong the moment you ship again.
4. Validate: `winget validate --manifest packaging/winget`
5. PR into `microsoft/winget-pkgs` at `manifests/d/Doctordefector/MetrolistDesktop/<version>/`.
   PR title format: `New version: Doctordefector.MetrolistDesktop version X.Y.Z`

### Getting the SHA256 without downloading

GitHub already publishes it, so don't re-download 180 MB:

```bash
gh release view vX.Y.Z --repo Doctordefector/Metrolist-Desktop --json assets --jq '.assets[].digest'
```

That value matches `Get-FileHash -Algorithm SHA256` on the local build. winget wants it **uppercase**; Scoop wants it lowercase.

---

## Manifest facts that are easy to get wrong

- **Zip root is `Metrolist/`.** The Gradle task runs `7z a -tzip <zip> "Metrolist/*"`, so every entry is prefixed. That's why winget uses `RelativeFilePath: Metrolist/Metrolist.exe` and Scoop uses `extract_dir: Metrolist`.
- **Forward slashes in `RelativeFilePath`.** Merged manifests in winget-pkgs use `/`, not `\`.
- **Schema version.** Currently `1.12.0` in all three files. Check what recent merged manifests use before bumping; the `# yaml-language-server:` comment at the top must match `ManifestVersion`.
- **`Moniker: metrolist-desktop`**, not `metrolist`. Monikers are globally unique across winget, and the bare name invites a collision with anything Metrolist-related.
- **Tags drive winget search.** The locale file carries 15; the cap is 16. Don't trim them.
- **Scoop `persist`.** `data` and `Downloads` are persisted, so `scoop update` cannot wipe the login, database or library. If a future version moves where the app stores things, update `persist` in the bucket or you will sign every Scoop user out.

---

## The in-app updater vs package managers

The updater in Settings is **manual only**, triggered by a button. It never fires on its own, so it does not silently fight winget or Scoop.

It does still overwrite the install directory if a user clicks it. For Scoop that desynchronizes Scoop's version tracking, which is why the bucket manifest carries a `notes` block telling users to run `scoop update metrolist-desktop` instead. Same caveat applies to winget's portable install.

If this ever becomes a real support burden, the fix is to detect a `scoop\apps\` or `WinGet\Packages\` install path at startup and hide the update button. Not worth building until someone actually reports it.

---

## Scoop Extras: blocked until 100 stars

The manifest was originally submitted to the official [ScoopInstaller/Extras](https://github.com/ScoopInstaller/Extras) bucket, which is what `scoop install` finds without adding a custom bucket. It was withdrawn: their package-request template has a **required** checkbox reading

> Reasonably well-known and widely used (e.g. if it's a GitHub project, it should have at least 100 stars and/or 50 forks)

The repo was at 33 stars and 0 forks, so PR [#18429](https://github.com/ScoopInstaller/Extras/pull/18429) was closed rather than file a request ticking a required box that wasn't true. The manifest itself passed their CI.

**When the repo crosses 100 stars:** open a Package Request issue on ScoopInstaller/Extras, then a PR adding `bucket/metrolist-desktop.json`, titled `metrolist-desktop: Add version X.Y.Z`. The manifest in the bucket repo can be reused as-is. Keep the personal bucket alive afterwards so existing users don't break.

winget has no equivalent popularity gate.

---

## History

- 2026-07-31: winget PR [#410566](https://github.com/microsoft/winget-pkgs/pull/410566) opened for 2.9.2; personal Scoop bucket created; Extras PR closed pending the star threshold.
