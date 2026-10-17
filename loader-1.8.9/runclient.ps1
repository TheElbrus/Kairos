# Launch the Kairos Forge 1.8.9 dev client (Windows PowerShell).
#
# Why this script exists:
#   Loom 0.10.0.5 + Gradle 8.8 reject `.\gradlew.bat runClient` during task validation
#   ("property 'main' is missing an input or output annotation"), because
#   net.fabricmc.loom.task.RunGameTask extends JavaExec but exposes an unannotated
#   getMain(). See docs/JOURNAL.md.
#
#   So instead we ask the build for exactly what Loom would run
#   (task `printRunClientCommand`), then launch it by hand on the Java 8 JDK that
#   Loom provisioned INSIDE the repo. Nothing outside the repo is touched.
#
# Usage:
#   .\runclient.ps1                                 # launch the dev client
#   $env:KAIROS_PRINT_ONLY="1"; .\runclient.ps1     # print the command, do not launch
#
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $repo ".gradle-home" }

# JDK used to run Gradle itself (Gradle 8.8 needs 17+; the game needs Java 8).
# Try, in order: KAIROS_GRADLE_JDK, JAVA_HOME, the JDKs found in this machine's ~/.jdks.
$candidates = @()
if ($env:KAIROS_GRADLE_JDK) { $candidates += $env:KAIROS_GRADLE_JDK }
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += (Join-Path $env:USERPROFILE ".jdks\ms-21.0.12.1")
$candidates += (Join-Path $env:USERPROFILE ".jdks\temurin-25.0.4.1")
$gradleJdk = $null
foreach ($cand in $candidates) {
    if ($cand -and (Test-Path (Join-Path $cand "bin\java.exe"))) { $gradleJdk = $cand; break }
}
if (-not $gradleJdk) {
    Write-Error "[runclient] No JDK found to run Gradle. Set JAVA_HOME or KAIROS_GRADLE_JDK."
    exit 1
}

# Java 8 JDK auto-provisioned by the foojay resolver (real JDK, has javac).
# Glob so a patch-version bump of the provisioned JDK does not break the script.
$java8 = Get-ChildItem (Join-Path $gradleHome "jdks\temurin-8-*\jdk8u*\bin\java.exe") -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $java8) {
    Write-Error "[runclient] Java 8 JDK not found under $gradleHome\jdks\temurin-8-*\. Build the loader first."
    exit 1
}

Write-Host "[runclient] asking Gradle for the launch command..."
$env:JAVA_HOME = $gradleJdk
$env:GRADLE_USER_HOME = $gradleHome
$out = & (Join-Path $here "gradlew.bat") -q printRunClientCommand
if ($LASTEXITCODE -ne 0) {
    Write-Error "[runclient] gradlew printRunClientCommand failed."
    exit 1
}

function Get-LaunchValue([string]$prefix) {
    $line = $out | Where-Object { $_.StartsWith($prefix) } | Select-Object -First 1
    if ($null -eq $line) { return "" }
    return $line.Substring($prefix.Length)
}

$main = Get-LaunchValue "KAIROS_MAIN="
$argsRaw = Get-LaunchValue "KAIROS_ARGS="
$jvmArgsRaw = Get-LaunchValue "KAIROS_JVMARGS="
$cp = Get-LaunchValue "KAIROS_CP="
if (-not $main -or -not $cp) {
    $out | Write-Host
    Write-Error "[runclient] Gradle did not print a launch command."
    exit 1
}

# Loom separates args with byte 0x01.
$gameArgs = if ($argsRaw) { $argsRaw -split [char]1 } else { @() }
$jvmArgs = if ($jvmArgsRaw) { $jvmArgsRaw -split [char]1 } else { @() }

# The game's working directory (config/, saves/, logs/ live here). Gitignored.
$runDir = Join-Path $here "run"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$log = Join-Path $runDir "runclient.log"

if ($env:KAIROS_PRINT_ONLY -eq "1") {
    Write-Host "cd $runDir"
    Write-Host "$($java8.FullName) $($jvmArgs -join ' ') -cp $cp $main $($gameArgs -join ' ')"
    exit 0
}

Write-Host "[runclient] launching $main on Java 8"
Write-Host "[runclient] working dir: $runDir"
Write-Host "[runclient] log:         $log"
Write-Host "[runclient] (close the game window to stop)"
Write-Host ""

Push-Location $runDir
try {
    & $java8.FullName @jvmArgs -cp $cp $main @gameArgs 2>&1 | Tee-Object -FilePath $log
} finally {
    Pop-Location
}
