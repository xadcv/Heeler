#!/usr/bin/env bash
# Runs the Android transport suites (`:ssh:test`) against a real, disposable
# sshd pair and the fake herdr fixture shared with the iOS CI script
# (scripts/fixtures/fake-herdr-streamlocal.py). Nothing here needs root or an
# emulator: both sshd instances run as the invoking user on loopback ports.
#
#   modern sshd  (AllowStreamLocalForwarding yes)  -> RPC, events, attach
#   denied sshd  (AllowStreamLocalForwarding no)   -> preflight refusal
#
# The sshd forces HOME to a throwaway fixture home so the transport's own
# `$HOME` resolution finds the fixture socket at ~/.config/herdr/herdr.sock and
# the attach stub at ~/.local/bin/herdr. The suite writes authorized_keys
# itself from a Device Key it generates in-process.
#
# Usage: android/scripts/run-transport-e2e.sh [extra gradle args]
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
android_root="$repo_root/android"
sshd_bin=${SSHD_BIN:-$(command -v sshd || echo /usr/sbin/sshd)}
[ -x "$sshd_bin" ] || { echo "sshd not found (set SSHD_BIN)" >&2; exit 2; }
command -v ssh-keygen >/dev/null || { echo "ssh-keygen not found" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 2; }

fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/heeler-e2e.XXXXXX")
fixture_home="$fixture_dir/home"
mkdir -p "$fixture_home/.config/herdr/sessions/stale" "$fixture_home/.local/bin" "$fixture_dir/empty"
chmod 700 "$fixture_dir"

pids=()
cleanup() {
    for pid in "${pids[@]:-}"; do
        [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
    done
    rm -rf "$fixture_dir"
}
trap cleanup EXIT

free_port() {
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'
}

ssh-keygen -q -t ed25519 -N '' -f "$fixture_dir/host_ed25519"
: > "$fixture_dir/authorized_keys"
chmod 600 "$fixture_dir/authorized_keys"

# The attach stub: what `herdr agent attach`/`herdr terminal attach` looks like
# to the transport. Mirrors the fake-attach script the iOS CI uses.
# shellcheck disable=SC2016
printf '%s\n' \
    '#!/bin/sh' \
    'stty -echo' \
    'printf "TTY-OK\n"' \
    'printf "ARGS:%s\n" "$*"' \
    'printf "SOCKET:%s\n" "$HERDR_SOCKET_PATH"' \
    'stty size' \
    'while IFS= read -r line; do' \
    '    case "$line" in' \
    '        __exit__) exit 0 ;;' \
    '        __fail__) exit 23 ;;' \
    '        __size__) stty size; continue ;;' \
    '    esac' \
    '    printf "GOT:%s\n" "$line"' \
    'done' \
    > "$fixture_home/.local/bin/herdr"
chmod 755 "$fixture_home/.local/bin/herdr"

modern_port=$(free_port)
denied_port=$(free_port)
while [ "$denied_port" = "$modern_port" ]; do denied_port=$(free_port); done
username=$(id -un)

write_config() {
    local port=$1 pid_file=$2 stream_local=$3
    printf '%s\n' \
        "Port $port" \
        "ListenAddress 127.0.0.1" \
        "HostKey $fixture_dir/host_ed25519" \
        "PidFile $pid_file" \
        "PasswordAuthentication no" \
        "KbdInteractiveAuthentication no" \
        "PubkeyAuthentication yes" \
        "AuthorizedKeysFile $fixture_dir/authorized_keys" \
        "UsePAM no" \
        "PermitRootLogin no" \
        "AllowUsers $username" \
        "StrictModes no" \
        "PrintMotd no" \
        "PrintLastLog no" \
        "LogLevel VERBOSE" \
        "MaxSessions 10" \
        "MaxStartups 100:30:200" \
        "AllowStreamLocalForwarding $stream_local" \
        "SetEnv HOME=$fixture_home"
}

write_config "$modern_port" "$fixture_dir/sshd-modern.pid" yes > "$fixture_dir/sshd-modern.conf"
write_config "$denied_port" "$fixture_dir/sshd-denied.pid" no > "$fixture_dir/sshd-denied.conf"

"$sshd_bin" -D -e -f "$fixture_dir/sshd-modern.conf" 2> "$fixture_dir/sshd-modern.log" &
pids+=($!)
"$sshd_bin" -D -e -f "$fixture_dir/sshd-denied.conf" 2> "$fixture_dir/sshd-denied.log" &
pids+=($!)

python3 "$repo_root/scripts/fixtures/fake-herdr-streamlocal.py" \
    --socket "$fixture_home/.config/herdr/herdr.sock" \
    --stale-socket "$fixture_home/.config/herdr/sessions/stale/herdr.sock" \
    --count-file "$fixture_dir/connections.count" \
    > "$fixture_dir/fake-herdr.log" 2>&1 &
pids+=($!)

wait_listening() {
    local port=$1
    for _ in $(seq 1 50); do
        if python3 -c "import socket,sys; s=socket.socket(); s.settimeout(0.2)
sys.exit(0 if s.connect_ex(('127.0.0.1',$port))==0 else 1)"; then return 0; fi
        sleep 0.1
    done
    echo "sshd on port $port never came up" >&2
    cat "$fixture_dir"/sshd-*.log >&2
    return 1
}
wait_listening "$modern_port"
wait_listening "$denied_port"
for _ in $(seq 1 50); do
    [ -S "$fixture_home/.config/herdr/herdr.sock" ] && break
    sleep 0.1
done
[ -S "$fixture_home/.config/herdr/herdr.sock" ] || { echo "fake herdr never listened" >&2; cat "$fixture_dir/fake-herdr.log" >&2; exit 1; }

export HEELER_E2E_PORT=$modern_port
export HEELER_E2E_PORT_DENIED=$denied_port
export HEELER_E2E_USER=$username
export HEELER_E2E_AUTHORIZED_KEYS_FILE=$fixture_dir/authorized_keys
export HEELER_E2E_HOST_KEY_PUBLIC_FILE=$fixture_dir/host_ed25519.pub
export HEELER_E2E_HOME_DIR=$fixture_home

echo "sshd modern=127.0.0.1:$modern_port denied=127.0.0.1:$denied_port user=$username home=$fixture_home"

status=0
(cd "$android_root" && ./gradlew :ssh:test --rerun --console=plain "$@") || status=$?

# The suite must have run, not skipped: count executed E2E cases from the XML report.
report="$android_root/ssh/build/test-results/test/TEST-dev.bybee.heeler.ssh.JschTransportE2ETest.xml"
if [ -f "$report" ]; then
    python3 - "$report" <<'EOF' || status=1
import sys, xml.etree.ElementTree as ET
suite = ET.parse(sys.argv[1]).getroot()
tests, skipped, failures, errors = (int(suite.get(k, 0)) for k in ("tests", "skipped", "failures", "errors"))
executed = tests - skipped
print(f"JschTransportE2ETest: {executed} executed, {skipped} skipped, {failures} failures, {errors} errors")
if executed == 0:
    print("E2E suite was skipped; the fixture environment did not reach the tests", file=sys.stderr)
    sys.exit(1)
EOF
else
    echo "no JUnit report for JschTransportE2ETest" >&2
    status=1
fi

if [ "$status" -ne 0 ]; then
    echo "--- sshd modern log (tail)" >&2; tail -n 40 "$fixture_dir/sshd-modern.log" >&2 || true
    echo "--- fake herdr log (tail)" >&2; tail -n 40 "$fixture_dir/fake-herdr.log" >&2 || true
fi
exit "$status"
