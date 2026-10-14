param(
    [string]$OutputDirectory = "dist"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root "gradlew.bat"
$output = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }
. (Join-Path $PSScriptRoot 'local-signing.ps1')
$savedSigning = Read-LocalSigningProfile -App chikabell -Repository $root
$signingEnvironment = @{
    CHIKABELL_KEYSTORE_PATH = $savedSigning.StoreFile
    CHIKABELL_KEYSTORE_PASSWORD = Convert-SigningPassword $savedSigning.StorePassword
    CHIKABELL_KEY_ALIAS = $savedSigning.KeyAlias
    CHIKABELL_KEY_PASSWORD = Convert-SigningPassword $savedSigning.KeyPassword
}
$previousEnvironment = @{}
foreach ($name in $signingEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
foreach ($name in $signingEnvironment.Keys) {
    [Environment]::SetEnvironmentVariable($name, $signingEnvironment[$name], 'Process')
}

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

Push-Location -LiteralPath $root
try {
    & $gradle --no-daemon --no-configuration-cache :app:testDebugUnitTest :siwc:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:printReleaseSigningStatus
} finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "Release verification failed." }

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "A signed release APK was not generated at the expected path. No distributable APK was created."
}

& $apksigner verify --verbose --print-certs $apk *> $null
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed. No distributable APK was created."
}

Assert-RegisteredApkSigner -ApkPath $apk -Profile $savedSigning -Repository $root
New-Item -ItemType Directory -Path $output -Force | Out-Null
$versionMatch = [regex]::Match([IO.File]::ReadAllText((Join-Path $root 'app/build.gradle.kts')), 'versionName\s*=\s*"(?<version>[A-Za-z0-9._-]+)"')
if (-not $versionMatch.Success) { throw 'Release versionName could not be determined. No distributable APK was copied.' }
$targetName = 'chikabell-v' + $versionMatch.Groups['version'].Value + '.apk'
$target = Join-Path $output $targetName
Copy-Item -LiteralPath $apk -Destination $target -Force

$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $output "SHA256SUMS.txt") -Value "$hash  $targetName" -Encoding utf8NoBOM

Write-Host "APK: $target"
Write-Host "SHA-256: $hash"
Write-Host "Signed: true"
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    $signingEnvironment.Clear()
    $savedSigning = $null
}
