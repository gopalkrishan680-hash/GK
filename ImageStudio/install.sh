#!/usr/bin/env bash
# GK AI Image Studio - Mac / Linux installer
set -e
cd "$(dirname "$0")"
echo "=========================================================="
echo "        GK AI Image Studio - One click installer"
echo "  Internet is needed only during this one-time installation"
echo "=========================================================="
if [ ! -x venv/bin/python ]; then
  PY=""
  for c in python3.12 python3.11 python3.13 python3.10 python3; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if (3,10)<=sys.version_info[:2]<=(3,13) else 1)'; then
      PY="$c"; break
    fi
  done
  if [ -z "$PY" ]; then
    echo "❌ Python 3.10-3.13 not found."
    if [ "$(uname)" = "Darwin" ]; then
      echo "   Install it:  brew install python@3.12   (or https://www.python.org/downloads/)"
    else
      echo "   Install it:  sudo apt install python3 python3-venv python3-pip"
    fi
    exit 1
  fi
  echo "[*] Python: $PY"
  if ! "$PY" -m venv venv; then
    echo "❌ Could not create venv. On Ubuntu/Debian:  sudo apt install python3-venv"
    exit 1
  fi
fi
venv/bin/python installer.py "$@"
