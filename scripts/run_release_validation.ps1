param(
    [switch]$IncludeConnectedTests,
    [switch]$SkipUnitTests,
    [switch]$SkipReleaseBuild
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

$javaHome = "C:\Program Files\Android\Android Studio\jbr"
if (-not (Test-Path $javaHome)) {
    throw "Java runtime not found at $javaHome"
}

$env:JAVA_HOME = $javaHome
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
Write-Host "Project root: $projectRoot"

if (-not $SkipUnitTests) {
    Write-Host "Running unit tests..."
    .\gradlew testDebugUnitTest
}

if (-not $SkipReleaseBuild) {
    Write-Host "Building minified release..."
    .\gradlew assembleRelease
}

if ($IncludeConnectedTests) {
    Write-Host "Checking connected Android devices..."
    $adbOutput = & adb devices
    $deviceLines = $adbOutput | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }

    if ($deviceLines.Count -eq 0) {
        Write-Host "No connected devices detected. Skipping connected tests."
    } else {
        Write-Host "Running connected instrumentation tests..."
        .\gradlew connectedDebugAndroidTest
    }
}

Write-Host "Release validation script completed."
