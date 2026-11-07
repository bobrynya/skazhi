#!/bin/bash
# Install or update the skazhi server on a Linux host (e.g. Raspberry Pi). Run with sudo.
# Model is NOT bundled: pass its path or URL (default: the small Russian model). Usage:
#   sudo ./install.sh
#   sudo ./install.sh /path/to/vosk-model-dir
#   sudo ./install.sh https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
# Re-running updates the code and keeps your commands.json and model.
set -euo pipefail
DEST=/opt/skazhi
SRC="$(cd "$(dirname "$0")" && pwd)"
MODEL_ARG="${1:-https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip}"
SVC_USER="${SUDO_USER:-$USER}"

mkdir -p "$DEST"
cp "$SRC/asr_server.py" "$SRC/commands.py" "$DEST/"
# commands.json is yours to edit on the server: never overwrite it
[ -f "$DEST/commands.json" ] || cp "$SRC/commands.json" "$DEST/"
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

# LAN access: tcp/2700 for the app, udp/5353 (mDNS) so the TV can find the server
if command -v nft >/dev/null && nft list chain inet filter input >/dev/null 2>&1; then
  for rule in "tcp dport 2700" "udp dport 5353"; do
    nft list chain inet filter input | grep -q "$rule" || \
      nft add rule inet filter input ip saddr 192.168.0.0/16 $rule accept || true
    if [ -f /etc/nftables.conf ] && ! grep -q "$rule" /etc/nftables.conf; then
      sed -i.bak "/type filter hook input/a\    ip saddr 192.168.0.0/16 $rule accept" /etc/nftables.conf || true
    fi
  done
else
  echo "WARNING: no nftables 'inet filter input' chain; if a firewall is on, open tcp/2700 and udp/5353 for the LAN"
fi

sed "s/%i/$SVC_USER/" "$SRC/skazhi-asr.service" > /etc/systemd/system/skazhi-asr.service
systemctl daemon-reload
systemctl enable skazhi-asr
systemctl restart skazhi-asr  # a re-run must pick up the new code
for _ in $(seq 1 30); do curl -sf localhost:2700/health >/dev/null && break; sleep 1; done
curl -sf localhost:2700/health && echo " <- skazhi is up. Commands: $DEST/commands.json (applied on save)"
