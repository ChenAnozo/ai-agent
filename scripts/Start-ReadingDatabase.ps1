param([switch]$Setup, [switch]$Stop)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectDirectory '.reading-runtime'
$dataDirectory = Join-Path $projectDirectory '.reading-data'
$pgRoot = Join-Path $runtimeDirectory 'pgsql'
$clusterDirectory = Join-Path $dataDirectory 'postgres'
New-Item -ItemType Directory -Force -Path $runtimeDirectory, $dataDirectory | Out-Null

function Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Executable (exit $LASTEXITCODE)" }
}

if ($Setup -and !(Test-Path -LiteralPath (Join-Path $pgRoot 'bin/pg_ctl.exe'))) {
    $archive = Join-Path $runtimeDirectory 'postgresql.zip'
    if (!(Test-Path -LiteralPath $archive)) {
        Write-Host 'Downloading native PostgreSQL 17.11 (about 364 MiB)...'
        Invoke-WebRequest -Uri 'https://get.enterprisedb.com/postgresql/postgresql-17.11-5-windows-x64-binaries.zip' -OutFile $archive
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $runtimeDirectory -Force
}
if (!(Test-Path -LiteralPath (Join-Path $pgRoot 'bin/pg_ctl.exe'))) { throw 'Run with -Setup first, or use your installed PostgreSQL and the configuration in docs/reading.md.' }
if ($Stop) { Checked (Join-Path $pgRoot 'bin/pg_ctl.exe') @('-D', $clusterDirectory, 'stop', '-m', 'fast', '-w', '-t', '20'); exit }

if ($Setup -and !(Test-Path -LiteralPath (Join-Path $pgRoot 'share/extension/vector.control'))) {
    $vectorArchive = Join-Path $runtimeDirectory 'pgvector.zip'
    if (!(Test-Path -LiteralPath $vectorArchive)) {
        Invoke-WebRequest -Uri 'https://github.com/pgvector/pgvector/archive/refs/tags/v0.8.7.zip' -OutFile $vectorArchive
    }
    Expand-Archive -LiteralPath $vectorArchive -DestinationPath $runtimeDirectory -Force
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    $vsPath = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (!$vsPath) { throw 'Visual Studio C++ tools are required to build pgvector. See docs/reading.md.' }
    $vcvars = Join-Path $vsPath 'VC/Auxiliary/Build/vcvars64.bat'
    $vectorSource = Join-Path $runtimeDirectory 'pgvector-0.8.7'
    # Build/install only into the native runtime inside this project; no system service registration.
    $buildFile = Join-Path $runtimeDirectory 'build-vector.cmd'
    @"
@echo off
call "$vcvars"
if errorlevel 1 exit /b 1
set "PGROOT=$pgRoot"
cd /d "$vectorSource"
nmake /F Makefile.win
if errorlevel 1 exit /b 1
nmake /F Makefile.win install
"@ | Set-Content -LiteralPath $buildFile -Encoding ascii
    Checked 'cmd.exe' @('/c', $buildFile)
}

$adminPasswordPath = Join-Path $dataDirectory 'postgres-admin-password'
$appPasswordPath = Join-Path $dataDirectory 'postgres-app-password'
function NewPassword {
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    try {
        $generator.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    } finally { $generator.Dispose() }
}
if (!(Test-Path -LiteralPath (Join-Path $clusterDirectory 'PG_VERSION'))) {
    if (!$Setup) { throw 'Database is not initialized. Run with -Setup.' }
    if (!(Test-Path -LiteralPath $adminPasswordPath)) { NewPassword | Set-Content -LiteralPath $adminPasswordPath -Encoding ascii }
    if (!(Test-Path -LiteralPath $appPasswordPath)) { NewPassword | Set-Content -LiteralPath $appPasswordPath -Encoding ascii }
    Checked (Join-Path $pgRoot 'bin/initdb.exe') @('-D', $clusterDirectory, '-U', 'postgres', '-A', 'scram-sha-256', '--encoding=UTF8', '--locale=C', "--pwfile=$adminPasswordPath")
}
& (Join-Path $pgRoot 'bin/pg_ctl.exe') -D $clusterDirectory status *> $null
if ($LASTEXITCODE -ne 0) {
    Checked (Join-Path $pgRoot 'bin/pg_ctl.exe') @('-D', $clusterDirectory, '-l', (Join-Path $dataDirectory 'postgres.log'), '-o', '-h 127.0.0.1 -p 5432', 'start', '-w', '-t', '20')
}
if ($Setup) {
    $previousPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = (Get-Content -LiteralPath $adminPasswordPath -Raw).Trim()
        $appPassword = (Get-Content -LiteralPath $appPasswordPath -Raw).Trim()
        $psql = Join-Path $pgRoot 'bin/psql.exe'
        $roleExists = & $psql -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='reading'"
        if (!$roleExists) { Checked $psql @('-h','127.0.0.1','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1','-c',"CREATE ROLE reading LOGIN PASSWORD '$appPassword'") }
        $databaseExists = & $psql -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='reading'"
        if (!$databaseExists) { Checked $psql @('-h','127.0.0.1','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1','-c','CREATE DATABASE reading OWNER reading') }
        Checked $psql @('-h','127.0.0.1','-U','postgres','-d','reading','-v','ON_ERROR_STOP=1','-c','CREATE EXTENSION IF NOT EXISTS vector')
    } finally { $env:PGPASSWORD = $previousPassword }
}
$env:READING_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/reading'
$env:READING_DATABASE_USER = 'reading'
$env:READING_DATABASE_PASSWORD = (Get-Content -LiteralPath $appPasswordPath -Raw).Trim()
Write-Host 'Native PostgreSQL is ready on 127.0.0.1:5432. Reading environment variables are set in this PowerShell session.'
