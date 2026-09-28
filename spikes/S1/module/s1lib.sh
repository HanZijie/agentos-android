# S1 install helpers, sourced by customize.sh (BOOTMODE install) and service.sh.
# W7 reuses this logic in module/service.sh: compare versionCode, verify SHA-256, `pm install -r`,
# never downgrade, never uninstall, stop with a visible reason on a signature mismatch.
# Needs MODDIR (the module directory holding app/apks.list).

S1_STATE=${S1_STATE:-/data/adb/agentos-s1}
PROCFS=${AGENTOS_PROCFS:-/proc} # overridable only for spikes/S1/test/lib-test.sh
S1_LOG=$S1_STATE/install.log

cs() { read -r _u _r <"$PROCFS/uptime"; echo "${_u%.*}${_u#*.}"; } # uptime in centiseconds

s1_log() {
    mkdir -p "$S1_STATE"
    read -r _u _r <"$PROCFS/uptime"
    echo "$(date '+%m-%d %H:%M:%S') up=${_u} $*" >>"$S1_LOG"
}

set_desc() { # text -> module.prop description (Magisk and KernelSU re-read it for the module list)
    _p="$MODDIR/module.prop"
    [ -f "$_p" ] || return 0
    while IFS= read -r _l || [ -n "$_l" ]; do
        case $_l in description=*) echo "description=$1" ;; *) echo "$_l" ;; esac
    done <"$_p" >"$_p.tmp" && mv -f "$_p.tmp" "$_p"
}

load_conf() { # optional override written by the test driver: S1_METHOD=auto|path|tmp|stdin|session
    S1_METHOD=auto
    [ -f "$S1_STATE/s1.conf" ] || return 0
    while IFS='=' read -r _k _v; do
        [ "$_k" = S1_METHOD ] && case $_v in auto | path | tmp | stdin | session) S1_METHOD=$_v ;; esac
    done <"$S1_STATE/s1.conf"
}

installed_vc() { # pkg -> INST_VC (empty when not installed)
    INST_VC=$(pm list packages --show-versioncode "$1" </dev/null 2>/dev/null | sed -n "s/^package:$1 versionCode:\([0-9]*\).*/\1/p")
}

wait_pm() { # package manager answers, at most ~2 min
    _i=0
    until pm path android >/dev/null 2>&1; do
        _i=$((_i + 1))
        [ $_i -gt 60 ] && return 1
        sleep 2
    done
}

pm_install() { # apk method -> INSTALL_OUT; 0 on Success
    case $2 in
    path) INSTALL_OUT=$(pm install -r "$1" </dev/null 2>&1) ;;
    tmp)
        _t=/data/local/tmp/agentos-install-$$.apk
        cp -f "$1" "$_t" && chmod 644 "$_t"
        INSTALL_OUT=$(pm install -r "$_t" </dev/null 2>&1)
        rm -f "$_t"
        ;;
    stdin) INSTALL_OUT=$(pm install -r -S "$(stat -c %s "$1")" <"$1" 2>&1) ;;
    session)
        _sz=$(stat -c %s "$1")
        _c=$(pm install-create -r -S "$_sz" </dev/null 2>&1)
        _sid=$(echo "$_c" | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
        if [ -z "$_sid" ]; then
            INSTALL_OUT=$_c
        else
            _w=$(pm install-write -S "$_sz" "$_sid" base.apk - <"$1" 2>&1)
            INSTALL_OUT="$_w / $(pm install-commit "$_sid" </dev/null 2>&1)"
        fi
        ;;
    esac
    case $INSTALL_OUT in *Success*) return 0 ;; esac
    return 1
}

semantic_failure() { # failures another install method cannot fix
    case $1 in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *INSTALL_FAILED_VERSION_DOWNGRADE* | *INSTALL_FAILED_VERIFICATION_FAILURE* | \
        *INSTALL_FAILED_INSUFFICIENT_STORAGE* | *INSTALL_PARSE_FAILED_NO_CERTIFICATES* | *INSTALL_FAILED_ABORTED* | \
        *INSTALL_FAILED_USER_RESTRICTED* | *INSTALL_FAILED_DUPLICATE_PERMISSION*) return 0 ;;
    esac
    return 1
}

install_apk() { # apk -> METHOD_USED, INSTALL_OUT, DUR_MS
    _methods=$S1_METHOD
    [ "$_methods" = auto ] && _methods="path tmp stdin session"
    for _m in $_methods; do
        _t0=$(cs)
        pm_install "$1" "$_m"
        _rc=$?
        DUR_MS=$((($(cs) - _t0) * 10))
        METHOD_USED=$_m
        s1_log "pm install method=$_m rc=$_rc ${DUR_MS}ms: $(echo "$INSTALL_OUT" | tr '\n' ' ' | cut -c1-300)"
        [ $_rc = 0 ] && return 0
        semantic_failure "$INSTALL_OUT" && return 1
    done
    return 1
}

failure_code() { echo "$1" | sed -n 's/.*Failure \[\([A-Z_]*\).*/\1/p' | head -n 1; }

process_all() { # -> RESULT; 1 when something failed or was refused
    # pm is `cmd package`, which hands its stdin to system_server: every pm call below that does
    # not stream an APK gets </dev/null so it cannot swallow the rest of apks.list.
    RESULT=
    while read -r _pkg _vc _file _sha; do
        case $_pkg in '' | '#'*) continue ;; esac
        _apk="$MODDIR/$_file"
        _actual=$(sha256sum "$_apk" 2>/dev/null | cut -d' ' -f1)
        if [ "$_actual" != "$_sha" ]; then
            RESULT="已停止：$_file 的 SHA-256 与清单不符"
            s1_log "$RESULT (got ${_actual:-missing})"
            return 1
        fi
        installed_vc "$_pkg"
        if [ -z "$INST_VC" ]; then
            _act=install
        elif [ "$INST_VC" -lt "$_vc" ]; then
            _act=upgrade
        elif [ "$INST_VC" = "$_vc" ]; then
            _act=same
        else
            _act=newer
        fi
        s1_log "$_pkg installed=${INST_VC:-none} zip=$_vc -> $_act ce_available=$(getprop sys.user.0.ce_available)"
        case $_act in
        same) RESULT="已是 v${_vc}，无需安装" ;;
        newer) RESULT="手机上已是更高的 v${INST_VC}，不降级" ;;
        *)
            if install_apk "$_apk"; then
                installed_vc "$_pkg"
                RESULT="${_act} 成功：v${INST_VC}（方式 ${METHOD_USED}，${DUR_MS} ms）"
            else
                _code=$(failure_code "$INSTALL_OUT")
                case $_code in
                INSTALL_FAILED_UPDATE_INCOMPATIBLE)
                    RESULT="已停止：zip 里的 $_pkg 与手机上已安装的签名不符（${_code}）。未卸载旧版；如需更换，请先手动卸载再重启" ;;
                *) RESULT="安装失败：${_code:-未知错误}，详见 $S1_LOG" ;;
                esac
                s1_log "$RESULT"
                return 1
            fi
            ;;
        esac
    done <"$MODDIR/app/apks.list"
    return 0
}
