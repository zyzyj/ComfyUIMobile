@echo off
setlocal
cd /d "%~dp0"

if not exist "node.exe" (
  echo.
  echo   [ERROR] node.exe is missing from this folder.
  echo   Please re-download the package.
  echo.
  pause
  exit /b 1
)

set "PATH=%~dp0;%PATH%"

echo.
echo   PDD / 1688 Spec Generator
echo   ==========================
echo.
echo   Starting server... this page will open in your browser.
echo   Keep this window open. Press Ctrl+C to stop.
echo.
echo   NOTE: when you click Login, a SECOND browser window opens
echo         for PDD login - log in there, then come back here.
echo.
start "" http://localhost:8787
"%~dp0node.exe" server.mjs

pause
