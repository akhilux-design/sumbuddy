#!/bin/bash
# Prepares the running emulator for design comparison: 360 x 800 dp screen and a clean status bar.
# Start the emulator first:  $ANDROID_HOME/emulator/emulator -avd xpense_pixel
# With more than one device attached, choose one with ANDROID_SERIAL (adb reads it), e.g. ANDROID_SERIAL=emulator-5554.
set -euo pipefail
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
"$ADB" shell wm size 1080x2400
"$ADB" shell wm density 480          # 1080 / 3 = 360 dp wide, 2400 / 3 = 800 dp tall, same as the design
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
"$ADB" shell settings put global sysui_demo_allowed 1
"$ADB" shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
"$ADB" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
"$ADB" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
# Full Wi-Fi and mobile signal, both shown as connected to the internet ("fully"), so no "!" (no internet) appears.
# One command per network: extras with the same name in one broadcast overwrite each other.
"$ADB" shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true >/dev/null
"$ADB" shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4 -e fully true >/dev/null
"$ADB" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
echo "emulator ready: $("$ADB" shell wm size | tr -d '\r'), $("$ADB" shell wm density | tr -d '\r')"
