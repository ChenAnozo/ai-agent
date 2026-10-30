@echo off
setlocal
title Reading Backend
cd /d "%~dp0"
if errorlevel 1 goto directory_error

echo Starting PostgreSQL and the reading backend...
echo Keep this window open while using the reading app.
echo Stop any backend already running in IDEA before starting.
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Reading.ps1"
set "readingExitCode=%errorlevel%"
echo.
if not "%readingExitCode%"=="0" echo Startup failed. Check the error messages above.
pause
exit /b %readingExitCode%

:directory_error
echo Could not open the project directory.
pause
exit /b 1
