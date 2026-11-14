@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title GK AI Image Studio - Installer
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
echo ==========================================================
echo          GK AI Image Studio - One click installer
echo   Internet sirf is installation ke time chahiye (ek baar)
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
  echo [*] Python nahi mila. Python 3.12 install kar rahe hain...
  winget install -e --id Python.Python.3.12 --scope user --accept-package-agreements --accept-source-agreements
  if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
)
if not defined PY (
  echo [*] winget se nahi hua, python.org se download kar rahe hain...
  curl -L -o "%TEMP%\python-3.12-setup.exe" https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe
  "%TEMP%\python-3.12-setup.exe" /quiet InstallAllUsers=0 PrependPath=1 Include_launcher=1 Include_pip=1
  if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
)
if not defined PY (
  echo.
  echo [X] Python install nahi ho paya. https://www.python.org/downloads/ se Python 3.12 install karein
  echo     ^(install karte waqt "Add python.exe to PATH" tick karein^), phir install.bat dobara chalaein.
  pause
  exit /b 1
)

echo [*] Python: %PY%
echo [*] Alag environment (venv) bana rahe hain...
%PY% -m venv venv
if errorlevel 1 (
  echo [X] venv nahi bana. & pause & exit /b 1
)

:venv_ready
"venv\Scripts\python.exe" installer.py %*
echo.
pause
