# AgentOS module: helpers shared by customize.sh (flash time) and service.sh (boot).
# POSIX sh: runs under Magisk / KernelSU busybox ash (standalone mode) and mksh (/system/bin/sh).
# Always brace variables inside non-ASCII strings ("${X}。"): some shells read UTF-8 bytes as name chars.
# Nothing here writes outside the module directory and /data/adb/agentos (architecture principle 7).

AGENTOS_MODULE_ID=agentos

is_uint() { case $1 in '' | *[!0-9]*) return 1 ;; esac; [ ${#1} -le 18 ]; }

# ---- support-matrix.yaml ------------------------------------------------------------------------
# Only a flat subset of YAML is read (see the header of support-matrix.yaml):
#   key: value            top-level scalars
#   key:                  followed by "  - item" lines (lists)
# Comments (" #" to end of line) and surrounding quotes are dropped.

_matrix_clean() { # value -> MV
    MV=$1
    case $MV in *" #"*) MV=${MV%% #*} ;; esac
    while :; do case $MV in ' '*) MV=${MV# } ;; *) break ;; esac; done
    while :; do case $MV in *' ') MV=${MV% } ;; *) break ;; esac; done
    case $MV in \"*\") MV=${MV#\"}; MV=${MV%\"} ;; \'*\') MV=${MV#\'}; MV=${MV%\'} ;; esac
}

matrix_get() { # file key -> MV (empty when missing)
    MV=
    [ -f "$1" ] || return 1
    while IFS= read -r _ml || [ -n "$_ml" ]; do
        case $_ml in
        "$2:"*)
            _matrix_clean "${_ml#*:}"
            return 0
            ;;
        esac
    done <"$1"
    MV=
    return 1
}

matrix_list() { # file key -> one item per line on stdout
    [ -f "$1" ] || return 1
    _in=0
    while IFS= read -r _ml || [ -n "$_ml" ]; do
        case $_ml in
        "$2:"*) _in=1; continue ;;
        '  - '* | '- '*)
            if [ $_in = 1 ]; then
                _matrix_clean "${_ml#*- }"
                [ -n "$MV" ] && echo "$MV"
            fi
            ;;
        '' | ' '* | '#'*) ;;
        *) _in=0 ;;
        esac
    done <"$1"
}

matrix_has() { # file key item -> 0 when item is listed under key (items never contain spaces)
    for _it in $(matrix_list "$1" "$2"); do
        [ "$_it" = "$3" ] && return 0
    done
    return 1
}

# ---- module.prop --------------------------------------------------------------------------------

set_desc() { # text -> module.prop "description" (Magisk and KernelSU show it in the module list)
    _p="$MODDIR/module.prop"
    [ -f "$_p" ] || return 0
    while IFS= read -r _l || [ -n "$_l" ]; do
        case $_l in
        description=*) echo "description=$1" ;;
        *) echo "$_l" ;;
        esac
    done <"$_p" >"$_p.tmp" && mv -f "$_p.tmp" "$_p"
}

prop_get() { # file key -> PV
    PV=
    [ -f "$1" ] || return 1
    while IFS='=' read -r _k _v || [ -n "$_k" ]; do
        [ "$_k" = "$2" ] && { PV=$_v; return 0; }
    done <"$1"
    return 1
}
