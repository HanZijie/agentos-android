# S1: flash-time checks. In the "customize" variant the APK is installed right here when flashing
# from the booted manager App (the fallback in implementation-plan S1); from recovery it is deferred.
API=$(getprop ro.build.version.sdk)
ui_print "- AgentOS S1 spike (install from module), API $API"
case $API in
35 | 36 | 37) ;;
*) abort "! S1 only targets API 35-37" ;;
esac
if [ "$KSU" = true ]; then
    ui_print "- root manager: KernelSU $KSU_VER ($KSU_VER_CODE)"
else
    ui_print "- root manager: Magisk $MAGISK_VER ($MAGISK_VER_CODE)"
fi
ui_print "- Rescue: create /data/adb/modules/agentos_s1/disable, or use the manager's safe mode"
set_perm_recursive "$MODPATH" 0 0 0755 0644

read -r MODE <"$MODPATH/s1.mode"
if [ "$MODE" = customize ]; then
    if [ "$BOOTMODE" = true ]; then
        MODDIR=$MODPATH
        . "$MODPATH/s1lib.sh"
        load_conf
        s1_log "customize.sh install (BOOTMODE) method=$S1_METHOD ctx=$(cat /proc/self/attr/current 2>/dev/null)"
        if process_all; then ui_print "- $RESULT"; else ui_print "! $RESULT"; fi
        s1_log "customize result: $RESULT"
    else
        ui_print "- Flashed from recovery: installation deferred to service.sh"
    fi
fi
