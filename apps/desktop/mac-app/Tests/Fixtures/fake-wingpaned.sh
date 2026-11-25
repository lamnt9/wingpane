#!/bin/sh

if [ -n "$FAKE_DAEMON_ARGS_FILE" ]; then
    printf "%s" "$*" > "$FAKE_DAEMON_ARGS_FILE"
fi

if [ -n "$FAKE_DAEMON_ADB_FILE" ]; then
    printf "%s" "$WINGPANE_ADB" > "$FAKE_DAEMON_ADB_FILE"
fi

STDIN_PID=""
if [ -n "$FAKE_DAEMON_STDIN_FILE" ]; then
    /bin/cat > "$FAKE_DAEMON_STDIN_FILE" 2>/dev/null &
    STDIN_PID=$!
fi

on_sigint() {
    if [ -n "$STDIN_PID" ]; then
        kill "$STDIN_PID" 2>/dev/null || true
    fi
    if [ -n "$FAKE_DAEMON_SIGNAL_FILE" ]; then
        printf "SIGINT" > "$FAKE_DAEMON_SIGNAL_FILE"
    fi
    printf '{"event":"stopped"}\n'
    exit 0
}

trap on_sigint INT

printf '{"event":"starting","version":"0.1.0"}\n'
printf '{"event":"waiting_device","reason":"none","devices":[]}\n'
printf '{"event":"device","serial":"R58M123456","model":"Pixel 8"}\n'
printf '{"event":"listening","port":7788}\n'
printf '{"event":"client_connected","peer":"127.0.0.1:54321"}\n'
printf '{"event":"streaming","width":2340,"height":1080,"hz":60,"hidpi":true}\n'
printf '{"event":"stats","fps":57,"encode_p95_ms":4.2,"write_p95_ms":1.1,"backlog_dropped":0}\n'

while true; do
    /bin/sleep 0.05
done
