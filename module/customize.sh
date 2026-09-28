# AgentOS module: flash-time checks (docs/architecture.md F1). Sourced by the root manager's installer
# (Magisk install_module / KernelSU), which provides ui_print, abort, set_perm*, MODPATH, BOOTMODE,
# and either KSU / KSU_VER_CODE or MAGISK_VER / MAGISK_VER_CODE.
#
# Only checks and bookkeeping inside $MODPATH: no APK is installed here (service.sh does it after
# boot, docs/spikes/S1.md), nothing is mounted, no sepolicy, no post-fs-data (principle 7).

MODDIR=$MODPATH
. "$MODPATH/common.sh"
MATRIX=$MODPATH/support-matrix.yaml

ui_print "- AgentOS"
prop_get "$MODPATH/module.prop" version && ui_print "  模块版本 ${PV}"
matrix_get "$MATRIX" matrix_version && ui_print "  支持矩阵 ${MV}"

# 1. Android version: refuse outside API 35-37
API_NOW=$(getprop ro.build.version.sdk)
matrix_get "$MATRIX" api_min; API_MIN=$MV
matrix_get "$MATRIX" api_max; API_MAX=$MV
if ! is_uint "$API_NOW" || [ "$API_NOW" -lt "$API_MIN" ] || [ "$API_NOW" -gt "$API_MAX" ]; then
    abort "! 不支持的 Android 版本（API ${API_NOW}）：AgentOS 只支持 API ${API_MIN}–${API_MAX}（Android 15–17）"
fi
ui_print "  Android API ${API_NOW}"

# 2. Root manager and its version
if [ "$APATCH" = true ]; then
    abort "! 不支持 APatch：AgentOS 只支持 Magisk 和 KernelSU"
elif [ "$KSU" = true ]; then
    MANAGER=kernelsu
    MANAGER_VC=${KSU_VER_CODE:-0}
    matrix_get "$MATRIX" kernelsu_min_version_code; MANAGER_MIN=${MV:-0}
    ui_print "  root 管理器：KernelSU ${KSU_VER:-?}（${MANAGER_VC}）"
elif is_uint "$MAGISK_VER_CODE"; then
    MANAGER=magisk
    MANAGER_VC=$MAGISK_VER_CODE
    matrix_get "$MATRIX" magisk_min_version_code; MANAGER_MIN=${MV:-0}
    ui_print "  root 管理器：Magisk ${MAGISK_VER:-?}（${MANAGER_VC}）"
else
    abort "! 无法识别 root 管理器：AgentOS 只支持 Magisk 和 KernelSU"
fi
is_uint "$MANAGER_VC" || MANAGER_VC=0
if is_uint "$MANAGER_MIN" && [ "$MANAGER_VC" -lt "$MANAGER_MIN" ]; then
    abort "! root 管理器版本过低（${MANAGER_VC}），至少需要 ${MANAGER_MIN}"
fi

# 3. Fingerprint: not verified is allowed, but said out loud
FP=$(getprop ro.build.fingerprint)
if matrix_has "$MATRIX" verified_fingerprints "$FP"; then
    FP_VERIFIED=1
    ui_print "  已验证设备"
else
    FP_VERIFIED=0
    ui_print "  未验证设备（社区适配）：${FP}"
fi

# 4. Free space on /data
matrix_get "$MATRIX" min_free_mb; NEED_MB=${MV:-300}
FREE_KB=$(df -k /data 2>/dev/null | awk 'NR == 2 { print $4 }')
if is_uint "$FREE_KB" && is_uint "$NEED_MB" && [ $((FREE_KB / 1024)) -lt "$NEED_MB" ]; then
    abort "! /data 剩余空间不足：$((FREE_KB / 1024)) MB，至少需要 ${NEED_MB} MB"
fi

# 5. Conflicting modules that are enabled
for M in $(matrix_list "$MATRIX" conflicting_modules); do
    D=/data/adb/modules/$M
    if [ -d "$D" ] && [ ! -f "$D/disable" ] && [ ! -f "$D/remove" ]; then
        abort "! 与已启用的模块 ${M} 冲突，请先在 root 管理器里禁用它"
    fi
done

# 6. Bookkeeping for service.sh (and W12's OTA check): module / matrix versions and what we saw
matrix_get "$MATRIX" matrix_version; MATRIX_VER=$MV
{
    echo "matrix_version=$MATRIX_VER"
    echo "api=$API_NOW"
    echo "fingerprint=$FP"
    echo "fingerprint_verified=$FP_VERIFIED"
    echo "manager=$MANAGER"
    echo "manager_version_code=$MANAGER_VC"
    echo "flashed_at=$(date +%s)"
} >"$MODPATH/install.prop"

ui_print "  重启后自动安装 AgentOS App，并由监督进程拉起运行时"
ui_print "  进不了系统时：在 root 管理器的安全模式里禁用模块，或 adb shell su -c 'touch /data/adb/modules/${AGENTOS_MODULE_ID}/disable'"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
