#!/system/bin/sh
# late_start service (Magisk / KernelSU run this in the background after boot starts).
MODDIR=${0%/*}
export AGENTOS_PKG=org.agentos.spike.s2
export AGENTOS_CLS_PREFIX=org.agentos.app
export AGENTOS_STATE_DIR=/data/adb/agentos-s2
export AGENTOS_MODDIR="$MODDIR"
exec sh "$MODDIR/supervisor.sh"
