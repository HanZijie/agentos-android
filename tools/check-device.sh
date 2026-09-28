#!/bin/sh
# Pre-flight check of a device before flashing agentos-<ver>.zip (docs/architecture.md F1).
#   ANDROID_SERIAL=<serial> sh tools/check-device.sh [agentos-<ver>.zip]
# Prints PASS / WARN / FAIL lines and exits 1 on any FAIL. With a zip, also compares the AgentOS App
# already on the device with the one in the zip (a different signing certificate makes the module stop).
# AGENTOS_ADB_ROOT=1: userdebug emulator after `adb root` (no Magisk / KernelSU); root commands then run
# directly in `adb shell` instead of `su -c`.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
ADB="adb -s $ANDROID_SERIAL"
ZIP=${1:-}
PKG=org.agentos.app
FAILS=0
WARNS=0

pass() { echo "PASS  $*"; }
warn() { echo "WARN  $*"; WARNS=$((WARNS + 1)); }
fail() { echo "FAIL  $*"; FAILS=$((FAILS + 1)); }
sh_() { $ADB shell "$*" 2>/dev/null | tr -d '\r'; }
if [ "${AGENTOS_ADB_ROOT:-0}" = 1 ]; then
    su_() { $ADB shell "$*" 2>/dev/null | tr -d '\r'; }
else
    su_() { $ADB shell "su -c '$*'" 2>/dev/null | tr -d '\r'; }
fi

# the support matrix: from the zip when given, else from the source tree
MATRIX=$(mktemp "${TMPDIR:-/tmp}/agentos-matrix.XXXXXX")
if [ -n "$ZIP" ]; then
    [ -f "$ZIP" ] || { echo "no such zip: $ZIP" >&2; exit 2; }
    unzip -p "$ZIP" support-matrix.yaml >"$MATRIX"
else
    cp "$ROOT/module/support-matrix.yaml" "$MATRIX"
fi
MODDIR=/nonexistent
. "$ROOT/module/common.sh"
mget() { matrix_get "$MATRIX" "$1"; echo "$MV"; }

# 1. device
if [ "$($ADB get-state 2>/dev/null)" != device ]; then
    fail "device $ANDROID_SERIAL is not connected"
    exit 1
fi
MODEL=$(sh_ getprop ro.product.model)
API=$(sh_ getprop ro.build.version.sdk)
FP=$(sh_ getprop ro.build.fingerprint)
echo "INFO  $MODEL, Android $(sh_ getprop ro.build.version.release) (API $API), $(sh_ getprop ro.build.type)"
echo "INFO  fingerprint $FP"

# 2. API
AMIN=$(mget api_min); AMAX=$(mget api_max)
if is_uint "$API" && [ "$API" -ge "$AMIN" ] && [ "$API" -le "$AMAX" ]; then
    pass "API $API is within $AMIN-$AMAX"
else
    fail "API $API is outside $AMIN-$AMAX: the module refuses to install"
fi

# 3. root and root manager
if [ "$(su_ id -u)" = 0 ]; then
    pass "root available ($([ "${AGENTOS_ADB_ROOT:-0}" = 1 ] && echo 'adb root' || echo 'su'); context $(su_ cat /proc/self/attr/current))"
else
    fail "no root: su -c id did not return uid 0 (grant shell root in the manager, or use AGENTOS_ADB_ROOT=1 on a userdebug emulator)"
fi
MAGISK_VC=$(su_ magisk -V)
KSU_VC=$(su_ /data/adb/ksud -V | sed -n 's/[^0-9]*\([0-9][0-9]*\).*/\1/p' | head -n 1)
if is_uint "$MAGISK_VC"; then
    MIN=$(mget magisk_min_version_code)
    if [ "$MAGISK_VC" -ge "$MIN" ]; then pass "Magisk $(su_ magisk -v) ($MAGISK_VC >= $MIN)"; else fail "Magisk $MAGISK_VC < $MIN"; fi
elif is_uint "$KSU_VC"; then
    MIN=$(mget kernelsu_min_version_code)
    if [ "$KSU_VC" -ge "$MIN" ]; then pass "KernelSU $KSU_VC (>= $MIN)"; else fail "KernelSU $KSU_VC < $MIN"; fi
elif [ "${AGENTOS_ADB_ROOT:-0}" = 1 ]; then
    warn "no root manager (adb root emulator): flashing and boot scripts are emulated by tools/smoke-test.sh"
else
    fail "neither Magisk nor KernelSU found"
fi

# 4. fingerprint
if matrix_has "$MATRIX" verified_fingerprints "$FP"; then
    pass "verified device (fingerprint in the support matrix)"
else
    warn "unverified device (community support): fingerprint not in support-matrix.yaml"
fi

# 5. free space
NEED=$(mget min_free_mb)
FREE_KB=$(su_ df -k /data | awk 'NR == 2 { print $4 }')
if is_uint "$FREE_KB" && [ $((FREE_KB / 1024)) -ge "$NEED" ]; then
    pass "/data free $((FREE_KB / 1024)) MB (>= $NEED MB)"
else
    fail "/data free ${FREE_KB:-?} KB, need $NEED MB"
fi

# 6. conflicting modules, and an existing AgentOS module
for M in $(matrix_list "$MATRIX" conflicting_modules); do
    if [ -n "$(su_ "ls -d /data/adb/modules/$M")" ] && [ -z "$(su_ "ls /data/adb/modules/$M/disable")" ]; then
        fail "conflicting module $M is enabled"
    fi
done
if [ -n "$(su_ ls -d /data/adb/modules/agentos)" ]; then
    echo "INFO  AgentOS module already installed: $(su_ grep -E '^(version|description)=' /data/adb/modules/agentos/module.prop | tr '\n' ' ')"
fi

# 7. rescue path: must be able to disable the module without booting into the system
if su_ "touch /data/adb/modules/.agentos-rescue-probe && rm -f /data/adb/modules/.agentos-rescue-probe && echo ok" | grep -q ok ||
    su_ "mkdir -p /data/adb/modules && touch /data/adb/modules/.agentos-rescue-probe && rm -f /data/adb/modules/.agentos-rescue-probe && echo ok" | grep -q ok; then
    pass "rescue: root can write /data/adb/modules (adb shell su -c 'touch /data/adb/modules/agentos/disable', then reboot)"
else
    fail "rescue: cannot write /data/adb/modules as root"
fi
if is_uint "$MAGISK_VC"; then
    echo "INFO  rescue: Magisk also disables all modules when booted into its safe mode (hold volume-down during boot)"
elif is_uint "$KSU_VC"; then
    echo "INFO  rescue: KernelSU safe mode: press volume-down several times during boot"
fi

# 8. the AgentOS App already on the device vs the one in the zip
INST=$(sh_ pm list packages --show-versioncode $PKG | sed -n "s/^package:$PKG versionCode:\([0-9]*\).*/\1/p")
if [ -z "$INST" ]; then
    pass "AgentOS App not installed yet (the module installs it after boot)"
elif [ -n "$ZIP" ]; then
    LINE=$(unzip -p "$ZIP" app/apks.list | grep "^$PKG ")
    ZVC=$(echo "$LINE" | cut -d' ' -f2)
    ZCERT=$(echo "$LINE" | cut -d' ' -f5)
    TMPAPK=$(mktemp "${TMPDIR:-/tmp}/agentos-installed.XXXXXX")
    $ADB pull "$(sh_ pm path $PKG | sed -n 's/^package://p' | head -n 1)" "$TMPAPK" >/dev/null 2>&1
    BT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* 2>/dev/null | sort | tail -n 1)
    ICERT=$("$BT/apksigner" verify --print-certs "$TMPAPK" 2>/dev/null | sed -n 's/.*Signer #1 certificate SHA-256 digest: //p')
    if [ -n "$ICERT" ] && [ "$ICERT" != "$ZCERT" ]; then
        fail "installed AgentOS App (v$INST) is signed with a different certificate than the zip: the module will stop. Uninstall it first (its data goes with it)"
    elif [ "$INST" -gt "$ZVC" ]; then
        warn "installed AgentOS App v$INST is newer than the zip (v$ZVC): the module will not downgrade it"
    else
        pass "installed AgentOS App v$INST, zip v$ZVC, same certificate"
    fi
else
    echo "INFO  AgentOS App v$INST installed (pass the zip to compare certificates)"
fi

# 9. informational
echo "INFO  SELinux $(sh_ getenforce); battery optimisation exemption for $PKG: $(sh_ dumpsys deviceidle whitelist | grep -q "$PKG" && echo yes || echo no)"

echo "---- $FAILS FAIL, $WARNS WARN"
[ $FAILS = 0 ]
