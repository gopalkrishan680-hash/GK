@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title GK AI Image Studio - Installer
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
echo ==========================================================
echo          GK AI Image Studio - One click installer
echo   Internet is needed only during this one-time installation
echo ==========================================================
echo.

set "PY="
if exist "venv\Scripts\python.exe" goto :venv_ready

for %%V in (3.12 3.11 3.13 3.10) do (
  if not defined PY (
    py -%%V -c "import sys" >nul 2>&1 && set "PY=py -%%V"
  )
)
if not defined PY (
  python -c "import sys; sys.exit(0 if (3,10)<=sys.version_info[:2]<=(3,13) else 1)" >nul 2>&1 && set "PY=python"
)
if not defined PY (
  echo [*] Python not found. Installing Python 3.12...
  winget install -e --id Python.Python.3.12 --scope user --accept-package-agreements --accept-source-agreements
  if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
)
if not defined PY (
  echo [*] winget failed, downloading from python.org...
  curl -L -o "%TEMP%\python-3.12-setup.exe" https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe
  "%TEMP%\python-3.12-setup.exe" /quiet InstallAllUsers=0 PrependPath=1 Include_launcher=1 Include_pip=1
  if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
)
if not defined PY (
  echo.
  echo [X] Could not install Python. Install Python 3.12 from https://www.python.org/downloads/
  echo     ^(tick "Add python.exe to PATH" during setup^), then run install.bat again.
  pause
  exit /b 1
)

echo [*] Python: %PY%
echo [*] Creating a separate environment (venv)...
%PY% -m venv venv
if errorlevel 1 (
  echo [X] Could not create venv. & pause & exit /b 1
)

:venv_ready
"venv\Scripts\python.exe" installer.py %*
echo.
pause
