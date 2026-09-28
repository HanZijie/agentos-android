#!/system/bin/sh
# Stop the supervisor and remove its state. The test Apps stay installed (remove them with adb).
STATE=/data/adb/agentos-s2
if [ -f "$STATE/supervisor.pid" ]; then
    read -r pid <"$STATE/supervisor.pid"
    case $pid in '' | *[!0-9]*) ;; *) kill "$pid" 2>/dev/null ;; esac
fi
rm -rf "$STATE"
