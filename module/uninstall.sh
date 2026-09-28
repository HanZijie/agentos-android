#!/system/bin/sh
# AgentOS module: run by the root manager when the module is removed (architecture F13).
# Stops the supervisor and deletes its state (/data/adb/agentos). The AgentOS App is NOT uninstalled:
# the user removes it like any other App, and its data goes with it.
STATE=${AGENTOS_DATA:-/data}/adb/agentos
PROCFS=${AGENTOS_PROCFS:-/proc}

if [ -f "$STATE/supervisor.pid" ]; then
    read -r pid <"$STATE/supervisor.pid"
    case $pid in
    '' | *[!0-9]*) ;;
    *)
        if [ -d "$PROCFS/$pid" ] && grep -q "service.sh" "$PROCFS/$pid/cmdline" 2>/dev/null; then
            kill "$pid" 2>/dev/null
            # TERM is handled when the supervisor's current sleep returns (<= 5 s)
            i=0
            while [ -d "$PROCFS/$pid" ] && [ $i -lt 60 ]; do sleep 0.1; i=$((i + 1)); done
            [ -d "$PROCFS/$pid" ] && kill -9 "$pid" 2>/dev/null
        fi
        ;;
    esac
fi
rm -rf "$STATE"
