#!/usr/bin/env bash
# Sends the brain code to the cloud server, installs it and restarts Ronie there. Run from Git Bash:
#   ./deploy-cloud.sh
# Data (memory, keys, settings) on the server is left alone.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
source "$HERE/cloud.env"  # RONIE_SERVER and RONIE_URL; copy cloud.env.example
SERVER="$RONIE_SERVER"
KEY="$HOME/.ssh/ronie-oracle"
TAR="$(mktemp -d)/brain.tgz"

tar czf "$TAR" -C "$HERE" --exclude=.venv --exclude=__pycache__ --exclude=.pytest_cache brain
scp -q -i "$KEY" "$TAR" "$SERVER":/home/ubuntu/brain.tgz
ssh -i "$KEY" "$SERVER" 'set -e
  cd ~/ronie && rm -rf brain.new && mkdir brain.new && tar xzf ~/brain.tgz -C brain.new
  rsync -a --delete --exclude .venv brain.new/brain/ brain/ && rm -rf brain.new
  cd brain && .venv/bin/pip install -q .
  sudo systemctl restart ronie-brain && sleep 4 && curl -s 127.0.0.1:8790/health && echo'
echo "Deployed. Public check:"
curl -s "${RONIE_URL/wss:/https:}/health" && echo
