param(
    [string]$OutputDirectory = "dist"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root "gradlew.bat"
$output = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }

& $gradle testDebugUnitTest lintRelease assembleRelease printReleaseSigningStatus
if ($LASTEXITCODE -ne 0) { throw "Release verification failed." }

$apk = Get-ChildItem -LiteralPath (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -File |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $apk) { throw "Release APK was not generated." }

$gradleConfig = Get-Content -LiteralPath (Join-Path $root "app\build.gradle.kts") -Raw
$versionMatch = [regex]::Match($gradleConfig, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) { throw "versionName was not found in app/build.gradle.kts." }
$versionName = $versionMatch.Groups[1].Value
$targetName = "chikabell-v$versionName.apk"

$apksigner = Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools") -Filter "apksigner.bat" -File -Recurse |
    Sort-Object { [version]$_.Directory.Name } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $apksigner -or -not (Test-Path $apksigner)) { throw "apksigner was not found." }
& $apksigner verify --verbose --print-certs $apk.FullName *> $null
if ($LASTEXITCODE -ne 0) {
    throw "The release APK is unsigned or has an invalid signature. Nothing was copied to dist."
}

$target = Join-Path $output $targetName
New-Item -ItemType Directory -Path $output -Force | Out-Null
Copy-Item -LiteralPath $apk.FullName -Destination $target -Force
$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $output "SHA256SUMS.txt") -Value "$hash  $targetName" -Encoding utf8NoBOM

Write-Host "APK: $target"
Write-Host "SHA-256: $hash"
Write-Host "Signed: True"
