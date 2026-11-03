@echo off
title Mi TV installer
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install_home.ps1"
echo.
echo ---- finished, the log is in install_log.txt ----
pause
