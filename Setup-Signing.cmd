@echo off
pwsh.exe -NoProfile -Sta -File "%~dp0tools\setup-signing.ps1"
set "setup_result=%errorlevel%"
pause
exit /b %setup_result%
