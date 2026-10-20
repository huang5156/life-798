# Build the WaterWidget APK on Windows.
#
# Requires:
#   - JDK 21 (JAVA_HOME or on PATH)
#   - Android SDK with "platforms;android-37.0" and "build-tools;36.0.0"
#     (install via Android Studio, or: sdkmanager "platforms;android-37.0" "build-tools;36.0.0")
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Variant release
#
# Output: .\dist\WaterWidget-<version>-<variant>.apk

param(
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

function Find-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return $env:JAVA_HOME
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { return (Split-Path (Split-Path $java.Source -Parent) -Parent) }
    throw "JDK 21 not found. Set JAVA_HOME or install a JDK."
}

function Find-Sdk {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    $localProps = Join-Path $root 'local.properties'
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $candidates += ($line -replace '^\s*sdk\.dir\s*=\s*', '' -replace '\\\\', '\') }
    }
    $candidates += @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        'C:\Android\Sdk',
        'H:\Android program\SDK'
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'platforms'))) { return $c }
    }
    return $null
}

$javaHome = Find-Java
$env:JAVA_HOME = $javaHome
Write-Host "JAVA_HOME = $javaHome"

$sdk = Find-Sdk
if (-not $sdk) {
    throw "Android SDK not found. Install it (Android Studio) or set ANDROID_HOME / create local.properties with sdk.dir=..."
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Host "ANDROID_HOME = $sdk"

# Make the SDK path visible to Gradle without touching version control.
$sdkForProps = $sdk.Replace('\', '\\')
Set-Content -LiteralPath (Join-Path $root 'local.properties') -Value "sdk.dir=$sdkForProps" -Encoding ascii

if (-not (Test-Path (Join-Path $root 'secrets.properties'))) {
    Write-Warning "secrets.properties is missing: the APK will build but cannot reach the server."
}

$task = if ($Variant -eq 'release') { ':app:assembleRelease' } else { ':app:assembleDebug' }
Write-Host "Running gradlew $task ..."
& (Join-Path $root 'gradlew.bat') $task --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }

$apkDir = Join-Path $root "app\build\outputs\apk\$Variant"
$apk = Get-ChildItem $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $apk) { throw "APK not found under $apkDir" }

$versionLine = Select-String -Path (Join-Path $root 'app\build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
$version = if ($versionLine) { $versionLine.Matches[0].Groups[1].Value } else { 'unknown' }

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$target = Join-Path $dist ("WaterWidget-{0}-{1}.apk" -f $version, $Variant)
Copy-Item -LiteralPath $apk.FullName -Destination $target -Force

Write-Host ""
Write-Host "APK: $target"
