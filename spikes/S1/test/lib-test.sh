#!/bin/sh
# Host-side test of spikes/S1/module/s1lib.sh decision logic with a fake `pm`.
# Android behaviour (SELinux, Play Protect, real signatures) is covered on devices by s1.sh.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
LIB="$HERE/../module/s1lib.sh"
SH=${SIM_SHELL:-dash}
PASS=0
FAIL=0
PKG=org.agentos.spike.s1

setup() { # name
    NAME=$1
    T=$(mktemp -d "${TMPDIR:-/tmp}/agentos-s1-test.XXXXXX")
    mkdir -p "$T/bin" "$T/proc" "$T/mod/app" "$T/state" "$T/tmp"
    echo "100.00 0" >"$T/proc/uptime"
    : >"$T/pm.calls"
    cat >"$T/bin/pm" <<'EOF'
#!/bin/sh
# Fake pm. State: $T/installed = "<pkg> <versionCode>" lines. Knobs: $T/knobs (sourced).
. "$T/knobs"
echo "pm $*" >>"$T/pm.calls"
[ "${SWALLOW_STDIN:-0}" = 1 ] && cat >/dev/null
case $1 in
list)
    pkg=$4
    while read -r p v; do [ "$p" = "$pkg" ] && echo "package:$p versionCode:$v"; done <"$T/installed"
    ;;
path) echo "package:/system/framework/framework-res.apk" ;;
install)
    shift
    src=; stdin=0
    while [ $# -gt 0 ]; do case $1 in -r) ;; -S) stdin=1; shift ;; *) src=$1 ;; esac; shift; done
    case $src in *"/mod/"*) if [ "${PATH_DENIED:-0}" = 1 ]; then echo "Error: Unable to open file: $src"; echo "Consider using a file under /data/local/tmp/"; exit 1; fi ;; esac
    case $src in *"/tmp/agentos-install-"*) if [ "${TMP_DENIED:-0}" = 1 ]; then echo "Error: Unable to open file: $src"; exit 1; fi ;; esac
    [ $stdin = 1 ] && cat >/dev/null
    if [ "${SIG_MISMATCH:-0}" = 1 ]; then
        echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package $NEWPKG signatures do not match newer version; ignoring!]"
        exit 1
    fi
    grep -v "^$NEWPKG " "$T/installed" >"$T/installed.new" || true
    echo "$NEWPKG $NEWVC" >>"$T/installed.new"
    mv -f "$T/installed.new" "$T/installed"
    echo "Success"
    ;;
esac
EOF
    printf '#!/bin/sh\ncase $1 in sys.user.0.ce_available) echo true ;; esac\n' >"$T/bin/getprop"
    printf '#!/bin/sh\nshasum -a 256 "$@"\n' >"$T/bin/sha256sum"
    printf '#!/bin/sh\n# stat -c %%s FILE\nwc -c <"$3" | tr -d " "\n' >"$T/bin/stat"
    chmod +x "$T/bin/"*
    : >"$T/installed"
    : >"$T/knobs"
    export T
}

apk() { # versionCode [corrupt]
    printf 'fake apk v%s\n' "$1" >"$T/mod/app/S1-v$1.apk"
    sha=$(shasum -a 256 "$T/mod/app/S1-v$1.apk" | cut -d' ' -f1)
    [ "${2:-}" = corrupt ] && sha=0000000000000000000000000000000000000000000000000000000000000000
    echo "$PKG $1 app/S1-v$1.apk $sha" >"$T/mod/app/apks.list"
    printf 'NEWPKG=%s\nNEWVC=%s\n' "$PKG" "$1" >>"$T/knobs"
}

run() { # -> RC, OUTPUT (RESULT line)
    set +e
    OUTPUT=$(PATH="$T/bin:$PATH" MODDIR="$T/mod" S1_STATE="$T/state" S1_TMPDIR="$T/tmp" AGENTOS_PROCFS="$T/proc" "$SH" -c \
        '. "$0"; S1_METHOD=${METHOD:-auto}; process_all; rc=$?; echo "RESULT=$RESULT"; exit $rc' "$LIB" 2>&1)
    RC=$?
    set -e
}

check() { # description, command...
    _d=$1
    shift
    if "$@"; then
        PASS=$((PASS + 1)); echo "  ok   $NAME: $_d"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL $NAME: $_d"; echo "$OUTPUT" | sed 's/^/    /'; sed 's/^/    pm: /' "$T/pm.calls"
    fi
}
has() { case $OUTPUT in *"$1"*) return 0 ;; esac; return 1; }
installs() { grep -c "^pm install" "$T/pm.calls" || true; }

setup fresh; apk 1; run
check "fresh install succeeds via a copy in the tmp dir (first in the default order)" has "install 成功：v1（方式 tmp"
check "rc 0" [ $RC = 0 ]
check "tmp copy cleaned up" [ -z "$(ls "$T/tmp")" ]

setup same; apk 1; echo "$PKG 1" >"$T/installed"; run
check "same version is skipped" has "已是 v1"
check "no pm install" [ "$(installs)" = 0 ]

setup upgrade; apk 2; echo "$PKG 1" >"$T/installed"; run
check "upgrade succeeds" has "upgrade 成功：v2"

setup newer; apk 1; echo "$PKG 3" >"$T/installed"; run
check "never downgrades" has "不降级"
check "no pm install" [ "$(installs)" = 0 ]

setup badsig; apk 2; echo "$PKG 1" >"$T/installed"; echo SIG_MISMATCH=1 >>"$T/knobs"; run
check "signature mismatch stops with a reason" has "签名不符"
check "rc 1" [ $RC = 1 ]
check "no retry with other methods" [ "$(installs)" = 1 ]
check "installed App left alone" grep -q "^$PKG 1$" "$T/installed"

setup tmp_denied; apk 1; echo TMP_DENIED=1 >>"$T/knobs"; run
check "falls back to a pipe when the tmp copy is refused" has "install 成功：v1（方式 pipe"

setup path_method; apk 1; METHOD=path run
check "path method works when readable" has "方式 path"

setup stdin_method; apk 1; METHOD=stdin run
check "stdin method works" has "方式 stdin"

setup corrupt; apk 1 corrupt; run
check "SHA-256 mismatch stops before pm" has "SHA-256"
check "no pm install" [ "$(installs)" = 0 ]

setup two_pkgs; apk 1
echo "org.agentos.spike.other 1 app/S1-v1.apk $(shasum -a 256 "$T/mod/app/S1-v1.apk" | cut -d' ' -f1)" >>"$T/mod/app/apks.list"
echo SWALLOW_STDIN=1 >>"$T/knobs"; run
check "a stdin-reading pm does not swallow the rest of apks.list" [ "$(grep -c '^pm list' "$T/pm.calls")" -ge 3 ]

echo "s1lib.sh with $SH: passed $PASS, failed $FAIL"
[ $FAIL = 0 ]
