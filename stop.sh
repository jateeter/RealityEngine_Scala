#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

INSTANCE_ID="${INSTANCE_ID:-}"
for arg in "$@"; do
  case "$arg" in
    --instance=*) INSTANCE_ID="${arg#--instance=}" ;;
  esac
done
_INST="${INSTANCE_ID:+-${INSTANCE_ID}}"

stop_pid_file() {
  local label="$1"
  local pid_file="$2"

  if [ ! -f "$pid_file" ]; then
    echo "$label is not running"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  if [ -z "$pid" ] || ! kill -0 "$pid" >/dev/null 2>&1; then
    rm -f "$pid_file"
    echo "$label was not running; removed stale PID file"
    return 0
  fi

  echo "Stopping $label (PID $pid)"
  kill -TERM "$pid" 2>/dev/null || true

  local waited=0
  while kill -0 "$pid" >/dev/null 2>&1 && [ "$waited" -lt 15 ]; do
    sleep 1
    waited=$((waited + 1))
  done

  if kill -0 "$pid" >/dev/null 2>&1; then
    echo "$label did not exit after ${waited}s; sending SIGKILL" >&2
    kill -KILL "$pid" 2>/dev/null || true
  fi

  rm -f "$pid_file"
}

stop_pid_file "Scala Perception Engine" "run/perception-engine${_INST}.pid"
stop_pid_file "Scala Reality Engine" "run/reality-engine${_INST}.pid"
