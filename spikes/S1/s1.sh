#!/bin/sh
# Host-side driver for S1 on a rooted device (see docs/spikes/S1.md). ANDROID_SERIAL is required.
#   sh spikes/S1/s1.sh check | flash <zip-name> | reboot | verify | method <auto|path|tmp|stdin|session> | matrix | methods | cleanup
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device assigned to lane D}"
ADB="adb -s $ANDROID_SERIAL"
OUT="$HERE/build/out"
DIR=/data/local/tmp/agentos-s1
PKG=org.agentos.spike.s1
MOD=/data/adb/modules/agentos_s1

su_() { $ADB shell "su -c '$*'"; }
say() { printf '\n### %s\n' "$*"; }

flash() { # zip name without .zip
    $ADB shell mkdir -p $DIR
    $ADB push "$OUT/$1.zip" $DIR/ >/dev/null
    if su_ "command -v magisk" >/dev/null 2>&1; then
        su_ "magisk --install-module $DIR/$1.zip"
    else
        su_ "/data/adb/ksud module install $DIR/$1.zip"
    fi
}

reboot_wait() {
    $ADB reboot
    $ADB wait-for-device
    until [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
    i=0
    while su_ "grep -q \"等待开机完成\" $MOD/module.prop" 2>/dev/null; do
        i=$((i + 1))
        [ $i -gt 90 ] && break
        sleep 2
    done
    sleep 3
}

verify() {
    echo "installed: $($ADB shell pm list packages --show-versioncode $PKG | tr -d '\r')"
    $ADB shell dumpsys package $PKG | grep -E "versionName|signatures|installerPackageName|installInitiatingPackageName" | tr -d '\r' | head -6
    echo "module:    $(su_ "grep ^description= $MOD/module.prop" | tr -d '\r')"
    su_ "tail -n 8 /data/adb/agentos-s1/install.log"
    $ADB logcat -d | grep -iE "PackageVerifier|Play Protect|playprotect|package_verifier" | tail -5 || true
}

expect() { # substring expected in the module description
    d=$(su_ "grep ^description= $MOD/module.prop" | tr -d '\r')
    case $d in *"$1"*) echo "PASS: $1" ;; *) echo "FAIL: expected '$1' in: $d" ;; esac
}

cmd=${1:-help}
[ $# -gt 0 ] && shift
case $cmd in
check)
    $ADB get-state
    $ADB shell "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.fingerprint"
    su_ "id; magisk -v 2>/dev/null; /data/adb/ksud -V 2>/dev/null; ls /data/adb/modules"
    echo "Rescue before any reboot test: adb shell su -c 'touch $MOD/disable' && adb reboot, or the manager's safe mode."
    ;;
flash) flash "$1" ;;
reboot) reboot_wait ;;
verify) verify ;;
method)
    su_ "mkdir -p /data/adb/agentos-s1 && echo S1_METHOD=$1 > /data/adb/agentos-s1/s1.conf"
    ;;
matrix)
    [ -f "$OUT/s1-v1.zip" ] || sh "$HERE/build.sh"
    su_ "rm -f /data/adb/agentos-s1/s1.conf"
    $ADB uninstall $PKG >/dev/null 2>&1 || true
    say "1 fresh install from service.sh"
    flash s1-v1; reboot_wait; verify; expect "install 成功：v1"
    say "2 reboot with the same zip: nothing to do"
    reboot_wait; expect "已是 v1"
    say "3 upgrade to v2"
    flash s1-v2; reboot_wait; verify; expect "upgrade 成功：v2"
    say "4 older zip over newer App: no downgrade"
    flash s1-v1; reboot_wait; expect "不降级"
    say "5 v2 signed with another key: stop with a visible reason, keep the installed App"
    flash s1-v2-badsig; reboot_wait; verify; expect "签名不符"
    say "6 fallback: install while flashing (customize.sh)"
    $ADB uninstall $PKG >/dev/null 2>&1 || true
    flash s1-v3-customize
    echo "right after flashing: $($ADB shell pm list packages --show-versioncode $PKG | tr -d '\r')"
    reboot_wait; expect "已是 v3"
    ;;
methods)
    [ -f "$OUT/s1-v1.zip" ] || sh "$HERE/build.sh"
    for m in path tmp stdin session; do
        say "install method $m"
        $ADB uninstall $PKG >/dev/null 2>&1 || true
        su_ "mkdir -p /data/adb/agentos-s1 && echo S1_METHOD=$m > /data/adb/agentos-s1/s1.conf"
        flash s1-v1; reboot_wait; expect "install 成功：v1（方式 $m"
    done
    su_ "rm -f /data/adb/agentos-s1/s1.conf"
    ;;
cleanup)
    su_ "touch $MOD/remove"
    $ADB uninstall $PKG || true
    echo "module marked for removal; reboot to finish"
    ;;
*) sed -n '2,3p' "$0" ;;
esac
