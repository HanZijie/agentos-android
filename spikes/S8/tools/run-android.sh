#!/usr/bin/env bash
# Runs the S8 test app on the device in $ANDROID_SERIAL and collects the results JSON.
#
#   ANDROID_SERIAL=<serial> tools/run-android.sh [debug|release] [modes]
#
# modes: comma list of contract,measure,real (default contract,measure).
# For "real", keys are taken from this shell's environment (MINIMAX_API_KEY, optional MINIMAX_ANTHROPIC_BASE_URL,
# OPENAI_COMPAT_BASE_URL / _API_KEY / _MODEL) and passed as intent extras on the `adb shell am start`
# command line; they are not written to any file.
# API 37's adbd writes the whole `adb shell` command line to logcat, so this refuses to pass keys to
# a device above API 36. For real endpoints on newer devices use the app's device test instead
# (app/src/androidTest MiniMaxLiveDeviceTest), which receives the key through stdin.
# The fake endpoint must be running on this machine:
#   node bundle/test/fake-llm.mjs --port 8787
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device assigned to this lane}"
VARIANT="${1:-release}"
MODES="${2:-contract,measure}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APK="android/build/outputs/apk/$VARIANT/android-$VARIANT.apk"
OUT="android/build/s8-android-$ANDROID_SERIAL-$VARIANT-$(date +%Y%m%d-%H%M%S).json"

if [[ "$MODES" == *real* ]]; then
  sdk="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
  if [[ -z "$sdk" || "$sdk" -gt 36 ]]; then
    echo "refusing to pass keys on the adb command line to API ${sdk:-?} (> 36 logs adb shell commands to logcat)" >&2
    exit 2
  fi
fi

curl --noproxy '*' -fsS -X POST http://127.0.0.1:8787/__reset >/dev/null
"$ADB" reverse tcp:8787 tcp:8787 >/dev/null
"$ADB" uninstall org.agentos.spike.s8 >/dev/null 2>&1 || true
"$ADB" install -r -t "$APK" >/dev/null
"$ADB" logcat -c

extras=(--es modes "$MODES")
if [[ "$MODES" == *real* ]]; then
  [[ -n "${MINIMAX_API_KEY:-}" ]] && extras+=(--es minimaxKey "$MINIMAX_API_KEY")
  [[ -n "${MINIMAX_ANTHROPIC_BASE_URL:-}" ]] && extras+=(--es minimaxBaseUrl "$MINIMAX_ANTHROPIC_BASE_URL")
  if [[ -n "${OPENAI_COMPAT_BASE_URL:-}" && -n "${OPENAI_COMPAT_API_KEY:-}" && -n "${OPENAI_COMPAT_MODEL:-}" ]]; then
    extras+=(--es compatUrl "$OPENAI_COMPAT_BASE_URL" --es compatKey "$OPENAI_COMPAT_API_KEY" --es compatModel "$OPENAI_COMPAT_MODEL")
  fi
fi
"$ADB" shell am start -W -n org.agentos.spike.s8/org.agentos.spike.s8.app.MainActivity "${extras[@]}" >/dev/null

for _ in $(seq 1 300); do
  sleep 2
  "$ADB" logcat -d -s S8:I | grep -q S8DONE && break
done
"$ADB" logcat -d -s S8:I | sed -n 's/^.* S8 *: //p' | grep -E "^(PASS|FAIL|REAL|startup|device)" | cut -c1-220
"$ADB" logcat -d -s S8JSON:I | sed -n 's/^.* S8JSON *: [0-9]\{4\} //p' | tr -d '\n' > "$OUT"
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$OUT" && echo "results -> $OUT"
