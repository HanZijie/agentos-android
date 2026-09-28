#!/system/bin/sh
# adb-root mode helper for S1 (userdebug emulator after `adb root`, no Magisk / KernelSU).
# Does by hand what the root manager does, so the module's own scripts run unchanged:
#   flash <zip>   unpack into /data/adb/modules/agentos_s1; for the "customize" variant run customize.sh
#                 with stub installer functions and BOOTMODE=true (as when flashing from the manager App)
#   boot          run the module's service.sh in the foreground (what late_start service would do)
M=/data/adb/modules/agentos_s1

case $1 in
flash)
    [ -f "$2" ] || { echo "no zip: $2"; exit 1; }
    [ -d "$M" ] && mv "$M" "$M.old.$$" && rm -rf "$M.old.$$"
    mkdir -p "$M" && unzip -o -q "$2" -d "$M" && rm -rf "$M/META-INF"
    ui_print() { echo "$1"; }
    abort() { echo "$1"; exit 1; }
    set_perm_recursive() { :; }
    set_perm() { :; }
    MODPATH=$M
    BOOTMODE=true
    MAGISK_VER=adb-root
    MAGISK_VER_CODE=0
    . "$M/customize.sh"
    chmod 755 "$M"/*.sh
    ;;
boot)
    sh "$M/service.sh"
    ;;
*)
    echo "usage: s1emu.sh flash <zip> | boot" >&2
    exit 2
    ;;
esac
