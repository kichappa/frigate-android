# Publishes a new version. Installed apps offer it at their next launch.
#   .\release.ps1 1.0.1 "What changed, shown in the update prompt"
# Bumps appVersionName, builds the APK signed with the release key, commits everything,
# tags vX.Y.Z, pushes, and creates the GitHub release with the APK attached.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Version must look like 1.2.3" }
if (-not (Test-Path "$PSScriptRoot\keystore.properties")) {
    throw "keystore.properties is missing. Releases must be signed with the release key, or phones refuse the update."
}
if (-not $Notes) { $Notes = "Version $Version." }

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = "$env:USERPROFILE\.local\gh\bin\gh.exe" }

$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $jdk) { throw "No JDK 17 under $env:USERPROFILE\.jdks" }
$env:JAVA_HOME = $jdk.FullName

Push-Location $PSScriptRoot
try {
    if (git tag --list "v$Version") { throw "Tag v$Version already exists" }

    $props = [IO.File]::ReadAllText("$PSScriptRoot\gradle.properties")
    $props = $props -replace '(?m)^appVersionName=.*$', "appVersionName=$Version"
    [IO.File]::WriteAllText("$PSScriptRoot\gradle.properties", $props)

    & .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    & .\gradlew.bat --stop | Out-Null   # the daemon holds ~1 GB

    $apk = "app\build\outputs\apk\release\frigate-$Version.apk"
    Copy-Item "app\build\outputs\apk\release\app-release.apk" $apk -Force

    git add -A
    git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) {
        git commit -m "Release v$Version"
        if ($LASTEXITCODE -ne 0) { throw "git commit failed" }
    }
    git tag "v$Version"
    git push origin HEAD --tags
    if ($LASTEXITCODE -ne 0) { throw "git push failed" }

    & $gh release create "v$Version" $apk --title "v$Version" --notes $Notes
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
    Write-Host "Released v$Version"
} finally {
    Pop-Location
}
