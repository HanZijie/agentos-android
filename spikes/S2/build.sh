#!/bin/sh
# Build the S2 spike into build/out/:
#   s2-agent.apk            AgentOS stand-in (key A)          s2-client.apk   client / intruder (key B)
#   agentos-s2-module.zip   Magisk / KernelSU module running device/supervisor.sh at boot
#   s2dev.sh, supervisor.sh device-side helpers pushed by s2.sh
# Env: S2_STICKY=true builds AgentService with START_STICKY (case K3); SPIKE_KEY_PASS overrides the
# throwaway key password; GRADLE_OFFLINE=1 adds --offline.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/build/out"
KEYS="$HERE/build/keys"
PASS=${SPIKE_KEY_PASS:-android}
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export ANDROID_HOME
mkdir -p "$OUT" "$KEYS"

for k in a b; do
    if [ ! -f "$KEYS/spike-$k.jks" ]; then
        keytool -genkeypair -keystore "$KEYS/spike-$k.jks" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS" \
            -alias spike -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=AgentOS spike key $k" >/dev/null
        echo "generated throwaway key $KEYS/spike-$k.jks"
    fi
done

OFFLINE=
[ "${GRADLE_OFFLINE:-0}" = 1 ] && OFFLINE=--offline
"$HERE/gradlew" -p "$HERE" $OFFLINE -q :agent:assembleDebug :client:assembleDebug -Ps2.sticky="${S2_STICKY:-false}"
cp "$HERE/agent/build/outputs/apk/debug/agent-debug.apk" "$OUT/s2-agent.apk"
cp "$HERE/client/build/outputs/apk/debug/client-debug.apk" "$OUT/s2-client.apk"

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/agentos-s2-stage.XXXXXX")
cp -R "$HERE/module/." "$STAGE/"
cp "$HERE/device/supervisor.sh" "$STAGE/supervisor.sh"
(cd "$STAGE" && zip -qrX "$STAGE.zip" .)
mv -f "$STAGE.zip" "$OUT/agentos-s2-module.zip"
cp "$HERE/device/supervisor.sh" "$HERE/device/s2dev.sh" "$OUT/"

cd "$OUT"
shasum -a 256 s2-agent.apk s2-client.apk agentos-s2-module.zip
