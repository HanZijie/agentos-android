#!/bin/sh
# Host-side simulation of module/service.sh (install phase + root supervisor), ported from
# spikes/S2/test/sim.sh. Fake procfs + fake am / pm / pidof / getprop / dumpsys / settings / stat and a
# virtual clock: the `sleep` shim advances /proc/uptime and plays scheduled events, so minutes of
# supervision run in seconds. Checks the state machine only; Android behaviour is covered on devices
# (tools/smoke-test.sh, docs/spikes/S2.md). Nothing is ever deleted: dead processes and replaced files
# are moved into $SIM/graveyard.
#
# Usage: sh module/test/service-sim.sh [scenario ...]      SIM_SHELL=dash|bash|sh (default dash)
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
MODSRC=$(cd "$HERE/.." && pwd)
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
set_knob() { sed -i.bak "s/^$1=.*/$1=$2/" "$SIM/scenario"; }
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
    set_knob STOPPED false
    set_knob NOTLAUNCHED false
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
    cat >"$B/pm" <<'EOF'
#!/bin/sh
. "$SIM/world.sh"
echo "$(now) pm $*" >>"$SIM/calls.log"
case $1 in
list)
    [ -n "$INSTALLED_VC" ] && [ "$4" = "$PKG" ] && echo "package:$PKG versionCode:$INSTALLED_VC"
    ;;
path) echo "package:/system/framework/framework-res.apk" ;;
install)
    [ -t 0 ] || cat >/dev/null
    if [ "$SIG_MISMATCH" = 1 ]; then
        echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package $PKG signatures do not match newer version; ignoring!]"
        exit 1
    fi
    set_knob INSTALLED_VC "$ZIP_VC"
    echo "$(now) INSTALLED vc=$ZIP_VC" >>"$SIM/events.log"
    echo "Success"
    ;;
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
. "$SIM/scenario"
case $1 in
sys.boot_completed) echo 1 ;;
sys.user.0.ce_available) echo true ;;
ro.build.version.sdk) echo "$API" ;;
ro.build.fingerprint) echo "google/sim/sim:16/SIM/1:user/release-keys" ;;
esac
EOF
    printf '#!/bin/sh\necho 7\n' >"$B/settings"
    cat >"$B/stat" <<'EOF'
#!/bin/sh
case $2 in %u) echo 10123 ;; %s) wc -c <"$3" | tr -d ' ' ;; esac
EOF
    printf '#!/bin/sh\nshasum -a 256 "$@"\n' >"$B/sha256sum"
    # never delete anything, not even the install copy in $AGENTOS_TMPDIR: move it to the graveyard
    cat >"$B/rm" <<'EOF'
#!/bin/sh
for _a in "$@"; do
    case $_a in -*) ;; *) [ -e "$_a" ] && mv "$_a" "$SIM/graveyard/rm-$$-${_a##*/}" ;; esac
done
exit 0
EOF
    cat >"$B/dumpsys" <<'EOF'
#!/bin/sh
. "$SIM/scenario"
echo "Packages:"
echo "    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=$STOPPED notLaunched=$NOTLAUNCHED enabled=0"
EOF
    chmod +x "$B"/*
}

setup() { # name, then scenario variables as KEY=VALUE
    NAME=$1
    shift
    SIM=$(mktemp -d "${TMPDIR:-/tmp}/agentos-module-sim.XXXXXX")
    export SIM
    mkdir -p "$SIM/proc" "$SIM/data/user_de/0/$PKG/files/supervisor" "$SIM/data/adb" "$SIM/mod/app" \
        "$SIM/graveyard" "$SIM/tmp"
    echo "100.00 0" >"$SIM/proc/uptime"
    echo 2000 >"$SIM/nextpid"
    echo 1 >"$SIM/nextgrave"
    {
        printf '%s\n' LIFE=inf TASKS=1 HB_ON_START=1 STOPPED=false NOTLAUNCHED=false END=700 \
            API=36 INSTALLED_VC=1 ZIP_VC=1 SIG_MISMATCH=0 CORRUPT=0
        for kv in "$@"; do printf '%s\n' "$kv"; done
    } >"$SIM/scenario"
    . "$SIM/scenario"
    # the module as the root manager would unpack it
    cp "$MODSRC/service.sh" "$MODSRC/common.sh" "$MODSRC/apks.sh" "$MODSRC/support-matrix.yaml" "$SIM/mod/"
    sed -e "s/@VERSION@/0.1.0-sim/" -e "s/@VERSION_CODE@/1/" "$MODSRC/module.prop.template" >"$SIM/mod/module.prop"
    printf 'fake agentos apk v%s\n' "$ZIP_VC" >"$SIM/mod/app/AgentOS.apk"
    _sha=$(shasum -a 256 "$SIM/mod/app/AgentOS.apk" | cut -d' ' -f1)
    [ "$CORRUPT" = 1 ] && _sha=0000000000000000000000000000000000000000000000000000000000000000
    echo "$PKG $ZIP_VC app/AgentOS.apk $_sha -" >"$SIM/mod/app/apks.list"
    : >"$SIM/events.log"
    : >"$SIM/calls.log"
    write_world
    write_shims
}

run() {
    # BusyBox ash has a builtin `sleep` (Ubuntu's busybox 1.36: "sleep is a shell builtin"), and builtins win
    # over PATH. The shim in $SIM/bin/sleep would never run, the virtual clock would never advance and
    # service.sh would really sleep until the 240 s watchdog below kills it. A shell function wins over
    # builtins in every shell, so route `sleep` to the shim explicitly. The shim is still an external process,
    # so its $PPID is the same shell that a PATH lookup would have forked it from.
    cat >"$SIM/run-service.sh" <<'EOF'
sleep() { "$SIM/bin/sleep" "$@"; }
. "$SIM/mod/service.sh"
EOF
    # $SIM_SHELL unquoted on purpose: may be "busybox sh" (CI runs dash, bash --posix and busybox ash)
    PATH="$SIM/bin:$PATH" AGENTOS_MODDIR="$SIM/mod" AGENTOS_PROCFS="$SIM/proc" AGENTOS_DATA="$SIM/data" \
        AGENTOS_TMPDIR="$SIM/tmp" $SIM_SHELL "$SIM/run-service.sh" >"$SIM/stdout" 2>&1 &
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
desc_has() { grep '^description=' "$SIM/mod/module.prop" | grep -q -- "$1"; }

# ------------------------------------------------------------------ install phase

sc_fresh_install() {
    # First boot after flashing: the App is installed, never opened (stopped + notLaunched).
    setup fresh_install INSTALLED_VC= STOPPED=true NOTLAUNCHED=true TASKS=0 END=300
    printf '%s\n' '150 spawn' >"$SIM/schedule" # the user opens the App; its UI binds :agent
    run
    check "App installed from the zip" has "INSTALLED vc=1" "$SIM/events.log"
    check "no boot start for a never-opened App" [ "$(count 'START reason=boot' "$SIM/events.log")" = 0 ]
    check "state stopped (not_launched)" has "state -> stopped (not_launched)" "$LOGF"
    check "the first :agent process is adopted" has "state -> ok (runtime_up)" "$LOGF"
    check "no shell errors" no_shell_errors
}

sc_fresh_install_desc() {
    setup fresh_install_desc INSTALLED_VC= STOPPED=true NOTLAUNCHED=true TASKS=0 END=200
    printf '%s\n' '150 cp "$SIM/mod/module.prop" "$SIM/prop-at-150"' >"$SIM/schedule"
    run
    check "module.prop asks the user to open the App" grep -q "等待首次打开" "$SIM/prop-at-150"
    check "and says what was installed" grep -q "已安装 v1" "$SIM/prop-at-150"
}

sc_upgrade() {
    setup upgrade INSTALLED_VC=1 ZIP_VC=2 TASKS=0 END=200
    run
    check "App upgraded" has "INSTALLED vc=2" "$SIM/events.log"
    check "runtime started with REASON=upgrade" [ "$(count 'START reason=upgrade' "$SIM/events.log")" = 1 ]
    check "no plain boot start" [ "$(count 'START reason=boot' "$SIM/events.log")" = 0 ]
}

sc_same_version() {
    setup same_version TASKS=0 END=200
    run
    check "no pm install when the versions match" lacks " pm install" "$SIM/calls.log"
    check "boot start" [ "$(count 'START reason=boot' "$SIM/events.log")" = 1 ]
}

sc_sig_mismatch() {
    setup sig_mismatch INSTALLED_VC=1 ZIP_VC=2 SIG_MISMATCH=1 END=300
    run
    check "never starts a foreign App" lacks "START" "$SIM/events.log"
    check "supervisor exits (nothing to supervise)" has "nothing to supervise" "$LOGF"
    check "module.prop explains the mismatch" desc_has "签名不符"
    check "no shell errors" no_shell_errors
}

sc_corrupt_zip() {
    setup corrupt_zip INSTALLED_VC= CORRUPT=1 END=300
    run
    check "no pm install for a corrupt APK" lacks " pm install" "$SIM/calls.log"
    check "module.prop says the zip is broken" desc_has "SHA-256"
    check "never starts anything" lacks "START" "$SIM/events.log"
}

sc_unsupported_api() {
    setup unsupported_api API=38 END=200
    run
    check "safe mode unsupported_api" has "STATUS state=safe_mode reason=unsupported_api" "$SIM/events.log"
    check "runtime not started" lacks "START" "$SIM/events.log"
    check "safe_mode marker written" has "reason=unsupported_api" "$SIM/data/adb/agentos/safe_mode"
}

# ------------------------------------------------------------------ supervision (S2 v0.1)

sc_kill_loop() {
    setup kill_loop LIFE=10
    run
    check "restart delays are 1 2 4 8" [ "$(delays)" = "1 2 4 8" ]
    check "5th death enters safe mode (crash_loop)" has "STATUS state=safe_mode reason=crash_loop" "$SIM/events.log"
    check "safe_mode marker written" has "reason=crash_loop" "$SIM/data/adb/agentos/safe_mode"
    check "exactly 4 restarts" [ "$(count 'START reason=restart' "$SIM/events.log")" = 4 ]
    check "module.prop still shows safe mode after the supervisor is stopped" desc_has "\[安全模式\] 原因：crash_loop"
    check "no shell errors" no_shell_errors
}

sc_spread_deaths() {
    setup spread_deaths LIFE=150 END=1100
    run
    check "backoff resets after 60s up: all delays 1s" [ "$(delays)" = "1 1 1 1 1 1" ]
    check "never enters safe mode" lacks "safe_mode" "$SIM/events.log"
}

sc_user_stop() {
    setup user_stop LIFE=20 END=300
    printf '%s\n' '115 set_knob STOPPED true' >"$SIM/schedule"
    run
    check "no restart after user force-stop" [ "$(count 'START reason=restart' "$SIM/events.log")" = 0 ]
    check "state went to stopped (user_stopped)" has "state -> stopped (user_stopped)" "$LOGF"
}

sc_boot_stopped() {
    setup boot_stopped STOPPED=true TASKS=0 END=300
    printf '%s\n' '150 spawn' >"$SIM/schedule"
    run
    check "no boot start for a force-stopped package" [ "$(count 'START reason=boot' "$SIM/events.log")" = 0 ]
    check "reported user_stopped" has "state -> stopped (user_stopped)" "$LOGF"
    check "process started later is adopted, state ok" has "state -> ok (runtime_up)" "$LOGF"
}

sc_idle_death() {
    setup idle_death LIFE=20 TASKS=0 END=300
    run
    check "idle runtime death is not restarted" [ "$(count 'START reason=restart' "$SIM/events.log")" = 0 ]
}

sc_promote() {
    setup promote TASKS=0 END=300
    printf '%s\n' '150 _p=$(agent_pid) && write_hb "$_p" 1 0 busy' >"$SIM/schedule"
    run
    check "exactly one promotion" [ "$(count 'START reason=promote' "$SIM/events.log")" = 1 ]
    _tp=$(sed -n 's/^\([0-9]*\) START reason=promote/\1/p' "$SIM/events.log")
    check "promoted within 5s (t=${_tp:-none})" [ "${_tp:-999}" -le 155 ]
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
    check "reports module_disabled and exits" has "STATUS state=stopped reason=module_disabled" "$SIM/events.log"
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
    check "leaving safe mode starts the runtime once" [ "$(count 'START reason=safe_mode_exit' "$SIM/events.log")" = 1 ]
}

sc_short_lived() {
    setup short_lived TASKS=0 END=300
    printf '%s\n' \
        '120 kill_agent' \
        '150 TASKS=1; spawn; kill_agent' \
        '200 TASKS=0; spawn; kill_agent' >"$SIM/schedule"
    run
    check "unobserved busy runtime is noticed" has "unobserved runtime pid=2001" "$LOGF"
    check "exactly 1 restart" [ "$(count 'START reason=restart' "$SIM/events.log")" = 1 ]
}

sc_instant_crash() {
    setup instant_crash LIFE=0 END=400
    run
    check "boot start failure is counted and retried" has "restart in 1s (attempt 1, deaths in window 1)" "$LOGF"
    check "fails fast" lacks "did not come up within" "$LOGF"
    check "delays 1 2 4 8, then safe mode" [ "$(delays)" = "1 2 4 8" ]
}

sc_stop_during_backoff() {
    setup stop_during_backoff LIFE=10 END=300
    printf '%s\n' '111 set_knob STOPPED true' >"$SIM/schedule"
    run
    check "no restart after a force-stop during backoff" [ "$(count 'START reason=restart' "$SIM/events.log")" = 0 ]
    check "detected right before the restart" has "force-stopped during backoff" "$LOGF"
}

sc_recovering_task() {
    # v0.2: a task registered during recovery counts (recovering tasks>0); recovering tasks=0 does not.
    setup recovering_task TASKS=0 END=300
    printf '%s\n' \
        '120 kill_agent' \
        '150 spawn; _p=$(agent_pid); write_hb "$_p" 1 1 recovering' \
        '160 kill_agent' \
        '195 kill_agent' \
        '200 spawn; _p=$(agent_pid); write_hb "$_p" 0 1 recovering' \
        '210 kill_agent' >"$SIM/schedule"
    run
    check "death while recovering with a task is restarted" [ "$(count 'START reason=restart' "$SIM/events.log")" = 1 ]
    _tr=$(sed -n 's/^\([0-9]*\) START reason=restart/\1/p' "$SIM/events.log" | head -n 1)
    check "restart comes after the recovering death (t=${_tr:-none})" [ "${_tr:-0}" -ge 160 ] && [ "${_tr:-999}" -le 170 ]
}

sc_hostile_heartbeat() {
    setup hostile_hb END=400
    printf '%s\n' '130 write_hostile_hb' '150 symlink_hb' '170 fifo_hb' '200 kill_agent' >"$SIM/schedule"
    run
    check "nothing from the heartbeat was executed" [ ! -e "$SIM/pwned" ]
    check "did not block on a FIFO heartbeat (reached END)" reached_end
    check "no shell errors" no_shell_errors
}

ALL="fresh_install fresh_install_desc upgrade same_version sig_mismatch corrupt_zip unsupported_api
kill_loop spread_deaths user_stop boot_stopped idle_death promote crash_before_heartbeat module_disabled
manual_safe_mode short_lived instant_crash stop_during_backoff recovering_task hostile_heartbeat"
echo "module/service.sh simulation with $SIM_SHELL"
for s in ${*:-$ALL}; do
    "sc_$s"
done
echo "passed $PASS, failed $FAIL"
[ $FAIL = 0 ]
