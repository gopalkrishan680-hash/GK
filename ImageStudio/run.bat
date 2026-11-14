@echo off
chcp 65001 >nul
cd /d "%~dp0"
title GK AI Image Studio
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
if not exist "venv\Scripts\python.exe" (
  echo Pehle install.bat chalaein.
  pause
  exit /b 1
)
"venv\Scripts\python.exe" app.py --inbrowser %*
pause
