#!/system/bin/sh
# Device-side helper for the S2 cases. Pushed to /data/local/tmp/agentos-s2/ by s2.sh, run as root:
#   su -c 'sh /data/local/tmp/agentos-s2/s2dev.sh <command> [args]'
PKG=org.agentos.spike.s2
CLI=org.agentos.spike.s2.client
SVC=$PKG/org.agentos.app.agent.AgentService
PROC=$PKG:agent
DE=/data/user_de/0/$PKG/files
HB=$DE/supervisor/heartbeat
STATE=/data/adb/agentos-s2
DIR=/data/local/tmp/agentos-s2
A=org.agentos.spike.action

cs() { read -r _u _r </proc/uptime; echo "${_u%.*}${_u#*.}"; } # uptime in centiseconds
pid() { set -- $(pidof "$PROC" 2>/dev/null); echo "$1"; }
hbval() { sed -n "s/^$1=//p" "$HB" 2>/dev/null; }
fgs() { am start-foreground-service --user 0 -n "$SVC" -a "$@"; }

wait_fg() { # timeout_s -> 0 when heartbeat says tasks>0 fg=1 for the live pid
    _end=$(($(cs) + ${1:-15} * 100))
    while [ "$(cs)" -lt $_end ]; do
        _p=$(pid)
        if [ -n "$_p" ] && [ "$(hbval pid)" = "$_p" ] && [ "$(hbval fg)" = 1 ] && [ "$(hbval tasks)" != 0 ]; then
            echo "fg ok pid=$_p after $(((${1:-15} * 100 - _end + $(cs)) * 10))ms"
            return 0
        fi
        sleep 0.1
    done
    echo "fg NOT reached in ${1:-15}s: $(tr '\n' ' ' <"$HB" 2>/dev/null)"
    return 1
}

case $1 in
info)
    echo "model=$(getprop ro.product.model) device=$(getprop ro.product.device)"
    echo "android=$(getprop ro.build.version.release) api=$(getprop ro.build.version.sdk) patch=$(getprop ro.build.version.security_patch)"
    echo "fingerprint=$(getprop ro.build.fingerprint)"
    echo "selinux=$(getenforce)"
    command -v magisk >/dev/null && echo "magisk=$(magisk -v) ($(magisk -V))"
    [ -d /data/adb/ksu ] && echo "kernelsu=$(/data/adb/ksud -V 2>/dev/null || ksud -V 2>/dev/null)"
    echo "modules: $(ls /data/adb/modules 2>/dev/null | tr '\n' ' ')"
    pm list packages --show-versioncode 2>/dev/null | grep -E "spike|org.agentos"
    echo "standby_bucket=$(am get-standby-bucket $PKG 2>/dev/null)"
    dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG" && echo "battery_exempt=yes" || echo "battery_exempt=no"
    if [ -f "$STATE/supervisor.pid" ]; then
        read -r sp <"$STATE/supervisor.pid"
        if [ -d "/proc/$sp" ]; then
            echo "supervisor pid=$sp adj=$(cat /proc/$sp/oom_score_adj) ctx=$(cat /proc/$sp/attr/current) exe=$(readlink /proc/$sp/exe)"
        else
            echo "supervisor not running (stale pid $sp)"
        fi
    else
        echo "supervisor not started"
    fi
    echo "agent pid=$(pid)"
    ;;
rescue-check)
    # Contract prerequisite: every test device must have a way to disable the module without booting.
    for m in agentos_s1 agentos_s2 agentos; do
        [ -d "/data/adb/modules/$m" ] && echo "module $m present; rescue: adb shell su -c 'touch /data/adb/modules/$m/disable' then reboot"
    done
    [ -w /data/adb/modules ] && echo "/data/adb/modules writable by root: yes"
    command -v magisk >/dev/null && echo "Magisk: boot into its safe mode (volume-down during boot) disables all modules"
    [ -d /data/adb/ksu ] && echo "KernelSU: press volume-down several times during boot for safe mode"
    [ -x /system/xbin/su ] && [ "$(getprop ro.build.type)" = userdebug ] &&
        echo "userdebug build without a root manager: adb root, then touch the disable file above"
    :
    ;;
pid) pid ;;
hb) cat "$HB" ;;
task) fgs "$A.START_TASK" --el duration_ms "${2:-0}" --ei ballast_mb "${3:-0}" ;;
stop) fgs "$A.STOP_TASKS" ;;
crash) fgs "$A.CRASH" ;;
wait-fg) wait_fg "${2:-15}" ;;
kill-measure)
    # K1: SIGKILL the runtime while it has a task; measure until a new process is up and foreground.
    p=$(pid)
    [ -n "$p" ] || { echo "no runtime process"; exit 1; }
    t0=$(cs)
    kill -9 "$p"
    np=
    while [ $(($(cs) - t0)) -lt 12000 ]; do
        np=$(pid)
        [ -n "$np" ] && [ "$np" != "$p" ] && break
        np=
        sleep 0.1
    done
    t1=$(cs)
    [ -n "$np" ] || { echo "killed=$p NOT restarted within 120s"; exit 1; }
    while [ $(($(cs) - t1)) -lt 1500 ]; do
        [ "$(hbval pid)" = "$np" ] && [ "$(hbval fg)" = 1 ] && break
        sleep 0.1
    done
    t2=$(cs)
    echo "killed=$p new=$np process_up_ms=$(((t1 - t0) * 10)) foreground_ms=$(((t2 - t0) * 10)) hb_state=$(hbval state) tasks=$(hbval tasks)"
    ;;
safe-on) printf 'reason=manual\n' >"$STATE/safe_mode" ;;
safe-off) rm -f "$STATE/safe_mode" ;;
root-broadcast)
    for r in SupervisorStatusReceiver SupervisorStatusReceiverExported; do
        am broadcast --user 0 -n "$PKG/org.agentos.app.agent.supervisor.$r" -a org.agentos.action.SUPERVISOR_STATUS \
            --ei org.agentos.extra.PROTOCOL 1 --es org.agentos.extra.STATE ok --es org.agentos.extra.REASON root_test \
            --el org.agentos.extra.SEQ 424242
    done
    ;;
boot-log)
    # supervisor.log since the last "supervisor start" (this boot only)
    awk '/supervisor start/ { n = 0 } { l[n++] = $0 } END { for (i = 0; i < n; i++) print l[i] }' "$STATE/supervisor.log"
    ;;
logs)
    echo "== supervisor.log"; tail -n "${2:-150}" "$STATE/supervisor.log" 2>/dev/null
    echo "== status"; cat "$STATE/status" 2>/dev/null
    echo "== safe_mode"; cat "$STATE/safe_mode" 2>/dev/null
    echo "== heartbeat"; cat "$HB" 2>/dev/null
    echo "== app-side supervisor status"; cat "$DE/supervisor/status" 2>/dev/null
    echo "== events.log"; tail -n "${2:-150}" "$DE/spike/events.log" 2>/dev/null
    echo "== client probe.log"; tail -n 20 "/data/data/$CLI/files/probe.log" 2>/dev/null
    ;;
start-supervisor)
    # Run the prototype without the module (context: su, not the manager's service.sh context).
    AGENTOS_PKG=$PKG AGENTOS_CLS_PREFIX=org.agentos.app AGENTOS_STATE_DIR=$STATE \
        nohup sh "$DIR/supervisor.sh" >/dev/null 2>&1 &
    echo "started supervisor pid=$!"
    ;;
unpack-module)
    # adb-root mode (no Magisk / KernelSU): place the module where a root manager would, by hand.
    # Nothing is mounted and nothing runs at boot; s2.sh starts service.sh after each boot.
    M=/data/adb/modules/agentos_s2
    [ -f "$2" ] || { echo "no zip: $2"; exit 1; }
    [ -d "$M" ] && mv "$M" "$M.old.$$" && rm -rf "$M.old.$$"
    mkdir -p "$M" && unzip -o -q "$2" -d "$M" && rm -rf "$M/META-INF" && chmod 755 "$M"/*.sh
    echo "module unpacked into $M: $(ls "$M" | tr '\n' ' ')"
    ;;
stop-supervisor)
    # TERM is handled after the supervisor's current `sleep` returns (up to 5 s): wait for the exit,
    # otherwise a new instance sees the old pid and refuses to start.
    read -r sp <"$STATE/supervisor.pid" && kill "$sp" && echo "stopping $sp"
    i=0
    while [ -n "$sp" ] && [ -d "/proc/$sp" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
    [ -d "/proc/$sp" ] && echo "still running after 10 s" || echo "stopped"
    ;;
monitor)
    # Soak monitor: every $2 seconds for $3 hours, one CSV line. Run with nohup (s2.sh soak).
    iv=${2:-300}
    n=$(((${3:-24} * 3600) / iv))
    f=$DIR/soak.csv
    echo "epoch,uptime_s,pid,tasks,fg,pss_kb,rss_kb,wakefulness,deep_idle,battery,plugged" >"$f"
    i=0
    while [ $i -le $n ]; do
        p=$(pid)
        mem=$( [ -n "$p" ] && dumpsys meminfo "$p" 2>/dev/null | grep -m1 'TOTAL PSS')
        pss=$(echo "$mem" | sed -n 's/.*TOTAL PSS: *\([0-9]*\).*/\1/p')
        rss=$(echo "$mem" | sed -n 's/.*TOTAL RSS: *\([0-9]*\).*/\1/p')
        wk=$(dumpsys power | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
        idle=$(dumpsys deviceidle get deep 2>/dev/null)
        lvl=$(dumpsys battery | sed -n 's/^ *level: //p')
        plug=$(dumpsys battery | grep -cE '(AC|USB|Wireless) powered: true')
        read -r u _ </proc/uptime
        echo "$(date +%s),${u%.*},$p,$(hbval tasks),$(hbval fg),$pss,$rss,$wk,$idle,$lvl,$plug" >>"$f"
        i=$((i + 1))
        sleep "$iv"
    done
    ;;
*)
    echo "usage: s2dev.sh info|rescue-check|pid|hb|task <ms> <mb>|stop|crash|wait-fg <s>|kill-measure|safe-on|safe-off|root-broadcast|logs [n]|start-supervisor|stop-supervisor|monitor <interval_s> <hours>" >&2
    exit 2
    ;;
esac
