#!/usr/bin/env bash
# Emulator check: install the APK, start it in self-test mode (the page makes a small project,
# cuts a grid, syncs, exports an MP4 with WebCodecs and saves it through the native bridge),
# then print the app's log lines.
set -u
APK="$1"
PKG=com.gopalkrishan.stickmanstudio

adb install -r "$APK"
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" --ez selftest true

for i in $(seq 1 72); do
  sleep 5
  adb logcat -d -s SSAPP:I > logcat.txt
  if grep -qE "SSAPP-SAVED|SSAPP-SAVE-FAIL|SSAPP-SELFTEST-FAIL" logcat.txt; then break; fi
done
sleep 3
adb logcat -d -s SSAPP:I > logcat.txt
echo "===== app log ====="
cat logcat.txt
echo "===== saved files ====="
adb shell "ls -la '/sdcard/Movies/Stickman Studio/'" || true
adb exec-out screencap -p > screen.png || true
adb shell dumpsys package com.google.android.webview | grep -m1 versionName || true

grep -q "SSAPP-READY" logcat.txt && grep -q "SSAPP-SAVED" logcat.txt && adb shell "ls '/sdcard/Movies/Stickman Studio/'" | grep -q "stickman-video.mp4"
