#!/system/bin/sh
# AgentOS module: late_start service (Magisk / KernelSU run it in the background after boot starts).
#
# 1. Install or upgrade the AgentOS App from the zip (docs/spikes/S1.md). Does not wait for unlock.
# 2. Wait for user 0 to unlock, check the API level against support-matrix.yaml.
# 3. Root supervisor (contract: docs/spikes/S2.md, v0.1): start the runtime once after boot so it can
#    recover, watch it, restart it with backoff while it has tasks, enter safe mode on a crash loop,
#    report status to the App with an explicit broadcast and in module.prop.
#
# The supervisor only manages the process. From the runtime it reads nothing but integers and a short
# state enum in the heartbeat file; it never evaluates that file. Nothing here runs AgentOS code as
# root, mounts anything, or touches SELinux (architecture principles 2 and 7).
#
# Test-only overrides (never set on a device): AGENTOS_MODDIR, AGENTOS_PROCFS, AGENTOS_DATA,
# AGENTOS_TMPDIR, AGENTOS_INSTALL_METHODS; AGENTOS_PKG / AGENTOS_CLS_PREFIX point the supervision
# phase at the S2 stand-in App (org.agentos.spike.s2) before W6 lands. The install phase always uses
# app/apks.list.

MODDIR=${AGENTOS_MODDIR:-${0%/*}}
. "$MODDIR/common.sh"
. "$MODDIR/apks.sh"

APP_PKG=org.agentos.app # the package this module ships; install results are reported for it
PKG=${AGENTOS_PKG:-$APP_PKG}
CLS=${AGENTOS_CLS_PREFIX:-org.agentos.app}
SVC="$PKG/$CLS.agent.AgentService"
RCV="$PKG/$CLS.agent.supervisor.SupervisorStatusReceiver"
PROC="$PKG:agent"
USER_ID=0
PROCFS=${AGENTOS_PROCFS:-/proc}
DATA=${AGENTOS_DATA:-/data}
STATE_DIR=$DATA/adb/agentos
MATRIX=$MODDIR/support-matrix.yaml
HB="$DATA/user_de/$USER_ID/$PKG/files/supervisor/heartbeat"
SAFE="$STATE_DIR/safe_mode"
LOG="$STATE_DIR/supervisor.log"
STATUS_FILE="$STATE_DIR/status"

POLL_BUSY=1          # seconds, while the runtime has tasks or a restart is pending
POLL_IDLE=5          # seconds, otherwise
BACKOFF_MAX=60       # 1, 2, 4, ... 60 s
LOOP_WINDOW=600      # crash loop: LOOP_MAX deaths within LOOP_WINDOW seconds
LOOP_MAX=5
RESET_AFTER=60       # backoff resets after the runtime stayed up this long
START_WAIT=15        # seconds to wait for the process after a start
PROMOTE_INTERVAL=30  # min seconds between two foreground promotions

ACTION_START=org.agentos.action.SUPERVISOR_START
ACTION_STATUS=org.agentos.action.SUPERVISOR_STATUS
X=org.agentos.extra

ST=init; REASON=none; SINCE_MS=0; SEQ=0; DETAIL=
PID=; ALIVE_SINCE=0; NEED=0; ATTEMPT=0; NEXT_START=; DEATHS=; DEATH_COUNT=0; PROMOTED_AT=0
APP_UID=; BOOT_COUNT=-1; MOD_VER=none; MOD_VC=0; NOW=0; LOG_LINES=0; LAST_DEAD=

# ---------------------------------------------------------------- helpers

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

update_desc() {
    case $ST in
    ok) if [ -n "$PID" ]; then _r="运行时 pid ${PID}"; else _r="运行时未运行（有调用时由系统拉起）"; fi
        set_desc "[正常] 监督进程运行中；${_r}${DETAIL:+；${DETAIL}}" ;;
    backoff) set_desc "[重启中] 运行时有任务时异常退出，10 分钟内第 ${DEATH_COUNT} 次；按退避重新拉起" ;;
    safe_mode) set_desc "[安全模式] 原因：${REASON}。运行时不会被自动拉起；在 root 管理器里用本模块的“动作”按钮退出" ;;
    stopped)
        case $REASON in
        not_launched) set_desc "[等待首次打开] 请打开 AgentOS App 完成首次引导，之后由监督进程守护${DETAIL:+；${DETAIL}}" ;;
        user_stopped) set_desc "[已停止] AgentOS 被强行停止，监督进程不会拉起它；打开 App 后恢复守护" ;;
        module_disabled) set_desc "[已停止] 模块已禁用或将被移除" ;;
        supervisor_exited) set_desc "[已停止] 监督进程已退出，下次开机恢复" ;;
        not_installed) set_desc "[已停止] AgentOS App 未安装" ;;
        install_failed) set_desc "[已停止] ${DETAIL}" ;;
        *) set_desc "[已停止] ${REASON}" ;;
        esac
        ;;
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
# No FLAG_INCLUDE_STOPPED_PACKAGES: a stopped App must not be woken up by us.
broadcast() {
    SEQ=$((SEQ + 1))
    _out=$(am broadcast --user $USER_ID -n "$RCV" -a "$ACTION_STATUS" \
        --ei "$X.PROTOCOL" 1 --es "$X.STATE" "$ST" --es "$X.REASON" "$REASON" \
        --el "$X.SEQ" "$SEQ" --el "$X.SINCE" "$SINCE_MS" --ei "$X.DEATHS" "$DEATH_COUNT" \
        --ei "$X.BOOT_COUNT" "$BOOT_COUNT" --es "$X.MODULE_VERSION" "$MOD_VER" \
        --ei "$X.MODULE_VERSION_CODE" "$MOD_VC" --ei "$X.RUNTIME_PID" "${PID:-0}" </dev/null 2>&1)
    case $_out in
    *"Broadcast completed"*) log "status broadcast seq=$SEQ state=$ST reason=$REASON: delivered" ;;
    *) log "status broadcast seq=$SEQ state=$ST failed: $_out" ;;
    esac
}

# Contract item a. Root may start a non-exported service and is exempt from the background FGS start
# restriction (S2 B0: "Allowed … callingUid: 0 … code:SYSTEM_UID"); the service must call
# startForeground() right away.
start_runtime() { # reason attempt
    _out=$(am start-foreground-service --user $USER_ID -n "$SVC" -a "$ACTION_START" \
        --es "$X.REASON" "$1" --ei "$X.ATTEMPT" "${2:-0}" </dev/null 2>&1)
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

agent_alive() { # pid -> 0 when it is still our runtime process
    [ -d "$PROCFS/$1" ] && proc_uid "$1" && [ "$PROC_UID" = "$APP_UID" ]
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

# Only settled states carry a meaningful task count. While recovering, a positive count is trusted
# (W6 counts tasks registered during recovery exactly) but zero is not (tasks may not be loaded yet),
# so the previous NEED is kept; starting never counts (contract b / c, v0.2).
need_from_hb() {
    case $HB_STATE in
    idle | busy | stopping) if [ "$HB_TASKS" -gt 0 ]; then NEED=1; else NEED=0; fi ;;
    crashed | recovering) [ "$HB_TASKS" -gt 0 ] && NEED=1 ;;
    esac
}

# -> PKG_STOPPED (0/1), PKG_NOT_LAUNCHED (0/1) from the "User 0:" line of dumpsys package.
pkg_state() {
    PKG_STOPPED=0; PKG_NOT_LAUNCHED=0
    _u=$(dumpsys package "$PKG" </dev/null 2>/dev/null | grep -m1 -E "^ +User $USER_ID:")
    case $_u in *stopped=true*) PKG_STOPPED=1 ;; esac
    case $_u in *notLaunched=true*) PKG_NOT_LAUNCHED=1 ;; esac
}

pkg_stopped() { pkg_state; [ $PKG_STOPPED = 1 ]; }

stopped_reason() { # after pkg_state
    if [ $PKG_NOT_LAUNCHED = 1 ]; then echo not_launched; else echo user_stopped; fi
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

# Called when no runtime process is tracked. True when the heartbeat belongs to this boot, names a
# pid we have not handled, that pid is gone, and it had tasks (S2 v0.1 #1). Sets HB_* for on_death.
unobserved_death() {
    read_hb || return 1
    [ "$BOOT_COUNT" != -1 ] && [ "$HB_BOOT" = "$BOOT_COUNT" ] || return 1
    [ "$HB_PID" != "$LAST_DEAD" ] || return 1
    agent_alive "$HB_PID" && return 1
    case $HB_STATE in
    busy | idle | stopping | crashed | recovering) [ "$HB_TASKS" -gt 0 ] && return 0 ;;
    esac
    LAST_DEAD=$HB_PID # died idle or before settling: nothing to restart, do not look at it again
    return 1
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
        if unobserved_death; then # S2 v0.1 #2: lived < 1 s, fail fast
            LAST_DEAD=$HB_PID
            NEED=1
            log "runtime pid=$HB_PID came up and died within ${_w}s (heartbeat tasks=$HB_TASKS state=$HB_STATE)"
            return 1
        fi
    done
    log "runtime did not come up within ${START_WAIT}s"
    read_hb && LAST_DEAD=$HB_PID # the caller counts this death; do not count it again
    return 1
}

# A start did not leave a live runtime: count it like a death while there is work to do (v0.1 #3).
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
    # A root start clears the stopped state (S2 B0), so check it right before starting (v0.1 #4).
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

module_gone() { [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; }

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
            case $(am get-started-user-state $USER_ID </dev/null 2>/dev/null) in
            *RUNNING_UNLOCKED*)
                log "user $USER_ID unlocked (am get-started-user-state)"
                return 0
                ;;
            esac
        fi
        module_gone && return 1
        sleep 2
    done
}

load_module_version() {
    prop_get "$MODDIR/module.prop" version && case $PV in *[!A-Za-z0-9._-]* | '') ;; *) MOD_VER=$PV ;; esac
    prop_get "$MODDIR/module.prop" versionCode && is_uint "$PV" && MOD_VC=$PV
}

single_instance() {
    if [ -f "$STATE_DIR/supervisor.pid" ]; then
        read -r _old <"$STATE_DIR/supervisor.pid"
        if is_uint "$_old" && [ "$_old" != $$ ] && [ -d "$PROCFS/$_old" ] &&
            grep -q "service.sh" "$PROCFS/$_old/cmdline" 2>/dev/null; then
            echo "AgentOS supervisor already running as pid $_old" >&2
            exit 1
        fi
    fi
    echo $$ >"$STATE_DIR/supervisor.pid"
}

on_term() {
    log "supervisor terminated by signal"
    # Keep a safe-mode or install-failure description visible; otherwise report that we stopped.
    case $ST:$REASON in safe_mode:* | stopped:install_failed) ;; *) set_state stopped supervisor_exited ;; esac
    exit 0
}

# Phase 1 (S1): install / upgrade. Returns 1 when there is nothing we may supervise.
install_phase() {
    if ! wait_pm; then
        log "install: package manager not ready after 2 min; skipping installation this boot"
        return 0
    fi
    apks_install_all "$APP_PKG"
    log "install: $APP_PKG result=$APP_RESULT installed_vc=${APP_VC:-none}"
    case $APP_RESULT in
    same) ;;
    installed | upgraded) DETAIL="已${APP_DESC}" ;;
    newer) DETAIL=$APP_DESC ;;
    failed)
        if [ -n "$APP_VC" ]; then
            DETAIL="升级失败，仍在使用 v${APP_VC}，下次开机重试"
        else
            DETAIL=$APP_DESC
            set_state stopped install_failed
            return 1
        fi
        ;;
    *)
        # sig_mismatch: the installed App is not ours, never supervise it; corrupt / bad_list: broken zip
        DETAIL=$APP_DESC
        set_state stopped install_failed
        return 1
        ;;
    esac
    return 0
}

api_supported() {
    _api=$(getprop ro.build.version.sdk)
    matrix_get "$MATRIX" api_min; _min=$MV
    matrix_get "$MATRIX" api_max; _max=$MV
    is_uint "$_api" && is_uint "$_min" && is_uint "$_max" || return 0 # cannot tell: do not block
    [ "$_api" -ge "$_min" ] && [ "$_api" -le "$_max" ]
}

main() {
    mkdir -p "$STATE_DIR"
    chmod 700 "$STATE_DIR"
    # Managers do not run a disabled module's service.sh; exit anyway if one does (nothing installed).
    module_gone && exit 0
    single_instance
    trap on_term TERM INT
    load_module_version
    log "supervisor start pid=$$ pkg=$PKG module=$MOD_VER($MOD_VC) shell=$(readlink "$PROCFS/$$/exe" 2>/dev/null)"
    set_desc "[启动中] 等待开机完成"
    wait_boot
    install_phase || { log "nothing to supervise: $DETAIL"; exit 0; }
    UPGRADED=0
    [ "$APP_RESULT" = upgraded ] && [ "$PKG" = "$APP_PKG" ] && UPGRADED=1

    set_desc "[启动中] 等待用户解锁"
    wait_unlock || { set_state stopped module_disabled; exit 0; }
    _bc=$(settings get global boot_count </dev/null 2>/dev/null)
    is_uint "$_bc" && BOOT_COUNT=$_bc
    until app_uid; do
        set_state stopped not_installed
        module_gone && exit 0
        sleep 30
    done
    prop_get "$MODDIR/install.prop" fingerprint_verified
    log "device: api=$(getprop ro.build.version.sdk) fingerprint_verified=${PV:-unknown} matrix=$(matrix_get "$MATRIX" matrix_version; echo "$MV")"
    if read_hb; then
        need_from_hb
        [ "$HB_STATE" = busy ] && NEED=1
        log "last heartbeat: pid=$HB_PID tasks=$HB_TASKS fg=$HB_FG state=$HB_STATE -> need=$NEED"
    fi
    uptime_s
    if [ ! -f "$SAFE" ] && ! api_supported; then
        # F2 / F12: after an OTA the system may be outside the supported range (W12 adds fingerprints)
        enter_safe unsupported_api
    elif [ -f "$SAFE" ]; then
        safe_reason_from_file
        set_state safe_mode "$SAFE_REASON"
        broadcast
    elif pkg_stopped; then
        # Force-stopped by the user, or installed but never opened: a root start would clear the
        # stopped state and resume what the user stopped (S2 v0.1 #4).
        NEED=0
        set_state stopped "$(stopped_reason)"
        log "package is in the stopped state at boot ($REASON): not starting the runtime"
    else
        set_state ok boot
        _why=boot
        [ $UPGRADED = 1 ] && _why=upgrade
        # F2: start the runtime once after boot so it can run recovery (F8).
        if start_runtime $_why 0 && wait_for_pid; then :; else start_failed; fi
        [ -n "$PID" ] || [ "$ST" != ok ] || broadcast
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
            if pkg_stopped; then
                log "safe mode exited; package is stopped: not starting the runtime"
            else
                start_runtime safe_mode_exit 0
            fi
        fi

        if [ -n "$PID" ] && [ -d "$PROCFS/$PID" ]; then
            observe_alive
        else
            [ -n "$PID" ] && on_death
            if find_pid; then
                adopt "$FOUND"
            elif unobserved_death; then
                # Lived shorter than one poll (e.g. started by a bind, took a task and died within 5 s).
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
