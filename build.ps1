# Builds the Frigate Android app. Optional: -Install pushes it to the one adb device
# given by -Device (e.g. a phone with wireless debugging on).
#   .\build.ps1
#   .\build.ps1 -Install -Device 192.168.1.50:5555
param(
    [switch]$Install,
    [string]$Device
)
$ErrorActionPreference = "Stop"

# Android Gradle Plugin 8 needs JDK 17; the machine default is JDK 8.
$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $jdk) { throw "No JDK 17 under $env:USERPROFILE\.jdks" }
$env:JAVA_HOME = $jdk.FullName

Push-Location $PSScriptRoot
try {
    & .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    Write-Host "APK: $apk"

    if ($Install) {
        $adb = "$env:LOCALAPPDATA\Android\sdk\platform-tools\adb.exe"
        if ($Device) { & $adb -s $Device install -r $apk } else { & $adb install -r $apk }
    }
} finally {
    Pop-Location
}
