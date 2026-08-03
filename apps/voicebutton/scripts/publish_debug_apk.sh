#!/usr/bin/env bash
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
APK="$REPO/apps/voicebutton/app/build/outputs/apk/debug/voicebutton-debug.apk"
UPLOAD=/data/var/web_portal/uploads
[ -f "$APK" ] || { echo "missing APK: $APK" >&2; exit 1; }
mkdir -p "$UPLOAD"
find "$UPLOAD" -maxdepth 1 -type f -name 'voicebutton*.apk' ! -name 'voicebutton-debug.apk' -delete
install -m 0640 "$APK" "$UPLOAD/voicebutton-debug.apk"
find "$UPLOAD" -maxdepth 1 -type f -name 'voicebutton*.apk' -printf '%f %s\n'
