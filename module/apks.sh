# AgentOS module: install / upgrade the Apps shipped in the zip (docs/spikes/S1.md, "安装规则").
# Sourced by service.sh after sys.boot_completed. Needs MODDIR, and log() from the caller.
#
# app/apks.list (written by tools/package-module.py), one APK per line, space separated:
#   <package> <versionCode> <path inside the module> <SHA-256 of the APK> <SHA-256 of the signing cert>
# Rules: verify SHA-256; install when missing or older; skip when equal or newer (never downgrade,
# never uninstall); stop with a visible reason on a signature mismatch.
#
# `pm` is `cmd package`, which hands its stdin to system_server: every pm call that does not stream
# an APK gets </dev/null, otherwise it swallows the rest of apks.list (S1, lib-test "two_pkgs").

APKS_TMPDIR=${AGENTOS_TMPDIR:-/data/local/tmp} # shell_data_file: system_server may read it
# Order from S1 M7 (API 35 / 37 emulators, adb root): system_server cannot read /data/adb
# (adb_data_file) nor an fd redirected from it, so "path" and "stdin" fail; a copy in
# /data/local/tmp and a pipe both work. The others stay as fallbacks until real devices are measured.
APKS_METHODS=${AGENTOS_INSTALL_METHODS:-tmp pipe path stdin session}

cs() { read -r _u _r <"${PROCFS:-/proc}/uptime"; echo "${_u%.*}${_u#*.}"; } # uptime, centiseconds

installed_vc() { # pkg -> INST_VC (empty when not installed)
    INST_VC=$(pm list packages --show-versioncode "$1" </dev/null 2>/dev/null |
        sed -n "s/^package:$1 versionCode:\([0-9]*\).*/\1/p")
}

wait_pm() { # package manager answers, at most ~2 min
    _i=0
    until pm path android </dev/null >/dev/null 2>&1; do
        _i=$((_i + 1))
        [ $_i -gt 60 ] && return 1
        sleep 2
    done
}

pm_install() { # apk method -> INSTALL_OUT; 0 on Success
    case $2 in
    tmp)
        _t=$APKS_TMPDIR/agentos-install-$$.apk
        if cp -f "$1" "$_t" && chmod 644 "$_t"; then
            INSTALL_OUT=$(pm install -r "$_t" </dev/null 2>&1)
        else
            INSTALL_OUT="copy to $APKS_TMPDIR failed"
        fi
        rm -f "$_t"
        ;;
    pipe) INSTALL_OUT=$(cat "$1" | pm install -r -S "$(stat -c %s "$1")" 2>&1) ;;
    path) INSTALL_OUT=$(pm install -r "$1" </dev/null 2>&1) ;;
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
    *) INSTALL_OUT="unknown install method $2" ;;
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
    for _m in $APKS_METHODS; do
        _t0=$(cs)
        pm_install "$1" "$_m"
        _rc=$?
        DUR_MS=$((($(cs) - _t0) * 10))
        METHOD_USED=$_m
        log "install: pm method=$_m rc=$_rc ${DUR_MS}ms: $(echo "$INSTALL_OUT" | tr '\n' ' ' | cut -c1-240)"
        [ $_rc = 0 ] && return 0
        semantic_failure "$INSTALL_OUT" && return 1
    done
    return 1
}

failure_code() { echo "$1" | sed -n 's/.*Failure \[\([A-Z_]*\).*/\1/p' | head -n 1; }

# Installs every line of app/apks.list. Per package sets APK_RESULT_<n> (n = line index) and, for the
# package named by $1, APP_RESULT / APP_DESC / APP_VC:
#   same | newer | installed | upgraded | sig_mismatch | corrupt | failed | bad_list
# Returns 1 when any package failed.
apks_install_all() { # main-package
    APP_RESULT=; APP_DESC=; APP_VC=
    _list="$MODDIR/app/apks.list"
    [ -f "$_list" ] || { APP_RESULT=bad_list; APP_DESC="zip 里缺少 app/apks.list"; return 1; }
    _any_fail=0
    while read -r _pkg _vc _file _sha _cert _rest; do
        case $_pkg in '' | '#'*) continue ;; esac
        _res=; _desc=
        case $_file in
        app/*.apk) case $_file in *..* | *' '*) _res=bad_list ;; esac ;;
        *) _res=bad_list ;;
        esac
        is_uint "$_vc" || _res=bad_list
        if [ -n "$_res" ]; then
            _desc="app/apks.list 格式错误：$_pkg"
        else
            _apk="$MODDIR/$_file"
            _actual=$(sha256sum "$_apk" 2>/dev/null | cut -d' ' -f1)
            if [ "$_actual" != "$_sha" ]; then
                _res=corrupt
                _desc="已停止：${_file} 的 SHA-256 与清单不符，zip 可能损坏，请重新下载"
            else
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
                log "install: $_pkg installed=${INST_VC:-none} zip=$_vc cert=${_cert:--} -> $_act"
                case $_act in
                same) _res=same ;;
                newer)
                    _res=newer
                    _desc="手机上已是更高的 v${INST_VC}，不降级"
                    ;;
                *)
                    if install_apk "$_apk"; then
                        installed_vc "$_pkg"
                        if [ "$_act" = install ]; then
                            _res=installed
                            _desc="安装 v${INST_VC}（${METHOD_USED}，${DUR_MS} ms）"
                        else
                            _res=upgraded
                            _desc="升级到 v${INST_VC}（${METHOD_USED}，${DUR_MS} ms）"
                        fi
                    else
                        _code=$(failure_code "$INSTALL_OUT")
                        case $_code in
                        INSTALL_FAILED_UPDATE_INCOMPATIBLE)
                            _res=sig_mismatch
                            _desc="已停止：手机上的 ${_pkg} 与 zip 里的签名不符（${_code}）。未卸载旧版；如需更换，请先手动卸载再重启"
                            ;;
                        *)
                            _res=failed
                            _desc="安装失败：${_pkg} ${_code:-未知错误}，下次开机重试；详见 /data/adb/agentos/supervisor.log"
                            ;;
                        esac
                    fi
                    ;;
                esac
            fi
        fi
        log "install: $_pkg -> $_res${_desc:+ ($_desc)}"
        case $_res in same | newer | installed | upgraded) ;; *) _any_fail=1 ;; esac
        if [ "$_pkg" = "$1" ]; then
            APP_RESULT=$_res
            APP_DESC=$_desc
            installed_vc "$_pkg"
            APP_VC=$INST_VC
        fi
    done <"$_list"
    [ -n "$APP_RESULT" ] || { APP_RESULT=bad_list; APP_DESC="app/apks.list 里没有 $1"; _any_fail=1; }
    return $_any_fail
}
