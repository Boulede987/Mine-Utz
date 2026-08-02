<#
.SYNOPSIS
    Runs the mineutze-worldgen standalone region-solve export (PNG/ASCII/JSON),
    so you don't have to remember the gradle command or JAVA_HOME override.

.PARAMETER WorldSeed
    World seed to solve. Defaults to 42.

.PARAMETER RegionSpan
    NxN region grid span, centered on the world origin/spawn. Default 3.

.PARAMETER OutputDir
    Output directory, relative to worldgen-core/. Default "build/export".

.PARAMETER Open
    Open the resulting PNG when done.

.EXAMPLE
    .\export-biomes.ps1

.EXAMPLE
    .\export-biomes.ps1 -RegionSpan 5 -OutputDir build/spawn-check -Open
#>
param(
    [long]$WorldSeed = 42,
    [int]$RegionSpan = 3,
    [string]$OutputDir = "build/export",
    [switch]$Open
)

$ErrorActionPreference = "Stop"

# This project needs JDK 21; the system default `java` on this machine resolves to
# Java 8, which fails resolving the NeoForge gradle plugin. Edit this path if your
# JDK 21 install lives elsewhere.
$javaHome = "C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"
if (-not (Test-Path "$javaHome\bin\java.exe")) {
    throw "JDK 21 not found at '$javaHome'. Edit `$javaHome` at the top of export-biomes.ps1 to point at your JDK 21 install."
}
$env:JAVA_HOME = $javaHome

Push-Location $PSScriptRoot
try {
    & .\gradlew.bat ":worldgen-core:run" "--console=plain" "--args=$WorldSeed $RegionSpan $OutputDir"
    if ($LASTEXITCODE -ne 0) {
        throw "gradlew run failed (exit code $LASTEXITCODE)"
    }
} finally {
    Pop-Location
}

if ($Open) {
    $pngPath = Join-Path $PSScriptRoot "worldgen-core\$OutputDir\regions.png"
    if (Test-Path $pngPath) {
        Invoke-Item $pngPath
    } else {
        Write-Warning "Expected PNG not found at '$pngPath'."
    }
}
