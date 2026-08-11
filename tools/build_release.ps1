param(
    [string]$OutputDirectory = "dist"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root "gradlew.bat"
$output = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }

$requiredSigningVariables = @(
    "CHIKABELL_KEYSTORE_PATH",
    "CHIKABELL_KEYSTORE_PASSWORD",
    "CHIKABELL_KEY_ALIAS",
    "CHIKABELL_KEY_PASSWORD"
)
$missingSigningVariables = @(
    $requiredSigningVariables | Where-Object {
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
    }
)
if ($missingSigningVariables.Count -gt 0) {
    throw "Release signing is not configured. Missing: $($missingSigningVariables -join ', '). No distributable APK was created."
}

$releaseStoreFile = [Environment]::GetEnvironmentVariable("CHIKABELL_KEYSTORE_PATH")
if (-not (Test-Path -LiteralPath $releaseStoreFile -PathType Leaf)) {
    throw "CHIKABELL_KEYSTORE_PATH does not point to a file. No distributable APK was created."
}

$sdkRoots = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" })
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Container) } | Select-Object -Unique
$apksigner = $sdkRoots | ForEach-Object {
    Get-ChildItem -LiteralPath (Join-Path $_ "build-tools") -Filter "apksigner.bat" -File -Recurse -ErrorAction SilentlyContinue
} | Sort-Object { [version]$_.Directory.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $apksigner -or -not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw "Android apksigner was not found. No distributable APK was created."
}

& $gradle testDebugUnitTest lintRelease assembleRelease printReleaseSigningStatus
if ($LASTEXITCODE -ne 0) { throw "Release verification failed." }

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "A signed release APK was not generated at the expected path. No distributable APK was created."
}

& $apksigner verify --verbose --print-certs $apk *> $null
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed. No distributable APK was created."
}

New-Item -ItemType Directory -Path $output -Force | Out-Null
$targetName = "chikabell-v0.1.2.apk"
$target = Join-Path $output $targetName
Copy-Item -LiteralPath $apk -Destination $target -Force

$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $output "SHA256SUMS.txt") -Value "$hash  $targetName" -Encoding utf8NoBOM

Write-Host "APK: $target"
Write-Host "SHA-256: $hash"
Write-Host "Signed: true"
