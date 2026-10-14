@echo off
pwsh.exe -NoProfile -File "%~dp0tools\build_release.ps1"
set "build_result=%errorlevel%"
pause
exit /b %build_result%
