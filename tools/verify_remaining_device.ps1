param([string]$Serial, [switch]$Launch)
$ErrorActionPreference = 'Stop'
function Invoke-DeviceRead {
    param([string[]]$AdbArguments)
    $result = & adb -s $Serial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'ADB device read failed. Check the connection and USB debugging.' }
    return ($result -join [Environment]::NewLine).Trim()
}
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $connected = & adb devices 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'ADB is unavailable.' }
    $targets = @($connected | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    $pixels = @()
    foreach ($target in $targets) {
        $model = & adb -s $target shell getprop ro.product.model 2>&1
        if ($LASTEXITCODE -eq 0 -and ($model -join '').Trim() -eq 'Pixel 10a') { $pixels += $target }
    }
    if ($pixels.Count -ne 1) { throw 'Exactly one connected Pixel 10a is required. No device operation was performed.' }
    $Serial = $pixels[0]
}
if ((Invoke-DeviceRead @('shell','getprop','ro.product.model')) -ne 'Pixel 10a') { throw 'The target is not Pixel 10a.' }
$beforeRotation = @{
    accelerometer_rotation = Invoke-DeviceRead @('shell','settings','get','system','accelerometer_rotation')
    user_rotation = Invoke-DeviceRead @('shell','settings','get','system','user_rotation')
}
if ($Launch) { $null = Invoke-DeviceRead @('shell','am','start','-n','com.chikabell.app/.MainActivity') }
$package = Invoke-DeviceRead @('shell','dumpsys','package','com.chikabell.app')
$exitInfo = Invoke-DeviceRead @('shell','dumpsys','activity','exit-info','com.chikabell.app')
$services = Invoke-DeviceRead @('shell','dumpsys','activity','services','com.chikabell.app')
$afterRotation = @{
    accelerometer_rotation = Invoke-DeviceRead @('shell','settings','get','system','accelerometer_rotation')
    user_rotation = Invoke-DeviceRead @('shell','settings','get','system','user_rotation')
}
foreach ($key in $beforeRotation.Keys) {
    if ($beforeRotation[$key] -ne $afterRotation[$key]) { throw 'Rotation changed during verification. No setting was modified by this script.' }
}
$versionName = [regex]::Match($package, 'versionName=([^\s]+)').Groups[1].Value
$versionCode = [regex]::Match($package, 'versionCode=(\d+)').Groups[1].Value
if (-not $versionName -or -not $versionCode) { throw 'Installed package identity could not be read.' }
$reasonRecords = [regex]::Matches($exitInfo, '\breason=\d+\s*\(')
$anrCount = if ($reasonRecords.Count -gt 0) { [regex]::Matches($exitInfo, '\breason=6\s*\(ANR\)').Count } else { $null }
$crashCount = if ($reasonRecords.Count -gt 0) { [regex]::Matches($exitInfo, '\breason=(4|5)\s*\(').Count } else { $null }
$result = [ordered]@{
    checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
    device = 'Pixel 10a'
    android = Invoke-DeviceRead @('shell','getprop','ro.build.version.release')
    versionName = $versionName
    versionCode = [int]$versionCode
    rotationBefore = $beforeRotation
    rotationAfter = $afterRotation
    anrRecordsRetained = $anrCount
    crashRecordsRetained = $crashCount
    verificationServiceRecords = [regex]::Matches($services, 'ServiceRecord[^\r\n]*NearbyVerificationService').Count
    launchRequested = [bool]$Launch
    naturalGeofenceQa = 'UNVERIFIED unless separately recorded'
    twelveHourExpiry = 'UNVERIFIED unless measured with real elapsed time'
    rebootRestore = 'UNVERIFIED unless separately approved and measured'
}
$result | ConvertTo-Json -Depth 5
