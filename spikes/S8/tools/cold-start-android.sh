#!/usr/bin/env bash
# Cold start of the first QuickJS runtime in a fresh app process, N times each for
# source and cached bytecode. The app must already be installed (tools/run-android.sh).
#
#   ANDROID_SERIAL=<serial> tools/cold-start-android.sh [rounds]
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device assigned to this lane}"
ROUNDS="${1:-5}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=org.agentos.spike.s8

run_once() {
  local mode="$1"
  "$ADB" shell am force-stop "$PKG"
  sleep 1
  "$ADB" logcat -c
  "$ADB" shell am start -W -n "$PKG/$PKG.app.MainActivity" --es modes "$mode" >/dev/null
  for _ in $(seq 1 60); do
    sleep 1
    "$ADB" logcat -d -s S8:I | grep -q S8DONE && break
  done
  "$ADB" logcat -d -s S8:I | sed -n 's/^.* S8 *: cold //p' | head -1
}

run_once coldsource >/dev/null   # makes sure the bytecode cache exists
for i in $(seq 1 "$ROUNDS"); do
  echo "source   $(run_once coldsource)"
  echo "bytecode $(run_once coldbytecode)"
done
