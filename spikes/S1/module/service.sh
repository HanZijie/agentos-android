#!/system/bin/sh
# S1: install / upgrade the test APK after boot (late_start service, runs in the background).
MODDIR=${0%/*}
. "$MODDIR/s1lib.sh"
load_conf
read -r MODE <"$MODDIR/s1.mode"
s1_log "service.sh start mode=$MODE method=$S1_METHOD magisk=${MAGISK_VER:-} ksu=${KSU:-} ctx=$(cat /proc/self/attr/current 2>/dev/null) shell=$(readlink /proc/$$/exe)"
set_desc "[S1] 等待开机完成"
t0=$(cs)
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
t1=$(cs)
if ! wait_pm; then
    set_desc "[S1] 失败：包管理器 2 分钟内没有就绪"
    s1_log "package manager not ready"
    exit 0
fi
t2=$(cs)
s1_log "boot_completed after $(((t1 - t0) * 10)) ms, pm ready after $(((t2 - t1) * 10)) ms;" \
    "package_verifier_enable=$(settings get global package_verifier_enable)" \
    "verifier_verify_adb_installs=$(settings get global verifier_verify_adb_installs)"
if process_all; then
    set_desc "[S1] $RESULT"
else
    set_desc "[S1] $RESULT"
fi
s1_log "result: $RESULT"
