param(
    [string]$PackageName = "com.chikabell.app",
    [string]$DeviceSerial = "",
    [switch]$AsJson
)

$ErrorActionPreference = "Stop"

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    Write-Error "adb.exe was not found at $adb"
    exit 1
}

$devicesOutput = & $adb devices -l
$deviceLines = $devicesOutput |
    Where-Object { $_ -match "\sdevice\s" } |
    Where-Object { $_ -notmatch "^List of devices" }

if ($DeviceSerial) {
    $deviceLines = $deviceLines | Where-Object { $_ -like "$DeviceSerial*" }
}

if (-not $deviceLines) {
    Write-Error "No authorized Android device is connected."
    exit 1
}

if ($deviceLines.Count -gt 1) {
    Write-Error "Multiple authorized devices are connected. Pass -DeviceSerial <serial>."
    exit 1
}

$serial = (($deviceLines | Select-Object -First 1) -split "\s+")[0]
$accelerometerRotation = ((& $adb -s $serial shell settings get system accelerometer_rotation) | Select-Object -First 1).Trim()
$userRotation = ((& $adb -s $serial shell settings get system user_rotation) | Select-Object -First 1).Trim()

$activity = ((& $adb -s $serial shell cmd package resolve-activity --brief $PackageName) | Select-Object -Last 1).Trim()
if (-not $activity -or $activity -notmatch "/") {
    Write-Error "Could not resolve launch activity for $PackageName."
    exit 1
}

& $adb -s $serial shell am start -n $activity | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to launch $PackageName."
    exit 1
}

$accelerometerRotationAfter = ((& $adb -s $serial shell settings get system accelerometer_rotation) | Select-Object -First 1).Trim()
$userRotationAfter = ((& $adb -s $serial shell settings get system user_rotation) | Select-Object -First 1).Trim()
$preserved = (
    $accelerometerRotationAfter -eq $accelerometerRotation -and
    $userRotationAfter -eq $userRotation
)

$result = [ordered]@{
    schema_version = 1
    package_name = $PackageName
    activity = $activity
    device_serial = $serial
    accelerometer_rotation_before = $accelerometerRotation
    accelerometer_rotation_after = $accelerometerRotationAfter
    user_rotation_before = $userRotation
    user_rotation_after = $userRotationAfter
    rotation_preserved = $preserved
    settings_write_performed = $false
}

if ($AsJson) {
    $result | ConvertTo-Json -Depth 4
} else {
    Write-Host "Launched $PackageName on $serial without writing rotation settings. rotation_preserved=$preserved"
}

if (-not $preserved) {
    Write-Error "Rotation settings changed during launch. No settings write was performed; investigate before continuing."
    exit 1
}
