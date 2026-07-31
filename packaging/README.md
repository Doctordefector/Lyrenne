# Packaging: Scoop

Metrolist Desktop is distributed two ways: the portable ZIP from the releases page, and a Scoop
bucket. There is deliberately no winget listing; see the last section for why.

## TL;DR per release

**Nothing to do.** The bucket's Excavator workflow polls daily, bumps the version, URL and hash,
and commits itself. Publishing the GitHub release is the whole job.

## How users install

```powershell
scoop bucket add metrolist https://github.com/Doctordefector/scoop-metrolist
scoop install metrolist-desktop
```

No admin rights needed. Scoop puts it under `~\scoop\apps\metrolist-desktop\`, and because the
app is portable and writes next to its own exe, that location works unchanged.

## Where the manifest lives

The live manifest is in its own bucket repo,
[Doctordefector/scoop-metrolist](https://github.com/Doctordefector/scoop-metrolist), at
`bucket/metrolist-desktop.json`. `packaging/scoop/` in this repo holds no copy: a second one
would go stale the first time Excavator ran.

## Manifest facts that are easy to get wrong

- **Zip root is `Metrolist/`.** The Gradle task runs `7z a -tzip <zip> "Metrolist/*"`, so every
  entry carries that prefix. Hence `extract_dir: Metrolist`. If the zip layout ever changes, the
  manifest breaks.
- **`persist` is load-bearing.** `data` and `Downloads` are persisted, so `scoop update` cannot
  wipe the login, database or library. **If a future version changes where the app stores things,
  update `persist` in the bucket or you will sign every Scoop user out and delete their library.**
  This is not cosmetic; it is the single most dangerous line in the manifest.
- **Hash case.** Scoop wants lowercase SHA256. Get it without re-downloading 180 MB:
  ```bash
  gh release view vX.Y.Z --repo Doctordefector/Metrolist-Desktop --json assets --jq '.assets[].digest'
  ```

## The in-app updater vs Scoop

The updater in Settings is **manual only**, triggered by a button, so it never fires on its own
and cannot silently fight Scoop.

It does still overwrite the install directory if a user clicks it, which desynchronizes Scoop's
version tracking. That is why the bucket manifest carries a `notes` block pointing users at
`scoop update metrolist-desktop` instead. If it ever becomes a real support burden, the fix is to
detect a `scoop\apps\` install path at startup and hide the update button.

## Scoop Extras: blocked until 100 stars

The official [ScoopInstaller/Extras](https://github.com/ScoopInstaller/Extras) bucket is what
`scoop install` finds without adding a custom bucket, so getting in there is worth doing. Their
package-request template has a **required** checkbox reading:

> Reasonably well-known and widely used (e.g. if it's a GitHub project, it should have at least
> 100 stars and/or 50 forks)

The repo was at 33 stars and 0 forks, so PR [#18429](https://github.com/ScoopInstaller/Extras/pull/18429)
was closed rather than file a request ticking a required box that wasn't true. The manifest
itself passed their CI.

**When the repo crosses 100 stars:** open a Package Request issue on ScoopInstaller/Extras, then
a PR adding `bucket/metrolist-desktop.json`, titled `metrolist-desktop: Add version X.Y.Z`. Reuse
the manifest from the bucket repo as-is. Keep the personal bucket alive afterwards so existing
users don't break.

## Why there is no winget listing

A manifest was written and submitted
([winget-pkgs#410566](https://github.com/microsoft/winget-pkgs/pull/410566)), passed
`winget validate`, and was then **withdrawn on purpose**. Do not resubmit without reading this.

winget installs a nested portable package into
`%LOCALAPPDATA%\Microsoft\WinGet\Packages\<id>\`. This app writes its database, credentials and
downloads next to `Metrolist.exe`, so all of it lands inside that package folder. `winget upgrade`
and `winget uninstall` replace or remove that folder, taking the user's library and sign-in with
them.

winget has **no `persist` equivalent**, so there is no manifest-side fix. And there is no
recovery path either: `AppPaths.migrateFromAppData` only restores from `%APPDATA%\Metrolist` when
`data/` is empty, and the app deliberately stopped writing there, so after a wipe there is
nothing to migrate back from.

**Before resubmitting to winget, `AppPaths` has to stop putting user data inside the install
directory when it detects it is running from a package-manager path.** Roughly: if the exe sits
under `WinGet\Packages\` or `scoop\apps\`, store data in `%LOCALAPPDATA%\Metrolist` instead. Once
that ships, winget becomes safe and Scoop's `persist` becomes a second line of defence rather
than the only one.

## History

- 2026-07-31: personal Scoop bucket created. Extras PR closed pending the star threshold. winget
  PR opened, then withdrawn over the data-loss issue above.
