#!/usr/bin/env bash
cd "$(dirname "$0")"
if [ ! -x venv/bin/python ]; then echo "Pehle ./install.sh chalaein"; exit 1; fi
exec venv/bin/python app.py --inbrowser "$@"
