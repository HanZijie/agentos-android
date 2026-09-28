# S2 spike module: install-time checks only. Nothing is mounted, no sepolicy, no post-fs-data.
API=$(getprop ro.build.version.sdk)
ui_print "- AgentOS S2 spike (root supervisor prototype)"
ui_print "- API $API, $(getprop ro.product.model)"
case $API in
35 | 36 | 37) ;;
*) abort "! S2 only targets API 35-37" ;;
esac
if [ "$KSU" = true ]; then
    ui_print "- root manager: KernelSU $KSU_VER ($KSU_VER_CODE)"
else
    ui_print "- root manager: Magisk $MAGISK_VER ($MAGISK_VER_CODE)"
fi
ui_print "- Rescue: create /data/adb/modules/agentos_s2/disable, or use the manager's safe mode"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/supervisor.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
