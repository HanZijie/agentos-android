#!/system/bin/sh
# AgentOS root supervisor - S2 prototype. W7 turns this loop into module/service.sh.
#
# Runs as root, either from a Magisk / KernelSU module's service.sh (late_start service) or by hand:
#   su -c 'AGENTOS_PKG=org.agentos.spike.s2 sh /data/local/tmp/agentos-s2/supervisor.sh'
#
# Contract: docs/spikes/S2.md. The supervisor only manages the process. From the runtime it reads
# nothing but integers and a short state enum in the heartbeat file; it never evaluates that file.
# Works with busybox ash (Magisk / KernelSU standalone mode) and mksh (/system/bin/sh).
# Always brace variables inside non-ASCII strings ("${X}。"): some shells read UTF-8 bytes as name chars.

PKG=${AGENTOS_PKG:-org.agentos.app}
CLS=${AGENTOS_CLS_PREFIX:-org.agentos.app}
SVC="$PKG/$CLS.agent.AgentService"
RCV="$PKG/$CLS.agent.supervisor.SupervisorStatusReceiver"
PROC="$PKG:agent"
USER_ID=0
PROCFS=${AGENTOS_PROCFS:-/proc}     # overridable only for the host-side simulation (spikes/S2/test/)
DATA=${AGENTOS_DATA:-/data}
STATE_DIR=${AGENTOS_STATE_DIR:-$DATA/adb/agentos}
MODDIR=${AGENTOS_MODDIR:-}
HB="$DATA/user_de/$USER_ID/$PKG/files/supervisor/heartbeat"
SAFE="$STATE_DIR/safe_mode"
LOG="$STATE_DIR/supervisor.log"
STATUS_FILE="$STATE_DIR/status"

POLL_BUSY=${AGENTOS_POLL_BUSY:-1}   # seconds, while the runtime has tasks or a restart is pending
POLL_IDLE=${AGENTOS_POLL_IDLE:-5}   # seconds, otherwise
BACKOFF_MAX=60                      # 1, 2, 4, ... 60 s
LOOP_WINDOW=600                     # crash loop: LOOP_MAX deaths within LOOP_WINDOW seconds
LOOP_MAX=5
RESET_AFTER=60                      # backoff resets after the runtime stayed up this long
START_WAIT=15                       # seconds to wait for the process after a start
PROMOTE_INTERVAL=30                 # min seconds between two foreground promotions

ACTION_START=org.agentos.action.SUPERVISOR_START
ACTION_STATUS=org.agentos.action.SUPERVISOR_STATUS
X=org.agentos.extra

ST=init; REASON=none; SINCE_MS=0; SEQ=0
PID=; ALIVE_SINCE=0; NEED=0; ATTEMPT=0; NEXT_START=; DEATHS=; DEATH_COUNT=0; PROMOTED_AT=0
APP_UID=; BOOT_COUNT=-1; MOD_VER=none; MOD_VC=0; NOW=0; LOG_LINES=0; LAST_DEAD=

# ---------------------------------------------------------------- helpers

is_uint() { case $1 in '' | *[!0-9]*) return 1 ;; esac; [ ${#1} -le 18 ]; }

uptime_s() { read -r _u _rest <"$PROCFS/uptime"; NOW=${_u%%.*}; }

log() {
    uptime_s
    echo "$(date '+%m-%d %H:%M:%S') up=$NOW $*" >>"$LOG"
    LOG_LINES=$((LOG_LINES + 1))
    if [ $LOG_LINES -ge 500 ]; then
        LOG_LINES=0
        [ "$(wc -c <"$LOG")" -gt 262144 ] && mv -f "$LOG" "$LOG.1"
    fi
}

set_desc() {
    [ -n "$MODDIR" ] && [ -f "$MODDIR/module.prop" ] || return 0
    while IFS= read -r _l || [ -n "$_l" ]; do
        case $_l in
        description=*) echo "description=$1" ;;
        *) echo "$_l" ;;
        esac
    done <"$MODDIR/module.prop" >"$MODDIR/module.prop.tmp" && mv -f "$MODDIR/module.prop.tmp" "$MODDIR/module.prop"
}

update_desc() {
    case $ST in
    ok) if [ -n "$PID" ]; then _r="运行时 pid ${PID}"; else _r="运行时未运行（有调用时由系统拉起）"; fi
        set_desc "[正常] 监督进程运行中；${_r}" ;;
    backoff) set_desc "[重启中] 运行时有任务时异常退出，10 分钟内第 ${DEATH_COUNT} 次；按退避重新拉起" ;;
    safe_mode) set_desc "[安全模式] 原因：${REASON}。运行时不会被自动拉起" ;;
    stopped) set_desc "[已停止] ${REASON}" ;;
    *) set_desc "[启动中] ${ST}" ;;
    esac
}

set_state() { # state reason
    [ "$ST" = "$1" ] && [ "$REASON" = "$2" ] && return 1
    ST=$1
    REASON=$2
    SINCE_MS="$(date +%s)000"
    log "state -> $ST ($REASON)"
    printf 'state=%s\nreason=%s\nsince=%s\ndeaths=%s\npid=%s\n' "$ST" "$REASON" "$SINCE_MS" "$DEATH_COUNT" "$PID" >"$STATUS_FILE"
    update_desc
    return 0
}

# Contract item e. Explicit broadcast; root bypasses the receiver's permission and exported checks.
# No FLAG_INCLUDE_STOPPED_PACKAGES: after a user force-stop the App must not be woken up by us.
broadcast() {
    SEQ=$((SEQ + 1))
    _out=$(am broadcast --user $USER_ID -n "$RCV" -a "$ACTION_STATUS" \
        --ei "$X.PROTOCOL" 1 --es "$X.STATE" "$ST" --es "$X.REASON" "$REASON" \
        --el "$X.SEQ" "$SEQ" --el "$X.SINCE" "$SINCE_MS" --ei "$X.DEATHS" "$DEATH_COUNT" \
        --ei "$X.BOOT_COUNT" "$BOOT_COUNT" --es "$X.MODULE_VERSION" "$MOD_VER" \
        --ei "$X.MODULE_VERSION_CODE" "$MOD_VC" --ei "$X.RUNTIME_PID" "${PID:-0}" 2>&1)
    case $_out in
    *"Broadcast completed"*) log "status broadcast seq=$SEQ state=$ST reason=$REASON: delivered" ;;
    *) log "status broadcast seq=$SEQ state=$ST failed: $_out" ;;
    esac
}

# Contract item a. Root may start a non-exported service and is not subject to the background
# FGS start restriction; the service must call startForeground() right away (system limit 10 s).
start_runtime() { # reason attempt
    _out=$(am start-foreground-service --user $USER_ID -n "$SVC" -a "$ACTION_START" \
        --es "$X.REASON" "$1" --ei "$X.ATTEMPT" "${2:-0}" 2>&1)
    case $_out in
    *Error* | *Exception* | *rror:*)
        log "start failed reason=$1: $_out"
        return 1
        ;;
    esac
    log "start issued reason=$1 attempt=${2:-0}"
    return 0
}

app_uid() {
    APP_UID=$(stat -c %u "$DATA/user_de/$USER_ID/$PKG" 2>/dev/null)
    is_uint "$APP_UID" || APP_UID=
    [ -n "$APP_UID" ]
}

proc_uid() { # pid -> PROC_UID (real uid from /proc/<pid>/status; the /proc dir itself may be root-owned)
    PROC_UID=
    [ -r "$PROCFS/$1/status" ] || return 1
    while read -r _k _a _rest; do
        if [ "$_k" = "Uid:" ]; then PROC_UID=$_a; break; fi
    done 2>/dev/null <"$PROCFS/$1/status"
    [ -n "$PROC_UID" ]
}

# Contract item c. Liveness = a process named <pkg>:agent that belongs to the App's uid.
find_pid() {
    FOUND=
    for _p in $(pidof "$PROC" 2>/dev/null); do
        proc_uid "$_p" || continue
        [ "$PROC_UID" = "$APP_UID" ] || { log "ignoring pid $_p named $PROC with uid $PROC_UID"; continue; }
        FOUND=$_p
        return 0
    done
    return 1
}

# Contract item b. Only regular files; integers and a fixed enum; at most 16 lines.
read_hb() {
    HB_OK=0; HB_PID=; HB_TASKS=; HB_FG=; HB_STATE=; HB_AT=; HB_BOOT=
    [ -f "$HB" ] && [ ! -L "$HB" ] || return 1
    _n=0
    while IFS='=' read -r _k _v; do
        _n=$((_n + 1))
        [ $_n -gt 16 ] && break
        case $_k in
        pid) is_uint "$_v" && HB_PID=$_v ;;
        tasks) is_uint "$_v" && HB_TASKS=$_v ;;
        at) is_uint "$_v" && HB_AT=$_v ;;
        boot) is_uint "$_v" && HB_BOOT=$_v ;;
        fg) case $_v in 0 | 1) HB_FG=$_v ;; esac ;;
        state) case $_v in starting | recovering | idle | busy | stopping | crashed) HB_STATE=$_v ;; esac ;;
        esac
    done 2>/dev/null <"$HB"
    [ -n "$HB_PID" ] && [ -n "$HB_TASKS" ] && [ -n "$HB_STATE" ] && HB_OK=1
    [ $HB_OK = 1 ]
}

# Only settled states carry a meaningful task count; starting / recovering keep the previous NEED.
need_from_hb() {
    case $HB_STATE in
    idle | busy | stopping) if [ "$HB_TASKS" -gt 0 ]; then NEED=1; else NEED=0; fi ;;
    crashed) [ "$HB_TASKS" -gt 0 ] && NEED=1 ;;
    esac
}

pkg_stopped() {
    _u=$(dumpsys package "$PKG" 2>/dev/null | grep -m1 -E "^ +User $USER_ID:")
    case $_u in *stopped=true*) return 0 ;; esac
    return 1
}

backoff_delay() { # attempt -> DELAY
    DELAY=1
    _i=0
    while [ $_i -lt "$1" ]; do
        DELAY=$((DELAY * 2))
        _i=$((_i + 1))
        if [ $DELAY -ge $BACKOFF_MAX ]; then DELAY=$BACKOFF_MAX; break; fi
    done
}

record_death() {
    _keep=
    for _t in $DEATHS $NOW; do
        [ $((NOW - _t)) -lt $LOOP_WINDOW ] && _keep="$_keep $_t"
    done
    DEATHS=${_keep# }
    set -- $DEATHS
    DEATH_COUNT=$#
}

enter_safe() { # reason
    printf 'reason=%s\nsince=%s\ndeaths=%s\n' "$1" "$(date +%s)" "$DEATHS" >"$SAFE"
    NEXT_START=
    NEED=0
    set_state safe_mode "$1" && broadcast
    log "SAFE MODE entered: $1"
}

safe_reason_from_file() {
    SAFE_REASON=manual
    while IFS='=' read -r _k _v; do
        if [ "$_k" = reason ]; then
            case $_v in '' | *[!a-z0-9_]*) ;; *) SAFE_REASON=$_v ;; esac
        fi
    done 2>/dev/null <"$SAFE"
}

schedule_restart() {
    backoff_delay "$ATTEMPT"
    ATTEMPT=$((ATTEMPT + 1))
    NEXT_START=$((NOW + DELAY))
    set_state backoff runtime_died && broadcast
    log "restart in ${DELAY}s (attempt $ATTEMPT, deaths in window $DEATH_COUNT)"
}

adopt() { # pid
    PID=$1
    ALIVE_SINCE=$NOW
    log "runtime process pid=$PID"
    if [ "$ST" = backoff ] || [ "$ST" = stopped ]; then set_state ok runtime_up; fi
    update_desc
    broadcast
}

wait_for_pid() { # 0: runtime up and adopted; 1: not up (START_WAIT s) or came up and already died
    _w=0
    while [ $_w -lt $START_WAIT ]; do
        sleep 1
        _w=$((_w + 1))
        uptime_s
        if find_pid; then
            [ "$FOUND" = "$PID" ] || adopt "$FOUND"
            log "runtime up ${_w}s after start"
            return 0
        fi
        # Found on the API 35 emulator: a runtime that lived < 1 s (e.g. crashed during recovery)
        # is missed by the 1 s poll. Its heartbeat shows it came up with tasks: fail fast instead of
        # waiting START_WAIT seconds.
        if unobserved_death; then
            LAST_DEAD=$HB_PID
            NEED=1
            log "runtime pid=$HB_PID came up and died within ${_w}s (heartbeat tasks=$HB_TASKS state=$HB_STATE)"
            return 1
        fi
    done
    log "runtime did not come up within ${START_WAIT}s"
    # The caller counts this as one death; a process that lived < 1 s may have left a heartbeat,
    # which must not be counted a second time by unobserved_death.
    read_hb && LAST_DEAD=$HB_PID
    return 1
}

# A start did not leave a live runtime: count it like a death while there is work to do.
start_failed() {
    uptime_s
    if [ "$NEED" != 1 ]; then
        log "no runtime after start and no task known: not retrying (a bind will start it)"
        return 0
    fi
    record_death
    if [ "$DEATH_COUNT" -ge $LOOP_MAX ]; then
        enter_safe crash_loop
        return 0
    fi
    schedule_restart
}

agent_alive() { # pid -> 0 when it is still our runtime process
    [ -d "$PROCFS/$1" ] && proc_uid "$1" && [ "$PROC_UID" = "$APP_UID" ]
}

# Called when no runtime process is tracked. True when the heartbeat belongs to this boot, names a
# pid we have not handled, that pid is gone, and it had tasks. Sets HB_* for on_death.
unobserved_death() {
    read_hb || return 1
    [ "$BOOT_COUNT" != -1 ] && [ "$HB_BOOT" = "$BOOT_COUNT" ] || return 1
    [ "$HB_PID" != "$LAST_DEAD" ] || return 1
    agent_alive "$HB_PID" && return 1
    case $HB_STATE in
    busy | idle | stopping | crashed) [ "$HB_TASKS" -gt 0 ] && return 0 ;;
    esac
    LAST_DEAD=$HB_PID # died idle or before settling: nothing to restart, do not look at it again
    return 1
}

on_death() {
    _dead=$PID
    PID=
    LAST_DEAD=$_dead
    if read_hb && [ "$HB_PID" = "$_dead" ]; then need_from_hb; fi
    log "runtime pid=$_dead gone (heartbeat pid=$HB_PID tasks=$HB_TASKS fg=$HB_FG state=$HB_STATE) need=$NEED"
    [ "$NEED" = 1 ] || { update_desc; return 0; }
    [ "$ST" = safe_mode ] && return 0
    if pkg_stopped; then
        log "package is stopped (user force-stop): not restarting"
        NEED=0
        set_state stopped user_stopped
        return 0
    fi
    record_death
    if [ "$DEATH_COUNT" -ge $LOOP_MAX ]; then
        enter_safe crash_loop
        return 0
    fi
    schedule_restart
}

do_restart() {
    NEXT_START=
    # A root start clears the package's stopped state (seen in B0), so re-check it right before
    # starting: the user may have force-stopped the App during the backoff.
    if pkg_stopped; then
        log "package was force-stopped during backoff: not restarting"
        NEED=0
        set_state stopped user_stopped
        return 0
    fi
    if start_runtime restart "$ATTEMPT"; then
        if [ -n "$PID" ] && [ -d "$PROCFS/$PID" ]; then
            log "start delivered to running pid=$PID"
            return 0
        fi
        wait_for_pid && return 0
    fi
    start_failed
}

observe_alive() {
    if read_hb && [ "$HB_PID" = "$PID" ]; then
        need_from_hb
        if [ "$NEED" = 1 ] && [ "$HB_FG" = 0 ] && [ "$ST" != safe_mode ] &&
            [ $((NOW - PROMOTED_AT)) -ge $PROMOTE_INTERVAL ]; then
            PROMOTED_AT=$NOW
            log "runtime has $HB_TASKS task(s) but is not foreground: promoting"
            start_runtime promote 0
        fi
    fi
    if [ "$ATTEMPT" -gt 0 ] && [ $((NOW - ALIVE_SINCE)) -ge $RESET_AFTER ]; then
        ATTEMPT=0
        log "backoff reset (runtime up ${RESET_AFTER}s)"
    fi
}

module_gone() {
    [ -n "$MODDIR" ] && { [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; }
}

# ---------------------------------------------------------------- boot

wait_boot() {
    until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
    log "boot completed"
}

wait_unlock() {
    _i=0
    while :; do
        if [ "$(getprop sys.user.$USER_ID.ce_available)" = true ]; then
            log "user $USER_ID unlocked (sys.user.$USER_ID.ce_available)"
            return 0
        fi
        _i=$((_i + 1))
        if [ $((_i % 15)) -eq 0 ]; then
            case $(am get-started-user-state $USER_ID 2>/dev/null) in
            *RUNNING_UNLOCKED*)
                log "user $USER_ID unlocked (am get-started-user-state)"
                return 0
                ;;
            esac
        fi
        sleep 2
    done
}

load_module_version() {
    [ -n "$MODDIR" ] && [ -f "$MODDIR/module.prop" ] || return 0
    while IFS='=' read -r _k _v; do
        case $_k in
        version) case $_v in *[!A-Za-z0-9._-]*) ;; *) MOD_VER=$_v ;; esac ;;
        versionCode) is_uint "$_v" && MOD_VC=$_v ;;
        esac
    done <"$MODDIR/module.prop"
}

single_instance() {
    if [ -f "$STATE_DIR/supervisor.pid" ]; then
        read -r _old <"$STATE_DIR/supervisor.pid"
        if is_uint "$_old" && [ "$_old" != $$ ] && [ -d "$PROCFS/$_old" ] &&
            grep -q supervisor "$PROCFS/$_old/cmdline" 2>/dev/null; then
            echo "supervisor already running as pid $_old" >&2
            exit 1
        fi
    fi
    echo $$ >"$STATE_DIR/supervisor.pid"
}

on_term() {
    log "supervisor terminated by signal"
    # Keep a safe-mode description visible across shutdown; otherwise report that we stopped.
    [ "$ST" = safe_mode ] || set_state stopped supervisor_exited
    exit 0
}

main() {
    mkdir -p "$STATE_DIR"
    chmod 700 "$STATE_DIR"
    single_instance
    trap on_term TERM INT
    load_module_version
    log "supervisor start pid=$$ pkg=$PKG module=$MOD_VER($MOD_VC) shell=$(readlink "$PROCFS/$$/exe" 2>/dev/null)"
    update_desc
    wait_boot
    wait_unlock
    _bc=$(settings get global boot_count 2>/dev/null)
    is_uint "$_bc" && BOOT_COUNT=$_bc
    until app_uid; do
        set_state stopped not_installed
        sleep 30
    done
    if read_hb; then
        need_from_hb
        [ "$HB_STATE" = busy ] && NEED=1
        log "last heartbeat: pid=$HB_PID tasks=$HB_TASKS fg=$HB_FG state=$HB_STATE -> need=$NEED"
    fi
    uptime_s
    if [ -f "$SAFE" ]; then
        safe_reason_from_file
        set_state safe_mode "$SAFE_REASON"
        broadcast
    else
        if pkg_stopped; then
            # Force-stopped by the user, or installed but never opened. A root start would clear the
            # stopped state and resume what the user stopped (seen on the API 37 emulator after K5).
            NEED=0
            set_state stopped user_stopped
            log "package is in the stopped state at boot: not starting the runtime"
        else
            set_state ok boot
            # F2: start the runtime once after boot so it can run recovery (F8).
            if start_runtime boot 0 && wait_for_pid; then :; else start_failed; fi
            # adopt() / schedule_restart() / enter_safe() broadcast themselves; otherwise report ok once
            [ -n "$PID" ] || [ "$ST" != ok ] || broadcast
        fi
    fi

    while :; do
        if module_gone; then
            set_state stopped module_disabled && broadcast
            log "module disabled or removed: supervisor exits"
            exit 0
        fi
        uptime_s
        if [ -f "$SAFE" ] && [ "$ST" != safe_mode ]; then
            safe_reason_from_file
            NEXT_START=
            set_state safe_mode "$SAFE_REASON" && broadcast
        elif [ ! -f "$SAFE" ] && [ "$ST" = safe_mode ]; then
            DEATHS=; DEATH_COUNT=0; ATTEMPT=0
            set_state ok safe_mode_exited && broadcast
            start_runtime safe_mode_exit 0
        fi

        if [ -n "$PID" ] && [ -d "$PROCFS/$PID" ]; then
            observe_alive
        else
            [ -n "$PID" ] && on_death
            if find_pid; then
                adopt "$FOUND"
            elif unobserved_death; then
                # A runtime lived shorter than one poll (e.g. started by a bind, took a task and died
                # within 5 s). We never saw it, but its heartbeat says it had tasks: treat it as a death.
                PID=$HB_PID
                log "unobserved runtime pid=$PID left a heartbeat and is gone"
                on_death
            fi
        fi

        if [ -n "$NEXT_START" ] && [ "$NOW" -ge "$NEXT_START" ] && [ "$ST" != safe_mode ]; then
            do_restart
        fi

        if [ "$NEED" = 1 ] || [ -n "$NEXT_START" ]; then sleep $POLL_BUSY; else sleep $POLL_IDLE; fi
    done
}

main "$@"
