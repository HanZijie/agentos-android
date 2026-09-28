#!/bin/sh
# Host-side unit tests for module/apks.sh (install decisions, from spikes/S1/test/lib-test.sh) and
# module/common.sh (support-matrix.yaml parsing). Android behaviour is covered by tools/smoke-test.sh.
#   sh module/test/unit.sh          SIM_SHELL=dash|sh (default dash)
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
MODSRC=$(cd "$HERE/.." && pwd)
SH=${SIM_SHELL:-dash}
PASS=0
FAIL=0
PKG=org.agentos.app

check() { # description, command...
    _d=$1
    shift
    if "$@"; then
        PASS=$((PASS + 1)); echo "  ok   $NAME: $_d"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL $NAME: $_d"; echo "$OUTPUT" | sed 's/^/    /'
        [ -f "$T/pm.calls" ] && sed 's/^/    pm: /' "$T/pm.calls"
    fi
}
has() { case $OUTPUT in *"$1"*) return 0 ;; esac; return 1; }

# ------------------------------------------------------------------ apks.sh
setup() { # name
    NAME=$1
    T=$(mktemp -d "${TMPDIR:-/tmp}/agentos-module-unit.XXXXXX")
    mkdir -p "$T/bin" "$T/proc" "$T/mod/app" "$T/tmp"
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
    case $src in *"/mod/"*) if [ "${PATH_DENIED:-0}" = 1 ]; then echo "Error: Unable to open file: $src"; exit 1; fi ;; esac
    case $src in *"/tmp/agentos-install-"*) if [ "${TMP_DENIED:-0}" = 1 ]; then echo "Error: Unable to open file: $src"; exit 1; fi ;; esac
    [ $stdin = 1 ] && cat >/dev/null
    if [ "${SIG_MISMATCH:-0}" = 1 ]; then
        echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package $NEWPKG signatures do not match newer version; ignoring!]"
        exit 1
    fi
    if [ "${NO_SPACE:-0}" = 1 ]; then echo "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"; exit 1; fi
    grep -v "^$NEWPKG " "$T/installed" >"$T/installed.new" || true
    echo "$NEWPKG $NEWVC" >>"$T/installed.new"
    mv -f "$T/installed.new" "$T/installed"
    echo "Success"
    ;;
esac
EOF
    printf '#!/bin/sh\nshasum -a 256 "$@"\n' >"$T/bin/sha256sum"
    printf '#!/bin/sh\n# stat -c %%s FILE\nwc -c <"$3" | tr -d " "\n' >"$T/bin/stat"
    # never delete anything, not even the install copy: move it aside
    mkdir -p "$T/graveyard"
    printf '#!/bin/sh\nfor a in "$@"; do case $a in -*) ;; *) [ -e "$a" ] && mv "$a" "$T/graveyard/" ;; esac; done\nexit 0\n' >"$T/bin/rm"
    chmod +x "$T/bin/"*
    : >"$T/installed"
    : >"$T/knobs"
    export T
}

apk() { # versionCode [corrupt] [pkg]
    _p=${3:-$PKG}
    printf 'fake apk %s v%s\n' "$_p" "$1" >"$T/mod/app/$_p-v$1.apk"
    sha=$(shasum -a 256 "$T/mod/app/$_p-v$1.apk" | cut -d' ' -f1)
    [ "${2:-}" = corrupt ] && sha=0000000000000000000000000000000000000000000000000000000000000000
    echo "$_p $1 app/$_p-v$1.apk $sha abcd" >>"$T/mod/app/apks.list"
    printf 'NEWPKG=%s\nNEWVC=%s\n' "$_p" "$1" >>"$T/knobs"
}

run() { # -> RC, OUTPUT
    set +e
    OUTPUT=$(PATH="$T/bin:$PATH" MODDIR="$T/mod" AGENTOS_TMPDIR="$T/tmp" PROCFS="$T/proc" "$SH" -c '
        . "$0/common.sh"; . "$0/apks.sh"
        log() { echo "LOG $*"; }
        apks_install_all "$1"; rc=$?
        echo "RESULT=$APP_RESULT VC=$APP_VC DESC=$APP_DESC"; exit $rc' "$MODSRC" "${MAINPKG:-$PKG}" 2>&1)
    RC=$?
    set -e
}
installs() { grep -c "^pm install" "$T/pm.calls" || true; }

setup fresh; apk 1; run
check "fresh install via tmp copy" has "RESULT=installed VC=1 DESC=安装 v1（tmp"
check "rc 0" [ $RC = 0 ]
check "tmp copy cleaned up" [ -z "$(ls "$T/tmp")" ]

setup same; apk 1; echo "$PKG 1" >"$T/installed"; run
check "same version skipped" has "RESULT=same"
check "no pm install" [ "$(installs)" = 0 ]

setup upgrade; apk 2; echo "$PKG 1" >"$T/installed"; run
check "upgrade" has "RESULT=upgraded VC=2 DESC=升级到 v2"

setup newer; apk 1; echo "$PKG 3" >"$T/installed"; run
check "never downgrades" has "RESULT=newer VC=3"
check "no pm install" [ "$(installs)" = 0 ]

setup badsig; apk 2; echo "$PKG 1" >"$T/installed"; echo SIG_MISMATCH=1 >>"$T/knobs"; run
check "signature mismatch" has "RESULT=sig_mismatch VC=1 DESC=已停止：手机上的 org.agentos.app 与 zip 里的签名不符"
check "rc 1" [ $RC = 1 ]
check "no retry with other methods" [ "$(installs)" = 1 ]

setup nospace; apk 2; echo "$PKG 1" >"$T/installed"; echo NO_SPACE=1 >>"$T/knobs"; run
check "other failure keeps the installed version" has "RESULT=failed VC=1"

setup tmp_denied; apk 1; echo TMP_DENIED=1 >>"$T/knobs"; run
check "falls back to a pipe" has "（pipe，"

setup corrupt; apk 1 corrupt; run
check "SHA-256 mismatch" has "RESULT=corrupt"
check "no pm install" [ "$(installs)" = 0 ]

setup bad_path; printf 'org.agentos.app 1 ../../etc/x.apk 00 -\n' >"$T/mod/app/apks.list"; run
check "path outside app/ refused" has "RESULT=bad_list"

setup missing_main; apk 1 "" org.agentos.runner; run
check "main package missing from the list" has "RESULT=bad_list"

setup two_pkgs; apk 1; apk 1 "" org.agentos.runner; echo SWALLOW_STDIN=1 >>"$T/knobs"
printf 'NEWPKG=%s\nNEWVC=%s\n' "$PKG" 1 >>"$T/knobs"; run
check "a stdin-reading pm does not swallow the rest of apks.list" [ "$(grep -c '^pm list' "$T/pm.calls")" -ge 4 ]

# ------------------------------------------------------------------ common.sh: support-matrix.yaml
NAME=matrix
T=$(mktemp -d "${TMPDIR:-/tmp}/agentos-module-unit.XXXXXX")
cat >"$T/m.yaml" <<'EOF'
# comment
schema: 1
api_min: 35   # trailing comment
name: "quoted value"
verified_fingerprints:
  - google/a/b:15/X/1:user/release-keys  # Pixel
  - 'google/c/d:16/Y/2:user/release-keys'
conflicting_modules:
other: x
EOF
OUTPUT=$("$SH" -c '. "$0/common.sh"
    matrix_get "$1" api_min; echo "api_min=[$MV]"
    matrix_get "$1" name; echo "name=[$MV]"
    matrix_get "$1" missing; echo "missing=[$MV] rc=$?"
    echo "fps=[$(matrix_list "$1" verified_fingerprints | tr "\n" ",")]"
    echo "conf=[$(matrix_list "$1" conflicting_modules | tr "\n" ",")]"
    matrix_has "$1" verified_fingerprints "google/c/d:16/Y/2:user/release-keys" && echo has_c
    matrix_has "$1" verified_fingerprints "google/zz" || echo no_zz
    matrix_get "$0/support-matrix.yaml" api_max; echo "real_api_max=[$MV]"
    ' "$MODSRC" "$T/m.yaml" 2>&1)
check "scalar with trailing comment" has "api_min=[35]"
check "quoted scalar" has "name=[quoted value]"
check "missing key is empty" has "missing=[]"
check "list items, comments and quotes stripped" has "fps=[google/a/b:15/X/1:user/release-keys,google/c/d:16/Y/2:user/release-keys,]"
check "empty list" has "conf=[]"
check "matrix_has finds an item" has "has_c"
check "matrix_has rejects others" has "no_zz"
check "real support-matrix.yaml parses" has "real_api_max=[37]"

echo "module unit tests with $SH: passed $PASS, failed $FAIL"
[ $FAIL = 0 ]
