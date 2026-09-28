#!/bin/sh
# Build the S1 spike into build/out/:
#   apk/s1-v1-a.apk  s1-v2-a.apk  s1-v2-b.apk  s1-v3-a.apk      (R8 release, spike key a or b)
#   s1-v1.zip            fresh install from service.sh
#   s1-v2.zip            upgrade over v1
#   s1-v2-badsig.zip     v2 signed with key b: must stop with a visible reason
#   s1-v3-customize.zip  fallback: install while flashing (customize.sh, BOOTMODE)
# Env: SPIKE_KEY_PASS overrides the throwaway key password; GRADLE_OFFLINE=1 adds --offline.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/build/out"
KEYS="$HERE/build/keys"
PASS=${SPIKE_KEY_PASS:-android}
PKG=org.agentos.spike.s1
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export ANDROID_HOME
mkdir -p "$OUT/apk" "$KEYS"
OFFLINE=
[ "${GRADLE_OFFLINE:-0}" = 1 ] && OFFLINE=--offline

for k in a b; do
    if [ ! -f "$KEYS/spike-$k.jks" ]; then
        keytool -genkeypair -keystore "$KEYS/spike-$k.jks" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS" \
            -alias spike -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=AgentOS spike key $k" >/dev/null
        echo "generated throwaway key $KEYS/spike-$k.jks"
    fi
done

build_apk() { # versionCode key
    "$HERE/gradlew" -p "$HERE" $OFFLINE -q :app:assembleRelease -Ps1.versionCode="$1" -Ps1.key="$2"
    cp "$HERE/app/build/outputs/apk/release/app-release.apk" "$OUT/apk/s1-v$1-$2.apk"
}

pack() { # zip-name apk versionCode mode module-versionCode
    stage=$(mktemp -d "${TMPDIR:-/tmp}/agentos-s1-stage.XXXXXX")
    cp -R "$HERE/module/." "$stage/"
    sed -e "s/@VERSION@/$1/" -e "s/@VERSIONCODE@/$5/" \
        -e "s/@DESCRIPTION@/S1 spike ($4): installs $PKG v$3 after boot. Test devices only./" \
        "$HERE/module/module.prop.in" >"$stage/module.prop"
    mkdir -p "$stage/app"
    cp "$OUT/apk/$2" "$stage/app/S1-v$3.apk"
    sha=$(shasum -a 256 "$stage/app/S1-v$3.apk" | cut -d' ' -f1)
    echo "$PKG $3 app/S1-v$3.apk $sha" >"$stage/app/apks.list"
    echo "$4" >"$stage/s1.mode"
    (cd "$stage" && zip -qrX "$stage.zip" . -x module.prop.in)
    mv -f "$stage.zip" "$OUT/$1.zip"
}

build_apk 1 a
build_apk 2 a
build_apk 2 b
build_apk 3 a
pack s1-v1 s1-v1-a.apk 1 service 1
pack s1-v2 s1-v2-a.apk 2 service 2
pack s1-v2-badsig s1-v2-b.apk 2 service 3
pack s1-v3-customize s1-v3-a.apk 3 customize 4

cd "$OUT"
shasum -a 256 apk/*.apk ./*.zip
