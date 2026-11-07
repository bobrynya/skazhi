#!/bin/bash
# Install skazhi ASR server on a Linux host (e.g. Raspberry Pi). Run with sudo.
# Model is NOT bundled: pass its path or URL. Usage:
#   sudo ./install.sh /path/to/vosk-model-dir
#   sudo ./install.sh https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
set -euo pipefail
DEST=/opt/skazhi
SRC="$(cd "$(dirname "$0")" && pwd)"
MODEL_ARG="${1:-https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip}"
SVC_USER="${SUDO_USER:-$USER}"

mkdir -p "$DEST"
cp "$SRC/asr_server.py" "$DEST/"
python3 -m venv "$DEST/venv"
"$DEST/venv/bin/pip" install -q -r "$SRC/requirements.txt"

if [ ! -d "$DEST/model" ]; then
  if [ -d "$MODEL_ARG" ]; then
    cp -r "$MODEL_ARG" "$DEST/model"
  else
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/m.zip" "$MODEL_ARG"
    unzip -q "$tmp/m.zip" -d "$tmp"
    mv "$tmp"/vosk-model-* "$DEST/model"
    rm -rf "$tmp"
  fi
fi
chown -R "$SVC_USER":"$SVC_USER" "$DEST"

sed "s/%i/$SVC_USER/" "$SRC/skazhi-asr.service" > /etc/systemd/system/skazhi-asr.service
systemctl daemon-reload
systemctl enable skazhi-asr
systemctl restart skazhi-asr  # re-run must pick up a new asr_server.py
sleep 3
systemctl is-active skazhi-asr && curl -s localhost:2700/health && echo " <- OK"

# open the ASR port to the LAN (nftables), if present
if command -v nft >/dev/null && nft list chain inet filter input >/dev/null 2>&1; then
  nft list chain inet filter input | grep -q 'dport 2700' || \
    nft add rule inet filter input ip saddr 192.168.0.0/16 tcp dport 2700 accept || true
  if [ -f /etc/nftables.conf ] && ! grep -q 'dport 2700' /etc/nftables.conf; then
    sed -i.bak '/type filter hook input/a\    ip saddr 192.168.0.0/16 tcp dport 2700 accept' /etc/nftables.conf || true
  fi
else
  echo "WARNING: no nftables 'inet filter input' chain; open tcp/2700 for the LAN yourself if a firewall is on"
fi
