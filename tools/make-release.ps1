<#
  Builds a signed release and writes the three files to upload to a GitHub Release:
    dist\playhead.apk   dist\betteryt.user.js   dist\manifest.json
  The signing key stays outside the repo: pass -Signing (path to signing.properties) or set PLAYHEAD_SIGNING.
  Use -SkipBuild to package the release APK that was already built.
  This script never uploads or publishes anything.
#>
param(
    [string]$Signing = $env:PLAYHEAD_SIGNING,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $root 'android\app\build.gradle.kts'
$script = Join-Path $root 'core\betteryt.user.js'
$dist = Join-Path $root 'dist'

if (-not $Signing -or -not (Test-Path $Signing)) { throw 'No signing.properties found. Pass -Signing <path> or set PLAYHEAD_SIGNING.' }

if (-not $SkipBuild) {
    $env:PLAYHEAD_SIGNING = $Signing
    Push-Location (Join-Path $root 'android')
    try { & .\gradlew.bat assembleRelease --console=plain --no-daemon; if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' } }
    finally { Pop-Location }
}

$apk = Join-Path $root 'android\app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "Signed APK not found: $apk (an unsigned build is named app-release-unsigned.apk)" }

$gradle = Get-Content $gradleFile -Raw
$code = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$scriptVersion = [regex]::Match((Get-Content $script -Raw), '@version\s+(\S+)').Groups[1].Value
if (-not $code -or -not $name -or -not $scriptVersion) { throw 'Could not read the versions.' }

New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $apk (Join-Path $dist 'playhead.apk') -Force
Copy-Item $script (Join-Path $dist 'betteryt.user.js') -Force
$manifest = [ordered]@{
    script = [ordered]@{ version = $scriptVersion; file = 'betteryt.user.js'; sha256 = (Get-FileHash (Join-Path $dist 'betteryt.user.js') -Algorithm SHA256).Hash.ToLower() }
    app    = [ordered]@{ versionCode = $code; versionName = $name; file = 'playhead.apk'; sha256 = (Get-FileHash (Join-Path $dist 'playhead.apk') -Algorithm SHA256).Hash.ToLower() }
}
[IO.File]::WriteAllText((Join-Path $dist 'manifest.json'), ($manifest | ConvertTo-Json -Depth 4))
"Release $name (code $code), script $scriptVersion written to $dist"
Get-ChildItem $dist | Select-Object Name, Length
