# Passwords are protected by Windows DPAPI for this Windows user on this PC.
# Never print/import this file's private configuration into reports or Git.
$ErrorActionPreference = 'Stop'

function Get-SigningProfilePath {
    param([ValidateSet('recipe', 'chikabell')][string]$App)
    Join-Path 'C:\AndroidSigning' "$App.signing.clixml"
}

function Assert-SigningOutsideRepository {
    param([string]$Path, [string]$Repository)
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Repository).TrimEnd('\', '/') + '\'
    if ($full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Signing material must be outside the repository.'
    }
    if ((Get-Item -LiteralPath 'C:\AndroidSigning').Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw 'C:\AndroidSigning must be a real directory, not a junction or symlink.'
    }
    if ((Test-Path -LiteralPath $full) -and
        (Get-Item -LiteralPath $full).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw 'Signing files must not be symlinks.'
    }
}

function Read-LocalSigningProfile {
    param([string]$App, [string]$Repository)
    $path = Get-SigningProfilePath $App
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'Signing is not registered. Run Setup-Signing.cmd once; no build was started.'
    }
    Assert-SigningOutsideRepository $path $Repository
    try { $profile = Import-Clixml -LiteralPath $path } catch {
        throw 'Cannot decrypt signing settings. Run Setup-Signing.cmd with this Windows user on this PC.'
    }
    $expectedStore = Join-Path 'C:\AndroidSigning' "$App-release.p12"
    if ($profile.App -ne $App -or $profile.StoreFile -ne $expectedStore -or
        $profile.StorePassword -isnot [Security.SecureString] -or
        $profile.KeyPassword -isnot [Security.SecureString] -or
        [string]::IsNullOrWhiteSpace($profile.KeyAlias) -or
        $profile.CertificateSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'Invalid signing settings. Run Setup-Signing.cmd again.'
    }
    Assert-SigningOutsideRepository $profile.StoreFile $Repository
    if (-not (Test-Path -LiteralPath $profile.StoreFile -PathType Leaf) -or
        (Get-Item -LiteralPath $profile.StoreFile).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw 'The registered signing key is missing or is a link.'
    }
    if ((Get-FileHash -LiteralPath $profile.StoreFile -Algorithm SHA256).Hash -ne $profile.StoreHash) {
        throw 'The registered signing key changed. Build stopped; verify the existing release key before registering again.'
    }
    return $profile
}

function Convert-SigningPassword {
    param([Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Assert-RegisteredApkSigner {
    param([string]$ApkPath, $Profile, [string]$Repository)
    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME,
        (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk'))
    foreach ($properties in @('android\local.properties', 'local.properties')) {
        $path = Join-Path $Repository $properties
        if (Test-Path -LiteralPath $path) {
            $line = Get-Content -LiteralPath $path | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
            if ($line) { $sdkRoots += ($line.Substring(8) -replace '\\:', ':' -replace '\\\\', '\') }
        }
    }
    $signer = $sdkRoots | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $_ 'build-tools') -Filter apksigner.bat -Recurse -File -ErrorAction SilentlyContinue
    } | Sort-Object { [version]$_.Directory.Name } -Descending | Select-Object -First 1
    if (-not $signer) { throw 'apksigner was not found.' }
    $result = & $signer.FullName verify --print-certs $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $digests = @($result | ForEach-Object {
        if ($_ -match '^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]+)\s*$') { $Matches[1].ToUpperInvariant() }
    })
    if ($digests.Count -ne 1 -or $digests[0] -ne $Profile.CertificateSha256) {
        throw 'APK signer differs from the registered release key. Do not distribute or install this APK.'
    }
}

function Set-PrivateSigningDirectoryPermissions {
    param([Parameter(Mandatory)][string]$Path)
    $directory = [IO.DirectoryInfo]::new($Path)
    # Read and write only the DACL. A fresh DirectorySecurity passed to Set-Acl
    # can also request SACL changes, which require SeSecurityPrivilege.
    $acl = [IO.FileSystemAclExtensions]::GetAccessControl(
        $directory, [Security.AccessControl.AccessControlSections]::Access)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))) {
        [void]$acl.RemoveAccessRuleSpecific($rule)
    }
    foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    }
    [IO.FileSystemAclExtensions]::SetAccessControl($directory, $acl)
}

function Register-LocalSigningProfile {
    param([string]$App, [string]$Repository, [string]$DefaultAlias)
    if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') {
        throw 'Run Setup-Signing.cmd in external PowerShell.'
    }
    New-Item -ItemType Directory -Path 'C:\AndroidSigning' -Force | Out-Null
    Assert-SigningOutsideRepository (Get-SigningProfilePath $App) $Repository
    # Restrict private storage to the current user, SYSTEM and Administrators.
    Set-PrivateSigningDirectoryPermissions -Path 'C:\AndroidSigning'
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = [System.Windows.Forms.OpenFileDialog]::new()
    $dialog.Title = "Select the EXISTING $App release key (do not create a new key)"
    $dialog.Filter = 'PKCS12 (*.p12)|*.p12'
    try {
        if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return }
        $source = $dialog.FileName
    } finally { $dialog.Dispose() }
    $storePassword = Read-Host 'Keystore password (masked with asterisks)' -AsSecureString
    if ($storePassword.Length -eq 0) { throw 'Empty passwords are not supported.' }
    $previous = $env:ANDROID_SIGNING_SETUP_PASSWORD
    try {
        $env:ANDROID_SIGNING_SETUP_PASSWORD = Convert-SigningPassword $storePassword
        $storeInfo = & keytool '-J-Duser.language=en' '-J-Duser.country=US' -list -v -keystore $source '-storepass:env' ANDROID_SIGNING_SETUP_PASSWORD 2>&1
        if ($LASTEXITCODE -ne 0) {
            $failure = $storeInfo -join "`n"
            if ($failure -match 'password was incorrect|password.*incorrect|tampered with|failed to decrypt') {
                throw 'Keystore password verification failed. Check the saved keystore password (not the key password); nothing was registered.'
            }
            if ($failure -match 'does not exist|FileNotFound|cannot find') {
                throw 'keytool could not open the selected file. Check the file path; nothing was registered.'
            }
            throw 'keytool could not read the keystore. Check the keystore format and JDK; nothing was registered.'
        }
        $aliases = @($storeInfo | ForEach-Object {
            if ($_ -match '^Alias name:\s*(.+?)\s*$') { $Matches[1] }
        })
        if ($aliases.Count -eq 0) { throw 'No key aliases were found in the selected keystore.' }
        if ($aliases.Count -eq 1) {
            $alias = $aliases[0]
            Write-Host "Detected key alias: $alias"
        } else {
            Write-Host ('Available aliases: ' + ($aliases -join ', '))
            $alias = Read-Host "Key alias (Enter = $DefaultAlias)"
            if ([string]::IsNullOrWhiteSpace($alias)) { $alias = $DefaultAlias }
            if ($alias -notin $aliases) { throw 'Selected alias does not exist; nothing was registered.' }
        }
        $keyInfo = & keytool '-J-Duser.language=en' '-J-Duser.country=US' -list -v -keystore $source -alias $alias '-storepass:env' ANDROID_SIGNING_SETUP_PASSWORD 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Selected alias could not be read; nothing was registered.' }
        if (-not ($keyInfo -match 'Entry type: PrivateKeyEntry') -or ($keyInfo -match 'Owner:.*CN=Android Debug')) {
            throw 'Select an existing release private key, not a certificate-only or debug keystore.'
        }
        $digestLine = $keyInfo | Where-Object { $_ -match '^\s*SHA256:\s*([A-Fa-f0-9:]+)\s*$' } | Select-Object -First 1
        if (-not $digestLine -or $digestLine -notmatch '^\s*SHA256:\s*([A-Fa-f0-9:]+)\s*$') {
            throw 'Could not identify the release certificate.'
        }
        $certificateSha256 = $Matches[1].Replace(':', '').ToUpperInvariant()
    } finally { $env:ANDROID_SIGNING_SETUP_PASSWORD = $previous }
    $keyPassword = Read-Host 'Key password (Enter = same as keystore, masked)' -AsSecureString
    if ($keyPassword.Length -eq 0) { $keyPassword = $storePassword.Copy() }
    $destination = Join-Path 'C:\AndroidSigning' "$App-release.p12"
    Assert-SigningOutsideRepository $destination $Repository
    $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if (Test-Path -LiteralPath $destination) {
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash) {
            throw 'A different key already exists in C:\AndroidSigning. It was not overwritten.'
        }
    } else { Copy-Item -LiteralPath $source -Destination $destination }
    [pscustomobject]@{
        App = $App; StoreFile = $destination; StoreHash = $hash; KeyAlias = $alias; CertificateSha256 = $certificateSha256
        StorePassword = $storePassword; KeyPassword = $keyPassword
    } | Export-Clixml -LiteralPath (Get-SigningProfilePath $App)
    Write-Host 'Signing registered. From now on, use Build-Release.cmd without password entry.'
}
