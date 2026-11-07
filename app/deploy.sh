#!/bin/bash
# Install skazhi on the TV and enable it: grant the mic, turn on the accessibility service.
# Usage: ./deploy.sh <tv-ip>:5555 [path/to/skazhi.apk]   (default: the one build.sh made)
set -euo pipefail
[ $# -ge 1 ] || { echo "usage: $0 <tv-ip>:5555 [skazhi.apk]" >&2; exit 2; }
DEV="$1"
APK="$(cd "$(dirname "${2:-$(dirname "$0")/skazhi.apk}")" && pwd)/$(basename "${2:-skazhi.apk}")"
SVC="ru.home.skazhi/ru.home.skazhi.KeyService"
adb connect "$DEV" >/dev/null 2>&1 || true
adb -s "$DEV" install -r "$APK"
adb -s "$DEV" shell pm grant ru.home.skazhi android.permission.RECORD_AUDIO || true
# append our accessibility service without dropping others already enabled
cur=$(adb -s "$DEV" shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$cur" = "null" ] && cur=""
case ":$cur:" in *":$SVC:"*) new="$cur";; *) new="${cur:+$cur:}$SVC";; esac
adb -s "$DEV" shell settings put secure enabled_accessibility_services "$new"
adb -s "$DEV" shell settings put secure accessibility_enabled 1
echo "deployed. accessibility services: $new"
