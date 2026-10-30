param([switch]$SetupDatabase)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDirectory
& (Join-Path $PSScriptRoot 'Start-ReadingDatabase.ps1') -Setup:$SetupDatabase
$java21 = $false
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'release'))) {
    $java21 = [bool](Select-String -LiteralPath (Join-Path $env:JAVA_HOME 'release') -Pattern 'JAVA_VERSION="21[.]')
}
if (!$java21) {
    $jdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '21' } | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    else { throw 'Set JAVA_HOME to an installed JDK 21 before starting the backend.' }
}
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
# Clear obsolete compiled classes after feature removal before starting the backend.
& (Join-Path $projectDirectory 'mvnw.cmd') clean spring-boot:run '-Dspring-boot.run.profiles=reading' '-Dspring-boot.run.arguments=--logging.level.org.springframework.ai=INFO'
if ($LASTEXITCODE -ne 0) { throw 'Reading backend failed to start. Check database and model configuration.' }
