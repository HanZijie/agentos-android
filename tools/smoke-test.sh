#!/bin/sh
# Lifecycle smoke test of the AgentOS module on one device (M1 exit condition: install, reboot,
# disable, re-enable, uninstall all pass, and the system is clean afterwards).
#
#   ANDROID_SERIAL=<serial> sh tools/smoke-test.sh all <agentos-<ver>.zip>
#   ANDROID_SERIAL=<serial> sh tools/smoke-test.sh flash <zip> | boot | verify | disable | enable | uninstall | clean-check | logs
#
# Run tools/check-device.sh first; it also prints how to disable the module if the device does not boot.
#
# AGENTOS_ADB_ROOT=1: userdebug emulator after `adb root`, no Magisk / KernelSU. What the root manager
# would do is emulated: "flash" unpacks the zip into /data/adb/modules/agentos and runs customize.sh with
# stub installer functions; "boot" reboots and starts service.sh from adb early in boot; "uninstall" runs
# uninstall.sh and deletes the module directory. The scripts themselves run unchanged. Without the switch
# (real devices) the manager's own commands and boot hooks are used.
# SMOKE_SUPERVISE_PKG=org.agentos.spike.s2 (adb-root mode only) points the supervision phase at the S2
# stand-in App until W6 lands; the install phase always installs the zip's AgentOS App.
set -u
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
ADB="adb -s $ANDROID_SERIAL"
EMU=${AGENTOS_ADB_ROOT:-0}
MOD=/data/adb/modules/agentos
STATE=/data/adb/agentos
DIR=/data/local/tmp/agentos-smoke
PKG=org.agentos.app
FAILS=0

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAILS=$((FAILS + 1)); }
say() { printf '\n### %s\n' "$*"; }
sh_() { $ADB shell "$*" 2>/dev/null | tr -d '\r'; }
if [ "$EMU" = 1 ]; then
    [ "$(sh_ id -u)" = 0 ] || { echo "AGENTOS_ADB_ROOT=1 but adbd is not root: adb -s $ANDROID_SERIAL root" >&2; exit 2; }
    su_() { $ADB shell "$*" 2>/dev/null | tr -d '\r'; }
else
    su_() { $ADB shell "su -c '$*'" 2>/dev/null | tr -d '\r'; }
fi

# Device-side helper (pushed once): emulation of the manager in adb-root mode, and polling loops.
push_helper() {
    _h=$(mktemp "${TMPDIR:-/tmp}/agentos-smoke-dev.XXXXXX")
    cat >"$_h" <<'EOF'
#!/system/bin/sh
MOD=/data/adb/modules/agentos
STATE=/data/adb/agentos
case $1 in
emu-flash) # zip: what Magisk's install_module does, minus the mounts it would not do anyway
    [ -d "$MOD" ] && mv "$MOD" "$MOD.old.$$" && rm -rf "$MOD.old.$$"
    mkdir -p "$MOD" && unzip -o -q "$2" -d "$MOD" && rm -rf "$MOD/META-INF" || exit 1
    ui_print() { echo "$1"; }
    abort() { echo "$1"; exit 1; }
    set_perm_recursive() { :; }
    set_perm() { chmod "$4" "$1"; }
    MODPATH=$MOD; BOOTMODE=true; MAGISK_VER=adb-root; MAGISK_VER_CODE=99999
    . "$MOD/customize.sh"
    chmod 755 "$MOD/service.sh" "$MOD/uninstall.sh"
    ;;
emu-service) # [pkg]: late_start service
    if [ -n "$2" ]; then
        AGENTOS_PKG=$2 AGENTOS_CLS_PREFIX=org.agentos.app setsid nohup sh "$MOD/service.sh" >/dev/null 2>&1 </dev/null &
    else
        setsid nohup sh "$MOD/service.sh" >/dev/null 2>&1 </dev/null &
    fi
    ;;
emu-uninstall) # what the manager does for a module marked "remove" at the next boot
    sh "$MOD/uninstall.sh"
    rm -rf "$MOD"
    ;;
supervisor-pid)
    read -r p <"$STATE/supervisor.pid" 2>/dev/null
    [ -n "$p" ] && [ -d "/proc/$p" ] && grep -q service.sh "/proc/$p/cmdline" && echo "$p"
    ;;
wait-log) # pattern seconds
    i=0
    while [ $i -lt $(($3 * 2)) ]; do
        grep -q "$2" "$STATE/supervisor.log" 2>/dev/null && exit 0
        sleep 0.5; i=$((i + 1))
    done
    exit 1
    ;;
wait-gone) # pid seconds
    i=0
    while [ -d "/proc/$2" ] && [ $i -lt $(($3 * 10)) ]; do sleep 0.1; i=$((i + 1)); done
    [ ! -d "/proc/$2" ]
    ;;
esac
EOF
    $ADB shell mkdir -p $DIR
    $ADB push "$_h" $DIR/dev.sh >/dev/null
}
dev() { su_ "sh $DIR/dev.sh $*"; }

wait_boot() {
    $ADB wait-for-device
    if [ "$EMU" = 1 ]; then
        $ADB root >/dev/null
        sleep 2
        $ADB wait-for-device
        push_helper
        dev emu-service "${SMOKE_SUPERVISE_PKG:-}"
    fi
    i=0
    until [ "$(sh_ getprop sys.boot_completed)" = 1 ]; do
        i=$((i + 1)); [ $i -gt 150 ] && { fail "boot did not complete in 5 min"; return 1; }
        sleep 2
    done
    push_helper
}

desc() { su_ grep ^description= $MOD/module.prop | sed 's/^description=//'; }

cmd_flash() {
    [ -f "${1:-}" ] || { echo "usage: smoke-test.sh flash <zip>" >&2; exit 2; }
    say "flash $(basename "$1")"
    push_helper
    $ADB push "$1" $DIR/agentos.zip >/dev/null
    if [ "$EMU" = 1 ]; then
        out=$(dev emu-flash $DIR/agentos.zip)
    elif [ -n "$(su_ command -v magisk)" ]; then
        out=$(su_ magisk --install-module $DIR/agentos.zip)
    else
        out=$(su_ /data/adb/ksud module install $DIR/agentos.zip)
    fi
    echo "$out" | sed 's/^/  | /'
    case $out in
    *"! "*) fail "flash refused by customize.sh" ;;
    *) pass "flashed" ;;
    esac
}

cmd_boot() {
    say "reboot"
    $ADB reboot
    wait_boot && pass "booted"
}

cmd_verify() {
    say "verify after boot"
    # no spaces in the pattern: dev() passes its arguments through `sh -c`
    dev wait-log "install:.*result=" 180 && pass "install phase ran" || fail "install phase did not run within 3 min"
    i=0
    while :; do
        D=$(desc)
        case $D in "[启动中]"* | '') ;; *) break ;; esac
        i=$((i + 1)); [ $i -gt 60 ] && break
        sleep 1
    done
    su_ grep -E "install: |supervisor start|state ->|runtime up|start issued" $STATE/supervisor.log | tail -n 12 | sed 's/^/  | /'
    ZVC=$(su_ cat $MOD/app/apks.list | awk -v p=$PKG '$1 == p { print $2 }')
    IVC=$(sh_ pm list packages --show-versioncode $PKG | sed -n "s/^package:$PKG versionCode:\([0-9]*\).*/\1/p")
    [ -n "$IVC" ] && [ "$IVC" -ge "${ZVC:-0}" ] && pass "AgentOS App installed (v$IVC, zip v$ZVC)" || fail "AgentOS App v${IVC:-none}, zip v${ZVC:-?}"
    SP=$(dev supervisor-pid)
    [ -n "$SP" ] && pass "supervisor running (pid $SP, context $(su_ cat /proc/$SP/attr/current), oom_score_adj $(su_ cat /proc/$SP/oom_score_adj))" ||
        fail "supervisor not running"
    [ "$(su_ stat -c %a $STATE)" = 700 ] && pass "$STATE is 0700" || fail "$STATE mode $(su_ stat -c %a $STATE)"
    case $D in "[启动中]"* | '') fail "module.prop still says after 60 s: $D" ;; *) pass "module.prop: $D" ;; esac
    BAD=$(su_ "ls -d $MOD/system $MOD/post-fs-data.sh $MOD/sepolicy.rule $MOD/system.prop $MOD/zygisk 2>/dev/null")
    [ -z "$BAD" ] && pass "module has no system/, post-fs-data.sh, sepolicy.rule, system.prop, zygisk" || fail "module contains: $BAD"
}

cmd_disable() {
    say "disable (root manager toggle): the supervisor exits within 10 s"
    SP=$(dev supervisor-pid)
    su_ touch $MOD/disable
    if [ -n "$SP" ] && dev wait-gone "$SP" 10; then pass "supervisor exited"; else fail "supervisor still running"; fi
    su_ grep -q "module disabled or removed" $STATE/supervisor.log && pass "logged module_disabled" || fail "no module_disabled in the log"
    say "reboot while disabled: nothing of the module runs"
    $ADB reboot
    if [ "$EMU" = 1 ]; then
        # the manager skips disabled modules; here service.sh is started anyway to prove it exits at once
        wait_boot_no_service
    else
        wait_boot
    fi
    sleep 15
    [ -z "$(dev supervisor-pid)" ] && pass "no supervisor after a reboot while disabled" || fail "supervisor runs while disabled"
}

wait_boot_no_service() {
    $ADB wait-for-device
    $ADB root >/dev/null; sleep 2; $ADB wait-for-device
    i=0
    until [ "$(sh_ getprop sys.boot_completed)" = 1 ]; do i=$((i + 1)); [ $i -gt 150 ] && return 1; sleep 2; done
    push_helper
    # also prove that service.sh itself refuses to supervise a disabled module
    dev emu-service "${SMOKE_SUPERVISE_PKG:-}"
}

cmd_enable() {
    say "re-enable and reboot"
    su_ rm -f $MOD/disable
    cmd_boot
    cmd_verify
}

cmd_uninstall() {
    say "uninstall the module"
    SP=$(dev supervisor-pid)
    if [ "$EMU" = 1 ]; then
        su_ touch $MOD/remove
        [ -n "$SP" ] && dev wait-gone "$SP" 10
        dev emu-uninstall
        pass "module removed (emulated manager: uninstall.sh + delete)"
    else
        if [ -n "$(su_ command -v magisk)" ]; then su_ touch $MOD/remove; else su_ /data/adb/ksud module uninstall agentos; fi
        cmd_boot
    fi
    cmd_clean_check
}

cmd_clean_check() {
    say "clean after uninstall (architecture F13)"
    [ -z "$(su_ ls -d $MOD)" ] && pass "$MOD gone" || fail "$MOD still exists"
    [ -z "$(su_ ls -d $STATE)" ] && pass "$STATE gone" || fail "$STATE still exists"
    [ -z "$(dev supervisor-pid)" ] && [ -z "$(su_ "ps -A -o ARGS | grep modules/agentos/service.sh | grep -v grep")" ] &&
        pass "no supervisor process" || fail "a supervisor process is still running"
    [ -z "$(su_ "ls /data/local/tmp/agentos-install-*.apk 2>/dev/null")" ] && pass "no install leftovers in /data/local/tmp" ||
        fail "install leftovers in /data/local/tmp"
    if [ -n "$(sh_ pm list packages $PKG)" ]; then
        echo "INFO  AgentOS App stays installed by design (F13); remove it like any App: adb uninstall $PKG"
    fi
}

cmd_logs() { su_ tail -n 60 $STATE/supervisor.log; echo "description: $(desc)"; }

cmd=${1:-help}
[ $# -gt 0 ] && shift
push_helper
case $cmd in
flash) cmd_flash "$@" ;;
boot) cmd_boot ;;
verify) cmd_verify ;;
disable) cmd_disable ;;
enable) cmd_enable ;;
uninstall) cmd_uninstall ;;
clean-check) cmd_clean_check ;;
logs) cmd_logs ;;
all)
    [ -f "${1:-}" ] || { echo "usage: smoke-test.sh all <zip>" >&2; exit 2; }
    cmd_flash "$1"
    cmd_boot
    cmd_verify
    cmd_disable
    cmd_enable
    cmd_uninstall
    ;;
*) sed -n '2,9p' "$0"; exit 2 ;;
esac
echo "---- $FAILS FAIL"
[ $FAILS = 0 ]
