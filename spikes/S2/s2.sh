#!/bin/sh
# Host-side driver for the S2 cases on a rooted device (see docs/spikes/S2.md, "测试用例").
# Uses only the device assigned by the integrator: ANDROID_SERIAL must be set.
#   sh spikes/S2/s2.sh check|install|boot|kill [n]|loop|crash|forcestop|screenoff [min]|bgtask|spoof|soak [h] [mb]|soak-report|collect
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device assigned to lane D}"
ADB="adb -s $ANDROID_SERIAL"
OUT="$HERE/build/out"
RES="$HERE/build/results/$ANDROID_SERIAL"
DIR=/data/local/tmp/agentos-s2
PKG=org.agentos.spike.s2
CLI=org.agentos.spike.s2.client
RCV=org.agentos.app.agent.supervisor

r() { $ADB shell "su -c 'sh $DIR/s2dev.sh $*'"; }
say() { printf '\n### %s\n' "$*"; }
probe() { # extra args for the client's PROBE broadcast (sent as shell uid, no root)
    $ADB shell am broadcast -n "$CLI/.ProbeReceiver" -a org.agentos.spike.s2.client.PROBE "$@" >/dev/null
}

cmd=${1:-help}
[ $# -gt 0 ] && shift
case $cmd in
check)
    $ADB get-state
    r info
    say "rescue path (required before any reboot test)"
    r rescue-check
    ;;
install)
    [ -f "$OUT/s2-agent.apk" ] || sh "$HERE/build.sh"
    $ADB install -r "$OUT/s2-agent.apk"
    $ADB install -r "$OUT/s2-client.apk"
    $ADB shell mkdir -p $DIR
    $ADB push "$OUT/s2dev.sh" "$OUT/supervisor.sh" "$OUT/agentos-s2-module.zip" $DIR/ >/dev/null
    if $ADB shell "su -c 'command -v magisk'" >/dev/null 2>&1; then
        $ADB shell "su -c 'magisk --install-module $DIR/agentos-s2-module.zip'"
    else
        $ADB shell "su -c '/data/adb/ksud module install $DIR/agentos-s2-module.zip'"
    fi
    echo "module installed; run: sh $0 boot"
    ;;
boot)
    say "B0 boot: reboot, then the supervisor starts the runtime once (F2)"
    $ADB reboot
    $ADB wait-for-device
    until [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
    echo "boot completed. Unlock the device now (the supervisor waits for user 0 to unlock)."
    i=0
    until r logs 400 | grep -q "runtime up"; do
        i=$((i + 1))
        [ $i -gt 90 ] && { echo "runtime not started after 3 min"; break; }
        sleep 2
    done
    r logs 40
    ;;
kill)
    say "K1 kill -9 while a task is running, ${1:-3} times, 70 s apart (backoff resets after 60 s)"
    r task 0 0 >/dev/null
    r wait-fg 15
    n=0
    while [ $n -lt "${1:-3}" ]; do
        r kill-measure
        n=$((n + 1))
        [ $n -lt "${1:-3}" ] && sleep 70
    done
    r stop >/dev/null
    ;;
loop)
    say "K2 crash loop: 5 kills in a row must end in safe mode without a 5th restart"
    r task 0 0 >/dev/null
    r wait-fg 15
    n=0
    while [ $n -lt 5 ]; do
        r kill-measure || true
        n=$((n + 1))
    done
    sleep 20
    r logs 60 | grep -E "restart in|SAFE MODE|state ->|STATUS_RECEIVED"
    echo "pid after loop: '$(r pid | tr -d '\r')' (expected empty)"
    r safe-off
    echo "safe_mode marker removed; supervisor reports safe_mode_exited and starts the runtime once"
    sleep 10
    r stop >/dev/null
    ;;
crash)
    say "K4 uncaught exception in :agent while a task is running"
    r task 0 0 >/dev/null
    r wait-fg 15
    r crash >/dev/null
    sleep 8
    r logs 30 | grep -E "CRASH|gone|restart|RECOVER|FOREGROUND|EXIT_INFO"
    r stop >/dev/null
    ;;
forcestop)
    say "K5 user force-stop while a task is running: must not be restarted"
    r task 0 0 >/dev/null
    r wait-fg 15
    $ADB shell am force-stop $PKG
    sleep 90
    echo "pid after 90 s: '$(r pid | tr -d '\r')' (expected empty)"
    r logs 20 | grep -E "force-stop|user_stopped|restart"
    ;;
screenoff)
    min=${1:-30}
    say "B1 screen off + forced deep idle for $min min, then bind from another App (target: first response <= 3 s)"
    r stop >/dev/null || true
    $ADB shell dumpsys battery unplug
    $ADB shell input keyevent KEYCODE_SLEEP
    $ADB shell dumpsys deviceidle force-idle
    echo "idle since $(date '+%H:%M:%S'); runtime pid now: '$(r pid | tr -d '\r')'"
    sleep $((min * 60))
    echo "runtime pid before probe: '$(r pid | tr -d '\r')' (empty = killed while idle, bind cold-starts it)"
    probe
    sleep 25
    r logs 5 | sed -n '/client probe.log/,$p'
    $ADB shell dumpsys deviceidle unforce
    $ADB shell dumpsys battery reset
    ;;
bgtask)
    say "T1 task started by a background App while AgentOS is not battery-exempt (self FGS start vs root promotion)"
    $ADB shell dumpsys deviceidle whitelist -$PKG >/dev/null || true
    r stop >/dev/null || true
    $ADB shell input keyevent KEYCODE_SLEEP
    sleep 5
    probe --el task_ms 120000 --el hold_ms 3000
    sleep 20
    r logs 40 | grep -E "PROBE|FGS_SELF_START|promoting|FOREGROUND|fg="
    r stop >/dev/null
    ;;
spoof)
    say "P1 status broadcast: only root gets through"
    $ADB shell am broadcast -n "$CLI/.ProbeReceiver" -a org.agentos.spike.s2.client.SPOOF >/dev/null
    for rc in SupervisorStatusReceiver SupervisorStatusReceiverExported; do
        echo "shell uid -> $rc:"
        $ADB shell am broadcast -n "$PKG/$RCV.$rc" -a org.agentos.action.SUPERVISOR_STATUS \
            --ei org.agentos.extra.PROTOCOL 1 --es org.agentos.extra.STATE safe_mode --es org.agentos.extra.REASON spoofed_by_shell || true
    done
    echo "root -> both receivers:"
    r root-broadcast
    sleep 3
    r logs 50 | grep -E "STATUS_RECEIVED|SPOOF"
    $ADB logcat -d | grep -E "Permission Denial.*(SUPERVISOR|org.agentos.spike.s2)" | tail -8
    ;;
soak)
    h=${1:-24}
    mb=${2:-150}
    say "L1 ${h} h soak: one long task holding ${mb} MB; CSV every 5 min on the device"
    r task 0 "$mb" >/dev/null
    r wait-fg 30
    $ADB shell "su -c 'nohup sh $DIR/s2dev.sh monitor 300 $h >/dev/null 2>&1 &'"
    echo "monitor started. Unplug USB (or: adb shell dumpsys battery unplug). Afterwards: sh $0 soak-report"
    ;;
soak-report)
    mkdir -p "$RES"
    $ADB pull $DIR/soak.csv "$RES/soak.csv" >/dev/null
    awk -F, 'NR>1 { n++; if ($3 != "" && $3 != last) { if (last != "") changes++; last = $3 }
                    if ($3 == "") gone++; if ($6 > max) max = $6; sum += $6 }
             END { printf "samples=%d pid_changes=%d samples_without_runtime=%d pss_max_kb=%d pss_avg_kb=%d\n",
                   n, changes, gone, max, (n ? sum / n : 0) }' "$RES/soak.csv"
    $ADB shell dumpsys activity exit-info $PKG | grep -E "reason|description|pss" | head -20
    ;;
collect)
    d="$RES/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$d"
    r info >"$d/info.txt"
    r logs 2000 >"$d/logs.txt"
    $ADB logcat -d >"$d/logcat.txt"
    $ADB shell dumpsys activity exit-info $PKG >"$d/exit-info.txt"
    $ADB shell dumpsys activity services $PKG >"$d/services.txt"
    echo "collected into $d"
    ;;
*)
    sed -n '2,4p' "$0"
    ;;
esac
