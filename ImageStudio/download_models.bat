@echo off
chcp 65001 >nul
cd /d "%~dp0"
title GK AI Image Studio - Models download
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
if not exist "venv\Scripts\python.exe" (
  echo Pehle install.bat chalaein.
  pause
  exit /b 1
)
"venv\Scripts\python.exe" download_models.py %*
pause
