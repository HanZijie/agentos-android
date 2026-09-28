#!/bin/sh
# Host-side simulation of spikes/S2/device/supervisor.sh.
#
# Fake procfs + fake am / pidof / getprop / dumpsys / settings / stat, and a virtual clock: the
# `sleep` shim advances /proc/uptime and plays scheduled events, so minutes of supervision run in
# seconds. This checks the state machine only; Android behaviour is covered on devices.
# Nothing is ever deleted: dead processes and replaced files are moved into $SIM/graveyard.
#
# Usage: sh spikes/S2/test/sim.sh [scenario ...]      SIM_SHELL=dash|bash|sh (default dash)
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
SUP="$HERE/../device/supervisor.sh"
SIM_SHELL=${SIM_SHELL:-dash}
PKG=org.agentos.app
PASS=0
FAIL=0

# ------------------------------------------------------------------ world (sourced by the shims)
write_world() {
    cat >"$SIM/world.sh" <<'EOF'
PKG=org.agentos.app
HBF="$SIM/data/user_de/0/$PKG/files/supervisor/heartbeat"
. "$SIM/scenario"
now() { read -r _u _r <"$SIM/proc/uptime"; echo "${_u%%.*}"; }
put() { # file, content via stdin; atomic replace
    cat >"$1.tmp"
    mv -f "$1.tmp" "$1"
}
bury() {
    read -r _g <"$SIM/nextgrave"
    echo $((_g + 1)) >"$SIM/nextgrave"
    mv "$1" "$SIM/graveyard/$_g-${1##*/}"
}
write_hb() { # pid tasks fg state
    printf 'v=1\npid=%s\nstart=0\nat=%s000\nboot=7\ntasks=%s\nfg=%s\nstate=%s\n' "$1" "$(now)" "$2" "$3" "$4" | put "$HBF"
}
write_hostile_hb() {
    printf '%s\n' 'pid=$(touch "$SIM/pwned")' 'tasks=1;touch "$SIM/pwned"' 'state=busy`touch "$SIM/pwned"`' | put "$HBF"
}
symlink_hb() { ln -s /etc/hosts "$SIM/hb-link" && mv -f "$SIM/hb-link" "$HBF"; }
fifo_hb() { mkfifo "$SIM/hb-fifo" && mv -f "$SIM/hb-fifo" "$HBF"; }
agent_pid() {
    for _d in "$SIM"/proc/[0-9]*; do
        [ -f "$_d/name" ] && [ "$(cat "$_d/name")" = "$PKG:agent" ] && { echo "${_d##*/}"; return 0; }
    done
    return 1
}
spawn() {
    read -r _pid <"$SIM/nextpid"
    echo $((_pid + 1)) >"$SIM/nextpid"
    mkdir "$SIM/proc/$_pid"
    printf 'Name:\tapp:agent\nUid:\t10123\t10123\t10123\t10123\n' >"$SIM/proc/$_pid/status"
    echo "$PKG:agent" >"$SIM/proc/$_pid/name"
    [ "$LIFE" = inf ] || echo $(($(now) + LIFE)) >"$SIM/proc/$_pid/die_at"
    if [ "$HB_ON_START" = 1 ]; then
        if [ "$TASKS" -gt 0 ]; then write_hb "$_pid" "$TASKS" 1 busy; else write_hb "$_pid" 0 0 idle; fi
    fi
    echo "$(now) SPAWN pid=$_pid" >>"$SIM/events.log"
}
kill_agent() {
    _p=$(agent_pid) || return 0
    bury "$SIM/proc/$_p"
    echo "$(now) KILLED pid=$_p" >>"$SIM/events.log"
}
advance() {
    _t=$(($(now) + 1))
    echo "$_t.00 0" >"$SIM/proc/uptime"
    for _d in "$SIM"/proc/[0-9]*; do
        [ -f "$_d/die_at" ] || continue
        read -r _die <"$_d/die_at"
        if [ "$_die" -le "$_t" ]; then
            bury "$_d"
            echo "$_t DIED pid=${_d##*/}" >>"$SIM/events.log"
        fi
    done
    if [ -f "$SIM/schedule" ]; then
        while read -r _at _cmd; do
            if [ "$_at" = "$_t" ]; then eval "$_cmd"; fi
        done <"$SIM/schedule"
    fi
    :
}
EOF
}

write_shims() {
    B="$SIM/bin"
    mkdir -p "$B"
    cat >"$B/sleep" <<'EOF'
#!/bin/sh
. "$SIM/world.sh"
_i=0; while [ $_i -lt "${1%%.*}" ]; do advance; _i=$((_i + 1)); done
[ "$(now)" -ge "$END" ] && kill -TERM $PPID
exit 0
EOF
    cat >"$B/am" <<'EOF'
#!/bin/sh
. "$SIM/world.sh"
echo "$(now) am $*" >>"$SIM/calls.log"
_cmd=$1; shift
_state=; _reason=
while [ $# -gt 0 ]; do
    case $1 in
    --es) case $2 in *.STATE) _state=$3 ;; *.REASON) _reason=$3 ;; esac; shift 3 ;;
    *) shift ;;
    esac
done
case $_cmd in
start-foreground-service)
    if _p=$(agent_pid); then
        # onStartCommand in a running process: the service goes foreground if it has tasks.
        if [ -f "$HBF" ] && grep -q "^pid=$_p$" "$HBF" && grep -q '^tasks=[1-9]' "$HBF"; then
            write_hb "$_p" "$(sed -n 's/^tasks=//p' "$HBF")" 1 busy
        fi
    else
        spawn
    fi
    echo "$(now) START reason=$_reason" >>"$SIM/events.log"
    echo "Starting service: Intent { act=org.agentos.action.SUPERVISOR_START }"
    ;;
broadcast)
    echo "$(now) STATUS state=$_state reason=$_reason" >>"$SIM/events.log"
    echo "Broadcast completed: result=0"
    ;;
get-started-user-state) echo "RUNNING_UNLOCKED" ;;
esac
EOF
    cat >"$B/pidof" <<'EOF'
#!/bin/sh
for _d in "$SIM"/proc/[0-9]*; do
    [ -f "$_d/name" ] && [ "$(cat "$_d/name")" = "$1" ] && printf '%s ' "${_d##*/}"
done
echo
EOF
    cat >"$B/getprop" <<'EOF'
#!/bin/sh
case $1 in sys.boot_completed) echo 1 ;; sys.user.0.ce_available) echo true ;; esac
EOF
    printf '#!/bin/sh\necho 7\n' >"$B/settings"
    printf '#!/bin/sh\necho 10123\n' >"$B/stat"
    cat >"$B/dumpsys" <<'EOF'
#!/bin/sh
. "$SIM/world.sh"
echo "Packages:"
echo "    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=$STOPPED notLaunched=false"
EOF
    chmod +x "$B"/*
}

setup() { # name, then scenario variables as KEY=VALUE
    NAME=$1
    shift
    SIM=$(mktemp -d "${TMPDIR:-/tmp}/agentos-s2-sim.XXXXXX")
    export SIM
    mkdir -p "$SIM/proc" "$SIM/data/user_de/0/$PKG/files/supervisor" "$SIM/data/adb" "$SIM/mod" "$SIM/graveyard"
    echo "100.00 0" >"$SIM/proc/uptime"
    echo 2000 >"$SIM/nextpid"
    echo 1 >"$SIM/nextgrave"
    printf 'id=agentos\nname=AgentOS\nversion=0.1.0-sim\nversionCode=1\ndescription=init\n' >"$SIM/mod/module.prop"
    {
        printf '%s\n' LIFE=inf TASKS=1 HB_ON_START=1 STOPPED=false END=700
        for kv in "$@"; do printf '%s\n' "$kv"; done
    } >"$SIM/scenario"
    : >"$SIM/events.log"
    : >"$SIM/calls.log"
    write_world
    write_shims
}

run() {
    PATH="$SIM/bin:$PATH" AGENTOS_PROCFS="$SIM/proc" AGENTOS_DATA="$SIM/data" AGENTOS_MODDIR="$SIM/mod" \
        "$SIM_SHELL" "$SUP" >"$SIM/stdout" 2>&1 &
    _sp=$!
    (sleep 240 && kill -9 $_sp 2>/dev/null) &
    _wd=$!
    wait $_sp || true
    kill $_wd 2>/dev/null || true
    LOGF="$SIM/data/adb/agentos/supervisor.log"
}

ok() {
    PASS=$((PASS + 1))
    echo "  ok   $NAME: $*"
}
bad() {
    FAIL=$((FAIL + 1))
    echo "  FAIL $NAME: $*"
    echo "  --- events ($SIM/events.log)"
    tail -30 "$SIM/events.log" | sed 's/^/    /'
    echo "  --- supervisor.log"
    tail -30 "$LOGF" 2>/dev/null | sed 's/^/    /'
    echo "  --- stdout"
    tail -10 "$SIM/stdout" | sed 's/^/    /'
}
check() { # description, command...
    _d=$1
    shift
    if "$@"; then ok "$_d"; else bad "$_d"; fi
}
count() { grep -c -- "$1" "$2" || true; }
has() { grep -q -- "$1" "$2"; }
lacks() { ! grep -q -- "$1" "$2"; }
delays() { sed -n 's/.*restart in \([0-9]*\)s.*/\1/p' "$LOGF" | tr '\n' ' ' | sed 's/ $//'; }
no_shell_errors() { ! grep -qiE 'not found|syntax error|unexpected|bad substitution|parameter not set|unmatched' "$SIM/stdout"; }
reached_end() { has "terminated by signal" "$LOGF"; }

# ------------------------------------------------------------------ scenarios

sc_kill_loop() {
    setup kill_loop LIFE=10
    run
    check "restart delays are 1 2 4 8" [ "$(delays)" = "1 2 4 8" ]
    check "5th death enters safe mode (crash_loop)" has "STATUS state=safe_mode reason=crash_loop" "$SIM/events.log"
    check "safe_mode marker written" has "reason=crash_loop" "$SIM/data/adb/agentos/safe_mode"
    check "exactly 4 restarts" [ "$(count 'START reason=restart' "$SIM/events.log")" = 4 ]
    check "no restart after safe mode" [ "$(sed -n '/safe_mode reason=crash_loop/,$p' "$SIM/events.log" | grep -c 'START reason=restart')" = 0 ]
    check "module.prop still shows safe mode after the supervisor is stopped" has "description=\[安全模式\] 原因：crash_loop" "$SIM/mod/module.prop"
    check "no shell errors" no_shell_errors
}

sc_spread_deaths() {
    setup spread_deaths LIFE=150 END=1100
    run
    check "backoff resets after 60s up: all delays 1s" [ "$(delays)" = "1 1 1 1 1 1" ]
    check "never enters safe mode" lacks "safe_mode" "$SIM/events.log"
    check "no shell errors" no_shell_errors
}

sc_user_stop() {
    setup user_stop LIFE=20 END=300
    printf '%s\n' '115 sed -i.bak "s/^STOPPED=.*/STOPPED=true/" "$SIM/scenario"' >"$SIM/schedule"
    run
    check "no restart after user force-stop" [ "$(count 'START reason=restart' "$SIM/events.log")" = 0 ]
    check "logged as user force-stop" has "user force-stop" "$LOGF"
    check "state went to stopped (user_stopped)" has "state -> stopped (user_stopped)" "$LOGF"
}

sc_boot_stopped() {
    # Package still stopped at boot (force-stopped before the reboot, or never opened): no boot start.
    # Later the user opens the App / a client binds, and the supervisor adopts the new process.
    setup boot_stopped STOPPED=true TASKS=0 END=300
    printf '%s\n' '150 spawn' >"$SIM/schedule"
    run
    check "no boot start for a stopped package" [ "$(count 'START reason=boot' "$SIM/events.log")" = 0 ]
    check "reported stopped at boot" has "package is in the stopped state at boot" "$LOGF"
    check "process started later is adopted, state ok" has "state -> ok (runtime_up)" "$LOGF"
}

sc_idle_death() {
    setup idle_death LIFE=20 TASKS=0 END=300
    run
    check "idle runtime death is not restarted" [ "$(count 'START reason=restart' "$SIM/events.log")" = 0 ]
    check "no safe mode" lacks "safe_mode" "$SIM/events.log"
}

sc_promote() {
    setup promote TASKS=0 END=300
    # t=150: a task arrives while the runtime is in the background and its own FGS start was denied.
    printf '%s\n' '150 _p=$(agent_pid) && write_hb "$_p" 1 0 busy && echo "$(now) TASK_BG pid=$_p" >>"$SIM/events.log"' >"$SIM/schedule"
    run
    check "exactly one promotion" [ "$(count 'START reason=promote' "$SIM/events.log")" = 1 ]
    _tp=$(sed -n 's/^\([0-9]*\) START reason=promote/\1/p' "$SIM/events.log")
    check "promoted within 5s of the background task (t=${_tp:-none})" [ "${_tp:-999}" -le 155 ]
    check "heartbeat now fg=1" has "^fg=1" "$SIM/data/user_de/0/$PKG/files/supervisor/heartbeat"
}

sc_crash_before_heartbeat() {
    setup crash_before_hb LIFE=2 HB_ON_START=0
    printf 'v=1\npid=1\nstart=0\nat=0\nboot=6\ntasks=1\nfg=1\nstate=busy\n' \
        >"$SIM/data/user_de/0/$PKG/files/supervisor/heartbeat"
    run
    check "previous boot's task makes deaths count" has "need=1" "$LOGF"
    check "crash loop during recovery ends in safe mode" has "STATUS state=safe_mode reason=crash_loop" "$SIM/events.log"
}

sc_module_disabled() {
    setup module_disabled END=400
    printf '%s\n' '130 touch "$SIM/mod/disable"' >"$SIM/schedule"
    run
    check "supervisor exits and reports module_disabled" has "STATUS state=stopped reason=module_disabled" "$SIM/events.log"
    check "exited on its own before END" has "module disabled or removed" "$LOGF"
}

sc_manual_safe_mode() {
    setup manual_safe END=400
    printf '%s\n' \
        '130 printf "reason=manual\n" >"$SIM/data/adb/agentos/safe_mode"' \
        '140 kill_agent' \
        '200 bury "$SIM/data/adb/agentos/safe_mode"' >"$SIM/schedule"
    run
    check "manual safe mode reported" has "STATUS state=safe_mode reason=manual" "$SIM/events.log"
    check "no restart while in safe mode" [ "$(count 'START reason=restart' "$SIM/events.log")" = 0 ]
    check "leaving safe mode reports ok" has "STATUS state=ok reason=safe_mode_exited" "$SIM/events.log"
    check "leaving safe mode starts the runtime once" [ "$(count 'START reason=safe_mode_exit' "$SIM/events.log")" = 1 ]
}

sc_short_lived() {
    # Found on the API 35 emulator (K1): a runtime started by a bind took a task and was killed
    # 0.2 s later, between two 5 s idle polls, so the supervisor never saw it.
    setup short_lived TASKS=0 END=300
    printf '%s\n' \
        '120 kill_agent' \
        '150 TASKS=1; spawn; kill_agent' \
        '200 TASKS=0; spawn; kill_agent' >"$SIM/schedule"
    run
    check "unobserved busy runtime is noticed" has "unobserved runtime pid=2001" "$LOGF"
    _tr=$(sed -n 's/^\([0-9]*\) START reason=restart/\1/p' "$SIM/events.log" | head -n 1)
    check "restarted within 7 s (t=${_tr:-none})" [ "${_tr:-999}" -le 157 ]
    check "unobserved idle runtime is not restarted (exactly 1 restart)" \
        [ "$(count 'START reason=restart' "$SIM/events.log")" = 1 ]
    check "no shell errors" no_shell_errors
}

sc_instant_crash() {
    # Every runtime dies within its first second, after writing a busy heartbeat (crash during
    # recovery). Includes the boot start, which used to be neither counted nor retried.
    setup instant_crash LIFE=0 END=400
    run
    check "boot start failure is counted and retried" has "restart in 1s (attempt 1, deaths in window 1)" "$LOGF"
    check "fails fast, never waits the full START_WAIT" lacks "did not come up within" "$LOGF"
    check "delays 1 2 4 8, then safe mode" [ "$(delays)" = "1 2 4 8" ]
    check "crash loop ends in safe mode" has "STATUS state=safe_mode reason=crash_loop" "$SIM/events.log"
    _ts=$(sed -n 's/^\([0-9]*\) STATUS state=safe_mode.*/\1/p' "$SIM/events.log")
    check "safe mode reached by t=125 (t=${_ts:-none})" [ "${_ts:-999}" -le 125 ]
    check "no shell errors" no_shell_errors
}

sc_stop_during_backoff() {
    setup stop_during_backoff LIFE=10 END=300
    # First death at t=110 (detected at once); the force-stop lands at t=111, inside the 1 s backoff.
    printf '%s\n' '111 sed -i.bak "s/^STOPPED=.*/STOPPED=true/" "$SIM/scenario"' >"$SIM/schedule"
    run
    check "no restart after a force-stop during backoff" [ "$(count 'START reason=restart' "$SIM/events.log")" = 0 ]
    check "detected right before the restart" has "force-stopped during backoff" "$LOGF"
}

sc_hostile_heartbeat() {
    setup hostile_hb END=400
    printf '%s\n' '130 write_hostile_hb' '150 symlink_hb' '170 fifo_hb' '200 kill_agent' >"$SIM/schedule"
    run
    check "nothing from the heartbeat was executed" [ ! -e "$SIM/pwned" ]
    check "did not block on a FIFO heartbeat (reached END)" reached_end
    check "still restarted the runtime after the kill (need from last valid heartbeat)" \
        [ "$(count 'START reason=restart' "$SIM/events.log")" = 1 ]
    check "no shell errors" no_shell_errors
}

ALL="kill_loop spread_deaths user_stop boot_stopped idle_death promote crash_before_heartbeat module_disabled manual_safe_mode short_lived instant_crash stop_during_backoff hostile_heartbeat"
echo "supervisor.sh simulation with $SIM_SHELL"
for s in ${*:-$ALL}; do
    "sc_$s"
done
echo "passed $PASS, failed $FAIL"
[ $FAIL = 0 ]
