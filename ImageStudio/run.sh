#!/usr/bin/env bash
cd "$(dirname "$0")"
if [ ! -x venv/bin/python ]; then echo "Please run ./install.sh first"; exit 1; fi
exec venv/bin/python app.py --inbrowser "$@"
