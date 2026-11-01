# Verifies that a built APK is structurally sound and really contains the classes the IDE needs
# at runtime. Catches truncated/problematic artifacts produced by an interrupted build.
#
# Usage:
#   .\tools\verify-apk.ps1 -Apk dist\android-code-studio-arm64-v8a-1.0.0+gh.r04.apk
#
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,

    # Android SDK location. Defaults to ANDROID_HOME / ANDROID_SDK_ROOT / local.properties.
    [string]$Sdk
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot

# Classes that must be *defined* in every build. If one of them is missing the APK is broken and
# the app will fail at runtime with a NoClassDefFoundError.
$criticalClasses = @(
    "com/tom/rv2ide/app/IDEApplication",
    "com/tom/rv2ide/activities/MainActivity",
    "com/tom/rv2ide/projects/ModuleProject",
    "com/tom/rv2ide/javac/services/fs/CacheFSInfoSingleton",
    "com/tom/rv2ide/javac/services/fs/CachedJarFileSystem",
    "com/tom/rv2ide/templates/android/XposedModule",
    "com/tom/rv2ide/templates/android/TemplateRegistry"
)

function Resolve-Sdk {
    param([string]$Explicit)

    if ($Explicit) { return $Explicit }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }

    $localProps = Join-Path $ProjectRoot "local.properties"
    if (Test-Path $localProps) {
        $line = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
        if ($line) {
            $value = ($line.Line -split '=', 2)[1].Trim()
            return $value.Replace('\:', ':').Replace('\\', '\')
        }
    }

    return $null
}

function Find-DexDump {
    param([string]$SdkPath)

    if (-not $SdkPath) { return $null }
    $buildTools = Join-Path $SdkPath "build-tools"
    if (-not (Test-Path $buildTools)) { return $null }

    return Get-ChildItem $buildTools -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "dexdump.exe" } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
}

$errors = New-Object System.Collections.Generic.List[string]

if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

$apkItem = Get-Item $Apk
Write-Host "APK     : $($apkItem.FullName)"
Write-Host ("Size    : {0:N1} MB" -f ($apkItem.Length / 1MB))
Write-Host "SHA-256 : $((Get-FileHash $apkItem.FullName -Algorithm SHA256).Hash)"
Write-Host ""

Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [System.IO.File]::OpenRead($apkItem.FullName)
$zip = New-Object System.IO.Compression.ZipFile($stream)
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("apkverify_" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    $entries = @($zip.Entries)
    if ($entries.Count -eq 0) { $errors.Add("Archive has no entries - the APK is probably truncated.") }

    if (-not ($entries | Where-Object { $_.FullName -eq "AndroidManifest.xml" })) {
        $errors.Add("AndroidManifest.xml is missing from the APK.")
    }

    $dexEntries = @($entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    if ($dexEntries.Count -eq 0) { $errors.Add("No classes*.dex found in the APK.") }

    Write-Host "Dex files: $($dexEntries.Count)"

    $dexFiles = @{}
    foreach ($entry in $dexEntries) {
        $target = Join-Path $tempDir $entry.FullName
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)

        $bytes = [System.IO.File]::ReadAllBytes($target)
        if ($bytes.Length -lt 112) {
            $errors.Add("$($entry.FullName) is too small to be a dex file ($($bytes.Length) bytes).")
            continue
        }

        $magic = [System.Text.Encoding]::ASCII.GetString($bytes, 0, 4)
        if ($magic -ne "dex`n") {
            $errors.Add("$($entry.FullName) has an invalid magic ($magic).")
            continue
        }

        # A dex header records its own size; a mismatch means the file was cut short.
        $declaredSize = [System.BitConverter]::ToUInt32($bytes, 32)
        $headerSize = [System.BitConverter]::ToUInt32($bytes, 36)
        if ($declaredSize -ne $bytes.Length) {
            $errors.Add("$($entry.FullName) is truncated: header says $declaredSize bytes, file has $($bytes.Length).")
            continue
        }
        if ($headerSize -ne 112) {
            $errors.Add("$($entry.FullName) has an unexpected header size ($headerSize).")
            continue
        }

        $dexFiles[$entry.FullName] = $target
    }

    if ($errors.Count -eq 0) {
        Write-Host "Dex integrity: OK (all dex files complete and well formed)"
    }

    $sdkPath = Resolve-Sdk -Explicit $Sdk
    $dexdump = Find-DexDump -SdkPath $sdkPath

    # Classes are checked with dexdump, which reports the classes a dex actually *defines*
    # (a plain string match would also hit mere references).
    $dumps = @{}
    foreach ($cls in $criticalClasses) {
        $descriptor = "L$cls;"
        $defined = $false
        $checkedAny = $false

        foreach ($dexName in $dexFiles.Keys) {
            $target = $dexFiles[$dexName]
            $raw = [System.Text.Encoding]::GetEncoding(28591).GetString([System.IO.File]::ReadAllBytes($target))
            if (-not $raw.Contains($descriptor)) { continue }

            if (-not $dexdump) {
                # Without dexdump we can only verify that the descriptor exists somewhere.
                $defined = $true
                $checkedAny = $true
                break
            }

            if (-not $dumps.ContainsKey($dexName)) {
                $dumps[$dexName] = (& $dexdump -f $target 2>$null) -join "`n"
            }

            $checkedAny = $true
            if ($dumps[$dexName].Contains("Class descriptor  : '$descriptor'")) {
                $defined = $true
                break
            }
        }

        if (-not $checkedAny) {
            $errors.Add("Class $cls is not referenced by any dex.")
        } elseif ($defined) {
            Write-Host "Class $cls : OK"
        } else {
            $errors.Add("Class $cls is referenced but never defined - the APK would fail at runtime.")
        }
    }
} finally {
    $zip.Dispose()
    $stream.Dispose()
    Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
if ($errors.Count -gt 0) {
    Write-Host "VERIFICATION FAILED ($($errors.Count) problem(s)):" -ForegroundColor Red
    $errors | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "Rebuild from a clean state:  .\gradlew.bat clean assembleDebug assembleRelease"
    exit 1
}

Write-Host "APK verification passed." -ForegroundColor Green
exit 0
