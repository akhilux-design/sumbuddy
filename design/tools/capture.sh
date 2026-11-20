#!/bin/bash
# Captures one preview from the emulator.
#   design/tools/capture.sh <preview-name> [dark|light]
# Writes build/previews/<name>.<theme>.png (full resolution, 1080 x 2400) and
# build/previews/<name>.<theme>.360.png (360 x 800, the size of the design pictures in design/spec).
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
# The preview screen is in debug builds only, whose app id ends in ".debug" (app/build.gradle.kts).
APP="$(grep '^app.applicationId=' gradle.properties | cut -d= -f2).debug"
NAME="$1"; THEME="${2:-dark}"
OUT="build/previews"; mkdir -p "$OUT"
DARK=true; NIGHT=yes
if [ "$THEME" = "light" ]; then DARK=false; NIGHT=no; fi
"$ADB" shell cmd uimode night "$NIGHT" >/dev/null
"$ADB" shell am start -W -S -n "$APP/com.expenses.app.preview.PreviewActivity" --es name "$NAME" --ez dark "$DARK" >/dev/null
sleep "${CAPTURE_WAIT:-2}"
"$ADB" exec-out screencap -p > "$OUT/$NAME.$THEME.png"
sips -z 800 360 "$OUT/$NAME.$THEME.png" --out "$OUT/$NAME.$THEME.360.png" >/dev/null
echo "$OUT/$NAME.$THEME.360.png"
