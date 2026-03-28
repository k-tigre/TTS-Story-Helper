#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -x .venv/bin/python ]]; then
  echo "Creating venv..."
  python3 -m venv .venv
fi
.venv/bin/pip install -r requirements.txt

exec .venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8765
