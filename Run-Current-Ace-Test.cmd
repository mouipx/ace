@echo off
set "ACE=%~dp0app\build\compose\binaries\main\app\Ace\Ace.exe"
if not exist "%ACE%" (
  echo Current packaged Ace build was not found.
  echo Run: cd app ^&^& gradlew.bat createDistributable
  pause
  exit /b 1
)
echo Launching CURRENT TEST BUILD:
echo %ACE%
start "Ace - Current Test Build" "%ACE%"
