<#
.SYNOPSIS
  Renders the winget manifests for one Lyrenne release and validates them.

.DESCRIPTION
  Fills packaging/winget/templates with the version, release date and the ZIP's SHA-256, writes
  them to <OutDir>/manifests/d/Doctordefector/Lyrenne/<version>/ (the same layout as winget-pkgs,
  so the folder can be copied into a fork as-is), and runs `winget validate` on the result.

  It refuses a ZIP built before Lyrenne learned to keep its data out of the winget package
  folder (no InstallLocation class inside): winget deletes that folder on every upgrade, and an
  older build would take the user's library and sign-in with it. See packaging/README.md.

.EXAMPLE
  ./packaging/winget/New-WingetManifest.ps1                 # version from build.gradle.kts, ZIP from the release
  ./packaging/winget/New-WingetManifest.ps1 -Version 2.21.0
  ./packaging/winget/New-WingetManifest.ps1 -ZipPath desktop/build/compose/binaries/main/app/Lyrenne-2.21.0-portable.zip
#>
[CmdletBinding()]
param(
    [string]$Version,
    # A local copy of the release ZIP. Must be byte-identical to the release asset, or the hash is wrong.
    [string]$ZipPath,
    [string]$OutDir,
    # Only for rehearsing the manifest format against an old release. Never submit what this produces.
    [switch]$SkipZipCheck,
    [switch]$NoValidate
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot 'out' }
if (-not $Version) {
    $gradle = Get-Content (Join-Path $repoRoot 'desktop/build.gradle.kts') -Raw
    if ($gradle -notmatch 'val lyrenneVersion = "([^"]+)"') { throw 'lyrenneVersion not found in desktop/build.gradle.kts' }
    $Version = $Matches[1]
}
$tag = "v$Version"
$zipName = "Lyrenne-$Version-portable.zip"
$url = "https://github.com/Doctordefector/Lyrenne/releases/download/$tag/$zipName"
Write-Host "Lyrenne $Version"

# 1. The ZIP: the release asset unless a local copy is given.
New-Item -ItemType Directory -Force $OutDir | Out-Null
if (-not $ZipPath) {
    $ZipPath = Join-Path $OutDir $zipName
    if (-not (Test-Path $ZipPath)) {
        Write-Host "Downloading $url"
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $url -OutFile $ZipPath -UseBasicParsing
    }
}
$ZipPath = (Resolve-Path $ZipPath).Path
$sha = (Get-FileHash -Algorithm SHA256 $ZipPath).Hash.ToUpperInvariant()
Write-Host "SHA256 $sha"

# 2. The ZIP must be laid out as the manifest says, and must be new enough to be safe under winget.
$zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
try {
    if (-not ($zip.Entries | Where-Object FullName -eq 'Lyrenne/Lyrenne.exe')) {
        throw "Lyrenne/Lyrenne.exe is not in the ZIP; RelativeFilePath would be wrong"
    }
    if (-not $SkipZipCheck) {
        $safe = $false
        # The app's own classes are in desktop-<hash>.jar; look there first.
        $jars = $zip.Entries | Where-Object { $_.FullName -like 'Lyrenne/app/*.jar' } |
            Sort-Object { if ($_.Name -like 'desktop-*') { 0 } else { 1 } }
        foreach ($jar in $jars) {
            $s = $jar.Open()
            try {
                $inner = New-Object System.IO.Compression.ZipArchive($s, [System.IO.Compression.ZipArchiveMode]::Read)
                if ($inner.Entries | Where-Object FullName -eq 'com/lyrenne/desktop/InstallLocation.class') { $safe = $true }
                $inner.Dispose()
            } finally { $s.Dispose() }
            if ($safe) { break }
        }
        if (-not $safe) {
            throw "This build predates InstallLocation: under winget it would keep data in the package folder, which winget deletes on upgrade. Do not submit it."
        }
        Write-Host 'ZIP check: keeps data out of the package folder'
    } else {
        Write-Warning 'ZIP check skipped: rehearsal only, do not submit these manifests'
    }
} finally { $zip.Dispose() }

# 3. Release date from GitHub, today if the release is still a draft.
$date = (Get-Date).ToString('yyyy-MM-dd')
try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/Doctordefector/Lyrenne/releases/tags/$tag" -UseBasicParsing
    if ($rel.published_at) { $date = ([datetime]$rel.published_at).ToString('yyyy-MM-dd') }
} catch {
    Write-Warning "Release $tag not found (draft?), using today's date"
}

# 4. Render.
$dest = Join-Path $OutDir "manifests/d/Doctordefector/Lyrenne/$Version"
New-Item -ItemType Directory -Force $dest | Out-Null
foreach ($t in Get-ChildItem (Join-Path $PSScriptRoot 'templates') -Filter '*.yaml') {
    $text = (Get-Content $t.FullName -Raw).Replace('{{VERSION}}', $Version).Replace('{{RELEASE_DATE}}', $date).Replace('{{SHA256}}', $sha)
    if ($text -match '\{\{') { throw "Unfilled placeholder in $($t.Name)" }
    # winget-pkgs wants UTF-8 without a BOM and LF endings.
    [System.IO.File]::WriteAllText((Join-Path $dest $t.Name), $text.Replace("`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
}
Write-Host "Manifests: $dest"

# 5. Validate.
if ($NoValidate) { return }
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Warning 'winget not found, skipping validation'
    return
}
winget validate --manifest $dest --disable-interactivity
# 0x8A150028 is "succeeded with warnings". An older winget (CI runners lag) warns that it does not
# know the newest schema header; winget-pkgs validates with a current one.
if ($LASTEXITCODE -eq -1978335192) { Write-Warning 'winget validate passed with warnings (see above)'; $global:LASTEXITCODE = 0 }
elseif ($LASTEXITCODE -ne 0) { throw "winget validate failed ($LASTEXITCODE)" }
