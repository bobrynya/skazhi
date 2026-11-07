#!/bin/bash
# Install skazhi on the TV and enable it. Usage: ./deploy.sh [adb-serial]
set -euo pipefail
cd "$(dirname "$0")"
DEV="${1:-192.168.88.57:5555}"
SVC="ru.home.skazhi/ru.home.skazhi.KeyService"
adb connect "$DEV" >/dev/null 2>&1 || true
adb -s "$DEV" install -r skazhi.apk
adb -s "$DEV" shell pm grant ru.home.skazhi android.permission.RECORD_AUDIO || true
# append our accessibility service without dropping others already enabled
cur=$(adb -s "$DEV" shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$cur" = "null" ] && cur=""
case ":$cur:" in *":$SVC:"*) new="$cur";; *) new="${cur:+$cur:}$SVC";; esac
adb -s "$DEV" shell settings put secure enabled_accessibility_services "$new"
adb -s "$DEV" shell settings put secure accessibility_enabled 1
echo "deployed. accessibility services: $new"
