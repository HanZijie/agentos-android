#!/bin/sh
# AgentOS installer / AgentOS 安装脚本
#
# Flashes the AgentOS Magisk / KernelSU module (agentos-<ver>.zip) onto a rooted phone over adb, waits for the
# AgentOS App to appear after the reboot, and installs the sample apps. Written in POSIX sh: `sh install.sh`,
# `bash install.sh` and `zsh install.sh` all work (macOS, Linux, Windows with WSL or Git Bash).
#
#   sh install.sh [options]
#
#   -s SERIAL          the phone (adb serial). Optional when exactly one phone is connected (else required). Also ANDROID_SERIAL.
#   -d DIR             folder with the release files (default: the folder of this script)
#   --samples LIST     sample apps to install: all | none | a comma list of alarm,calendar,notes,todo,sms
#                      default: alarm,calendar,notes,todo (the messages app is never installed unless you name it)
#   --samples-only     skip the module: only install the sample apps (the module is already flashed)
#   --check-only       run the pre-flight checks and stop
#   --no-reboot        flash the module but do not reboot (you reboot later; the AgentOS App appears after that reboot)
#   -y, --yes          do not ask for confirmation
#   --allow-debug      accept a folder whose build-info.json says "variant": "debug". tools/package-release.py never produces one; this is a
#                      guard against a test build that someone put into the folder by hand
#   --no-verify        do not check SHA256SUMS (not recommended)
#   --skip-checks      do not run check-device.sh (not recommended)
#   -h, --help
#
# AGENTOS_ADB_ROOT=1 (environment): a userdebug emulator after `adb root`, no root manager; only for testing this script, see tools/check-device.sh.
#
# What this script cannot do (unlocking the bootloader, rooting, USB debugging, the Superuser prompt, the model key,
# enabling plugins, SMS permission ...) is listed step by step in docs/install.md.
#
# Exit codes: 0 done; 1 failed; 2 bad usage; 3 flashed but the AgentOS App did not appear in time (look at docs/install.md, "If it does not work").
#
# 中文：通过 adb 把 AgentOS 模块 zip 刷进已 root 的手机（Magisk / KernelSU），重启后等 AgentOS App 出现，再装示例 App。
# 脚本解决不了的部分（解锁 BL、root、USB 调试、授权 root 弹窗、模型 key、启用插件、短信权限等）见 docs/install.md。

# The Chinese messages use full-width quotes on purpose (SC1111).
# shellcheck disable=SC1111
set -u

ADB=${ADB:-adb}
SERIAL=${ANDROID_SERIAL:-}
DIR=$(cd "$(dirname "$0")" && pwd)
SAMPLES=default
SAMPLES_ONLY=0
CHECK_ONLY=0
REBOOT=1
YES=0
ALLOW_DEBUG=0
VERIFY=1
CHECKS=1
PKG=org.agentos.app
DEV_DIR=/data/local/tmp/agentos-install
ALL_SAMPLES="alarm calendar notes todo sms"
DEFAULT_SAMPLES="alarm calendar notes todo"
TMP=
ZIP=
SUMS=
FAILED=0

case "${AGENTOS_LANG:-${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}}" in
zh*) ZH=1 ;;
*) ZH=0 ;;
esac

# say <中文> <English>
say() { if [ "$ZH" = 1 ]; then printf '%s\n' "$1"; else printf '%s\n' "$2"; fi; }
ok() { if [ "$ZH" = 1 ]; then printf 'OK    %s\n' "$1"; else printf 'OK    %s\n' "$2"; fi; }
warn() { if [ "$ZH" = 1 ]; then printf 'WARN  %s\n' "$1"; else printf 'WARN  %s\n' "$2"; fi; }
step() { if [ "$ZH" = 1 ]; then printf '\n### %s\n' "$1"; else printf '\n### %s\n' "$2"; fi; }
die() {
    if [ "$ZH" = 1 ]; then printf 'ERROR %s\n' "$1" >&2; else printf 'ERROR %s\n' "$2" >&2; fi
    exit "${3:-1}"
}

cleanup() { [ -n "$TMP" ] && [ -d "$TMP" ] && rm -rf "$TMP"; }
trap cleanup EXIT
trap 'exit 130' INT TERM

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case $1 in
    -s) [ $# -ge 2 ] || die "-s 需要一个序列号" "-s needs a serial" 2; SERIAL=$2; shift 2 ;;
    -d) [ $# -ge 2 ] || die "-d 需要一个目录" "-d needs a folder" 2; DIR=$(cd "$2" 2>/dev/null && pwd) || die "目录不存在：$2" "no such folder: $2" 2; shift 2 ;;
    --samples) [ $# -ge 2 ] || die "--samples 需要一个列表" "--samples needs a list" 2; SAMPLES=$2; shift 2 ;;
    --samples-only) SAMPLES_ONLY=1; shift ;;
    --check-only) CHECK_ONLY=1; shift ;;
    --no-reboot) REBOOT=0; shift ;;
    -y | --yes) YES=1; shift ;;
    --allow-debug) ALLOW_DEBUG=1; shift ;;
    --no-verify) VERIFY=0; shift ;;
    --skip-checks) CHECKS=0; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "不认识的参数：$1（--help 看用法）" "unknown option: $1 (see --help)" 2 ;;
    esac
done

# ---------------------------------------------------------------------------------------------- helpers

adb_() { "$ADB" -s "$SERIAL" "$@"; }
sh_() { adb_ shell "$*" 2>/dev/null | tr -d '\r'; }
# AGENTOS_ADB_ROOT=1: a userdebug emulator after `adb root` (no Magisk / KernelSU): root commands then run directly in `adb shell`, the same
# switch tools/check-device.sh and tools/smoke-test.sh have. Real phones never need it.
if [ "${AGENTOS_ADB_ROOT:-0}" = 1 ]; then
    su_() { adb_ shell "$*" 2>/dev/null | tr -d '\r'; }
else
    su_() { adb_ shell "su -c '$*'" 2>/dev/null | tr -d '\r'; }
fi
nap() { sleep "${AGENTOS_NAP:-2}"; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | awk '{ print $NF }'
    else
        return 1
    fi
}

# the digest SHA256SUMS lists for a file name (empty when it is not listed)
expected_digest() { awk -v f="$1" '{ n = $2; sub(/^\*/, "", n); if (n == f) { print $1; exit } }' "$SUMS"; }

verify_file() {
    [ "$VERIFY" = 1 ] || return 0
    _name=$(basename "$1")
    _want=$(expected_digest "$_name")
    [ -n "$_want" ] || die "SHA256SUMS 里没有 ${_name}：文件不完整或不是同一次发布（--no-verify 可跳过，不建议）" "SHA256SUMS does not list $_name: incomplete download or files from different releases (--no-verify skips this, not recommended)"
    _got=$(sha256_of "$1") || die "找不到 sha256sum / shasum / openssl，无法校验" "no sha256sum / shasum / openssl found, cannot verify"
    [ "$_got" = "$_want" ] || die "$_name 的 SHA-256 不对（文件损坏或被改过）：期望 ${_want}，实际 $_got" "$_name has the wrong SHA-256 (corrupt or modified): expected $_want, got $_got"
    ok "校验通过 $_name" "checksum ok: $_name"
}

confirm() {
    [ "$YES" = 1 ] && return 0
    if [ -t 0 ]; then
        printf '%s [y/N] ' "$1"
        read -r _a
        case $_a in y | Y | yes | YES) return 0 ;; esac
        return 1
    fi
    die "没有终端可以确认：加 --yes 才会继续" "no terminal to confirm on: pass --yes to continue"
}

# the module logs one line per boot: "<date> up=<s> install: org.agentos.app result=<same|installed|upgraded|newer|sig_mismatch|corrupt|bad_list|failed> ..."
STATE_LOG=/data/adb/agentos/supervisor.log
last_install_line() { su_ "grep 'install: $PKG result=' $STATE_LOG 2>/dev/null | tail -n 1"; }

sample_list() {
    case $SAMPLES in
    default) echo "$DEFAULT_SAMPLES" ;;
    all) echo "$ALL_SAMPLES" ;;
    none) echo "" ;;
    *) echo "$SAMPLES" | tr ',' ' ' ;;
    esac
}

# ---------------------------------------------------------------------------------------------- 1. the files

command -v "$ADB" >/dev/null 2>&1 || die "找不到 adb：先装 Android platform-tools（见 docs/install.md）" "adb not found: install the Android platform-tools first (see docs/install.md)"

for s in $(sample_list); do
    case " $ALL_SAMPLES " in
    *" $s "*) ;;
    *) die "不认识的示例 App：${s}（可选：${ALL_SAMPLES}）" "unknown sample app: $s (choose from: $ALL_SAMPLES)" 2 ;;
    esac
done

step "1/6 检查文件" "1/6 Checking the files"
SUMS=$DIR/SHA256SUMS
if [ "$VERIFY" = 1 ] && [ ! -f "$SUMS" ]; then
    die "$DIR 里没有 SHA256SUMS：请把 Release 页的所有文件下载到同一个文件夹" "no SHA256SUMS in $DIR: download all files of the release page into one folder"
fi

if [ -f "$DIR/build-info.json" ] && grep -q '"variant": *"debug"' "$DIR/build-info.json" 2>/dev/null && [ "$ALLOW_DEBUG" != 1 ]; then
    die "这是 debug 测试包，不是正式发布（build-info.json 的 variant=debug）。确实要装就加 --allow-debug" "this is a debug test bundle, not a release (build-info.json says variant=debug). Pass --allow-debug if you really want it"
fi

if [ "$SAMPLES_ONLY" != 1 ]; then
    set -- "$DIR"/agentos-*.zip
    if [ $# -ne 1 ] || [ ! -f "$1" ]; then
        die "$DIR 里应当正好有一个 agentos-<版本>.zip" "expected exactly one agentos-<version>.zip in $DIR"
    fi
    ZIP=$1
    case $ZIP in
    *-unsigned.zip) die "$(basename "$ZIP") 是未签名的干跑包，手机装不上" "$(basename "$ZIP") is an unsigned dry-run package, a phone cannot install it" ;;
    esac
    command -v unzip >/dev/null 2>&1 || die "需要 unzip" "unzip is required"
    unzip -tq "$ZIP" >/dev/null 2>&1 || die "$(basename "$ZIP") 不是完整的 zip" "$(basename "$ZIP") is not a complete zip"
    verify_file "$ZIP"
    ZIP_VERSION=$(unzip -p "$ZIP" module.prop 2>/dev/null | sed -n 's/^version=//p' | tr -d '\r' | head -n 1)
    ZIP_VC=$(unzip -p "$ZIP" app/apks.list 2>/dev/null | awk -v p="$PKG" '$1 == p { print $2; exit }')
    [ -n "$ZIP_VERSION" ] && [ -n "$ZIP_VC" ] || die "$(basename "$ZIP") 里读不出 module.prop 的 version 或 app/apks.list 里的 $PKG" "cannot read version from module.prop or $PKG from app/apks.list in $(basename "$ZIP")"
    ok "模块 $(basename "$ZIP")（版本 ${ZIP_VERSION}，AgentOS App versionCode ${ZIP_VC}）" "module $(basename "$ZIP") (version $ZIP_VERSION, AgentOS App versionCode $ZIP_VC)"
fi

APKS=
for s in $(sample_list); do
    set -- "$DIR"/AgentOS-Sample-"$s"-*.apk
    if [ $# -ne 1 ] || [ ! -f "$1" ]; then
        die "$DIR 里应当正好有一个 AgentOS-Sample-$s-<版本>.apk" "expected exactly one AgentOS-Sample-$s-<version>.apk in $DIR"
    fi
    case $1 in
    *-unsigned.apk) die "$(basename "$1") 是未签名的干跑包，手机装不上" "$(basename "$1") is an unsigned dry-run package, a phone cannot install it" ;;
    esac
    verify_file "$1"
    APKS="$APKS $1"
done

# ---------------------------------------------------------------------------------------------- 2. the phone

step "2/6 选择手机" "2/6 Choosing the phone"
DEVS=$("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR > 1 && NF >= 2 && $1 !~ /^\*/ && $2 == "device" { print $1 }')
OTHERS=$("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR > 1 && NF >= 2 && $1 !~ /^\*/ && $2 != "device" { printf "%s(%s) ", $1, $2 }')
if [ -n "$SERIAL" ]; then
    printf '%s\n' "$DEVS" | grep -qx "$SERIAL" || die "手机 $SERIAL 不在线。adb 看到的：${DEVS:-无} ${OTHERS}（unauthorized = 手机上还没点“允许 USB 调试”）" "phone $SERIAL is not online. adb sees: ${DEVS:-nothing} ${OTHERS}(unauthorized = you have not tapped \"Allow USB debugging\" on the phone)"
else
    N=$(printf '%s\n' "$DEVS" | grep -c .)
    case $N in
    0) die "没有连接的手机。${OTHERS}检查：USB 线、手机上已开启 USB 调试并点了“允许”（见 docs/install.md）" "no phone connected. ${OTHERS}Check the cable, and that USB debugging is on and you tapped \"Allow\" on the phone (docs/install.md)" ;;
    1) SERIAL=$DEVS ;;
    *) die "连接了多台手机，请用 -s 指定一台：$(printf '%s' "$DEVS" | tr '\n' ' ')" "more than one phone is connected, pick one with -s: $(printf '%s' "$DEVS" | tr '\n' ' ')" 2 ;;
    esac
fi
MODEL=$(sh_ getprop ro.product.model)
ok "${SERIAL}（${MODEL:-?}，Android $(sh_ getprop ro.build.version.release)）" "$SERIAL (${MODEL:-?}, Android $(sh_ getprop ro.build.version.release))"

# ---------------------------------------------------------------------------------------------- 3. pre-flight

if [ "$SAMPLES_ONLY" != 1 ] && [ "$CHECKS" = 1 ]; then
    step "3/6 前检（root、Android 版本、空间、签名）" "3/6 Pre-flight (root, Android version, space, signature)"
    say "如果手机弹出“超级用户 / Superuser 请求”，请点允许（见 docs/install.md）。" "If the phone shows a Superuser request, tap Grant (docs/install.md)."
    [ -f "$DIR/check-device.sh" ] || die "$DIR 里没有 check-device.sh（Release 页的文件要一起下载）" "no check-device.sh in $DIR (download all files of the release page)"
    verify_file "$DIR/check-device.sh"
    TMP=$(mktemp -d "${TMPDIR:-/tmp}/agentos-install.XXXXXX") || die "建不了临时目录" "cannot create a temp folder"
    # check-device.sh reads module/common.sh and the support matrix relative to its own place in the repository: rebuild that layout from the zip
    mkdir -p "$TMP/tools" "$TMP/module"
    cp "$DIR/check-device.sh" "$TMP/tools/check-device.sh"
    unzip -p "$ZIP" common.sh >"$TMP/module/common.sh" 2>/dev/null
    unzip -p "$ZIP" support-matrix.yaml >"$TMP/module/support-matrix.yaml" 2>/dev/null
    [ -s "$TMP/module/common.sh" ] && [ -s "$TMP/module/support-matrix.yaml" ] || die "zip 里缺 common.sh 或 support-matrix.yaml" "common.sh or support-matrix.yaml is missing from the zip"
    if ! ANDROID_SERIAL=$SERIAL sh "$TMP/tools/check-device.sh" "$ZIP"; then
        die "前检没通过（上面 FAIL 的行）。先解决，再重跑本脚本" "the pre-flight failed (the FAIL lines above). Fix them, then run this script again"
    fi
    BT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* 2>/dev/null | tail -n 1)
    if [ -z "$BT" ]; then
        if [ -n "$(sh_ pm list packages $PKG)" ]; then
            warn "手机上已经装了 AgentOS，但这台电脑上没有 Android SDK 的 apksigner，没法核对它和 zip 的签名是否一致。如果它不是用同一张发布证书装的，模块会停止升级——先看 docs/install.md“从测试版升级”" "AgentOS is already on the phone but this computer has no Android SDK apksigner, so the signing certificates cannot be compared. If it was not installed from the same release certificate the module will refuse to upgrade it - read \"Upgrading from a test build\" in docs/install.md"
        fi
    fi
fi
[ "$CHECK_ONLY" = 1 ] && { say "只做前检，到此为止。" "Pre-flight only, stopping here."; exit 0; }

# ---------------------------------------------------------------------------------------------- 4. confirm

step "4/6 确认" "4/6 Confirm"
if [ "$SAMPLES_ONLY" != 1 ]; then
    say "将刷入模块 $(basename "$ZIP")（版本 ${ZIP_VERSION}），$([ "$REBOOT" = 1 ] && echo '然后重启手机' || echo '不重启（之后你自己重启）')。" "Will flash the module $(basename "$ZIP") (version $ZIP_VERSION), $([ "$REBOOT" = 1 ] && echo 'then reboot the phone' || echo 'without rebooting (you reboot later)')."
fi
if [ -n "$APKS" ]; then
    say "将安装示例 App：$(sample_list)" "Will install the sample apps: $(sample_list)"
fi
if [ "$SAMPLES_ONLY" = 1 ] && [ -z "$APKS" ]; then
    die "--samples-only 但没有选任何示例 App" "--samples-only but no sample app is selected" 2
fi
confirm "$(say "对 $SERIAL 继续？" "Continue on $SERIAL?")" || { say "已取消，没有改动手机。" "Cancelled, nothing was changed on the phone."; exit 1; }

# ---------------------------------------------------------------------------------------------- 5. flash, reboot, wait

if [ "$SAMPLES_ONLY" != 1 ]; then
    step "5/6 刷入模块" "5/6 Flashing the module"
    BEFORE_LINE=$(last_install_line)
    sh_ "mkdir -p $DEV_DIR" >/dev/null
    adb_ push "$ZIP" "$DEV_DIR/agentos.zip" >/dev/null || die "把 zip 推到手机失败" "pushing the zip to the phone failed"
    if [ -n "$(su_ command -v magisk)" ]; then
        MANAGER=Magisk
        OUT=$(su_ magisk --install-module "$DEV_DIR/agentos.zip")
    elif [ -n "$(su_ ls /data/adb/ksud)" ]; then
        MANAGER=KernelSU
        OUT=$(su_ /data/adb/ksud module install "$DEV_DIR/agentos.zip")
    else
        sh_ "rm -rf $DEV_DIR" >/dev/null
        die "没找到 Magisk 或 KernelSU（或没给 shell 授权 root）" "neither Magisk nor KernelSU found (or the shell was not granted root)"
    fi
    printf '%s\n' "$OUT" | sed 's/^/  | /'
    sh_ "rm -rf $DEV_DIR" >/dev/null
    case $OUT in
    *"! "*) die "$MANAGER 拒绝了这个模块（上面以 ! 开头的行是原因）" "$MANAGER refused the module (the lines starting with ! above say why)" ;;
    esac
    # Magisk and KernelSU stage a module in /data/adb/modules_update and move it at the next boot
    STAGED=$(su_ "grep -h ^version= /data/adb/modules_update/agentos/module.prop /data/adb/modules/agentos/module.prop 2>/dev/null")
    case $(printf '%s\n' "$STAGED" | tr -d '\r') in
    *"version=$ZIP_VERSION"*) ok "$MANAGER 已收下模块 $ZIP_VERSION" "$MANAGER accepted module $ZIP_VERSION" ;;
    *) die "$MANAGER 没有报错，但手机上找不到版本 $ZIP_VERSION 的模块（${STAGED:-空}）" "$MANAGER reported no error but no module with version $ZIP_VERSION is on the phone (${STAGED:-empty})" ;;
    esac

    if [ "$REBOOT" = 1 ]; then
        step "重启并等待" "Rebooting and waiting"
        adb_ reboot >/dev/null 2>&1
        i=0
        while [ "$(adb_ get-state 2>/dev/null)" = device ] && [ "$i" -lt 30 ]; do i=$((i + 1)); nap; done
        i=0
        until [ "$(adb_ get-state 2>/dev/null)" = device ] && [ "$(sh_ getprop sys.boot_completed)" = 1 ]; do
            i=$((i + 1))
            if [ "$i" -gt 150 ]; then
                say "5 分钟了手机还没开机完成。别慌：见 docs/install.md“如果手机起不来”（Magisk 安全模式：开机时按住音量下）。" "The phone has not finished booting after 5 minutes. Do not panic: see \"If the phone does not boot\" in docs/install.md (Magisk safe mode: hold volume-down while booting)."
                exit 3
            fi
            nap
        done
        ok "手机已开机" "the phone has booted"
        say "等模块在开机后装好 AgentOS App（最多 3 分钟）…" "Waiting for the module to install the AgentOS App after boot (up to 3 minutes) ..."
        i=0
        LINE=
        while [ "$i" -lt 90 ]; do
            LINE=$(last_install_line)
            # a new line (the date and uptime are in it) means this boot's install phase has run
            [ -n "$LINE" ] && [ "$LINE" != "$BEFORE_LINE" ] && break
            LINE=
            i=$((i + 1))
            nap
        done
        if [ -z "$LINE" ]; then
            say "3 分钟内模块没有记下安装结果。模块已刷入，但没看到它工作：见 docs/install.md“如果它没装上”。" "The module wrote no install result within 3 minutes. It is flashed, but it was not seen working: see \"If the App does not appear\" in docs/install.md."
            exit 3
        fi
        RESULT=$(printf '%s\n' "$LINE" | sed -n 's/.*result=\([a-z_]*\).*/\1/p')
        INST=$(sh_ pm list packages --show-versioncode "$PKG" | sed -n "s/^package:$PKG versionCode:\([0-9]*\).*/\1/p" | head -n 1)
        case $RESULT in
        installed | upgraded | same | newer)
            ok "AgentOS App：${RESULT}（手机上 versionCode ${INST:-?}）" "AgentOS App: $RESULT (versionCode on the phone ${INST:-?})"
            ;;
        sig_mismatch)
            say "AgentOS App 没有换成这个版本：手机上已有的 AgentOS 和 zip 里的签名不一致（比如之前装的是 debug 测试版）。模块不会卸载它，也不会动它的数据。想换成正式版，要先自己卸载旧的（数据会一起删除）再重启一次：见 docs/install.md“从测试版升级”。" "The AgentOS App was not replaced: the AgentOS on the phone is signed with another certificate than the zip (for example an earlier debug build). The module never uninstalls it and never touches its data. To switch to the release you must uninstall the old one yourself (its data goes with it) and reboot once: see \"Upgrading from a test build\" in docs/install.md."
            exit 3
            ;;
        *)
            say "模块报告安装结果：${RESULT:-?}（不是成功）。日志：adb -s $SERIAL shell su -c 'tail -n 30 $STATE_LOG'；见 docs/install.md“如果它没装上”。" "The module reports the install result: ${RESULT:-?} (not a success). Log: adb -s $SERIAL shell su -c 'tail -n 30 $STATE_LOG'; see \"If the App does not appear\" in docs/install.md."
            exit 3
            ;;
        esac
    else
        warn "没有重启：模块要重启后才生效，AgentOS App 也要重启后才会被装上" "No reboot: the module only takes effect after a reboot, and the AgentOS App is only installed after it"
    fi
fi

# ---------------------------------------------------------------------------------------------- 6. samples

if [ -n "$APKS" ]; then
    step "6/6 安装示例 App" "6/6 Installing the sample apps"
    for apk in $APKS; do
        name=$(basename "$apk")
        if OUT=$(adb_ install -r "$apk" 2>&1); then
            ok "已安装 $name" "installed $name"
        else
            FAILED=1
            printf '  | %s\n' "$OUT"
            case $OUT in
            *UPDATE_INCOMPATIBLE*) warn "${name}：手机上已有同名 App 但签名不同（比如之前装的是 debug 测试版）。要换成正式版只能先卸载旧的，数据会一起删除——见 docs/install.md" "$name: an app with the same name but another signature is installed (for example an earlier debug build). Replacing it means uninstalling the old one, and its data goes with it - see docs/install.md" ;;
            *DOWNGRADE*) warn "${name}：手机上的版本比这个新，没有降级" "$name: the phone has a newer version, not downgrading" ;;
            *) warn "$name 没装上（原因见上）" "$name was not installed (reason above)" ;;
            esac
        fi
    done
fi

# ---------------------------------------------------------------------------------------------- done

step "完成" "Done"
say "接下来这些要你自己在手机上做（脚本做不了，详见 docs/install.md）：" "What is left for you to do on the phone (the script cannot, see docs/install.md):"
say "  1. 打开 AgentOS → 设置 → 模型与 Key，填你自己的模型 key。" "  1. Open AgentOS -> Settings -> Model & key and enter your own model key."
say "  2. 设置 → 插件：把要用的示例插件打开（第三方插件默认关闭）。" "  2. Settings -> Plugins: turn on the sample plugins you want (third-party plugins are off by default)."
case " $(sample_list) " in
*" sms "*) say "  3. 短信 App：先授予短信权限；系统若拦着，按它页面上的三步“允许受限制的设置”。" "  3. Messages app: grant the SMS permissions; if Android blocks it follow the three \"Allow restricted settings\" steps on its page." ;;
esac
say "  通知权限、电池优化也建议允许，否则后台任务和提醒可能被系统冻结。" "  Also allow notifications and battery optimisation exemption, or background work and reminders may be frozen by the system."
[ "$FAILED" = 0 ] || { warn "有示例 App 没装上（见上面的 WARN）" "Some sample apps were not installed (see the WARN lines above)"; exit 1; }
exit 0
