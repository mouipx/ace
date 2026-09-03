@echo off
setlocal EnableExtensions
cd /d "%~dp0"

REM Double-click wrapper. Gradle uses build.ps1 directly with its own output dir.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" -OutputDir "%~dp0" > "%~dp0build.log" 2>&1
set "CODE=%ERRORLEVEL%"
type "%~dp0build.log"
echo.
echo (Full output saved to: %~dp0build.log)
echo.
pause
exit /b %CODE%
