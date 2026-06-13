#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [ -f .env ]; then
  # shellcheck source=/dev/null
  source .env
fi

REALITY_ENGINE_PORT="${REALITY_ENGINE_PORT:-5001}"
PERCEPTION_ENGINE_PORT="${PERCEPTION_ENGINE_PORT:-5000}"
VECTOR_DIMENSION="${VECTOR_DIMENSION:-7680}"
MACHINES_DIR="${MACHINES_DIR:-../RealityEngine_Machines/machines}"
QDRANT_URL="${QDRANT_URL:-http://localhost:4333}"
LOCAL_AI_API_URL="${LOCAL_AI_API_URL:-http://localhost:4000}"
LOCAL_AI_MACHINES_DIR="${LOCAL_AI_MACHINES_DIR:-../localAIStack/data/machines}"
REALITY_ENGINE_URL="${REALITY_ENGINE_URL:-http://localhost:${REALITY_ENGINE_PORT}}"
HOST="${HOST:-0.0.0.0}"
INSTANCE_ID="${INSTANCE_ID:-}"
_INST="${INSTANCE_ID:+-${INSTANCE_ID}}"
SBT="${SBT:-sbt}"

RUN_DIR="$ROOT_DIR/run"
LOG_DIR="$ROOT_DIR/logs"
mkdir -p "$RUN_DIR" "$LOG_DIR"

RE_JAR="${REALITY_ENGINE_JAR:-$ROOT_DIR/target/scala-2.13/reality-engine.jar}"
PE_JAR="${PERCEPTION_ENGINE_JAR:-$ROOT_DIR/perception-engine/target/scala-2.13/perception-engine.jar}"
RE_PID_FILE="$RUN_DIR/reality-engine${_INST}.pid"
PE_PID_FILE="$RUN_DIR/perception-engine${_INST}.pid"
RE_LOG_FILE="$LOG_DIR/reality-engine${_INST}.log"
PE_LOG_FILE="$LOG_DIR/perception-engine${_INST}.log"

die() {
  echo "fatal: $*" >&2
  exit 1
}

wait_for_http() {
  local url="$1" label="$2"
  local n=0
  while [ "$n" -lt 45 ]; do
    if curl -sf --max-time 2 "$url" >/dev/null 2>&1; then
      echo "$label ready"
      return 0
    fi
    n=$((n + 1))
    sleep 1
  done
  return 1
}

check_port_free() {
  local port="$1" pid_file="$2"
  if [ -f "$pid_file" ]; then
    local pid
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
      die "port $port is already owned by pid $pid from $(basename "$pid_file")"
    fi
    rm -f "$pid_file"
  fi
  if lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    die "port $port is already in use"
  fi
}

if [ ! -f "$RE_JAR" ]; then
  echo "Building Scala Reality Engine assembly..."
  "$SBT" assembly
fi
if [ ! -f "$PE_JAR" ]; then
  echo "Building Scala Perception Engine assembly..."
  (cd "$ROOT_DIR/perception-engine" && "$SBT" assembly)
fi
[ -f "$RE_JAR" ] || die "missing $RE_JAR after assembly"
[ -f "$PE_JAR" ] || die "missing $PE_JAR after assembly"
[ -d "$MACHINES_DIR" ] || die "machine directory not found: $MACHINES_DIR"

check_port_free "$REALITY_ENGINE_PORT" "$RE_PID_FILE"
check_port_free "$PERCEPTION_ENGINE_PORT" "$PE_PID_FILE"

echo "Starting Scala Reality Engine on $HOST:$REALITY_ENGINE_PORT${INSTANCE_ID:+ [instance: $INSTANCE_ID]}"
HOST="$HOST" \
PORT="$REALITY_ENGINE_PORT" \
REALITY_ENGINE_PORT="$REALITY_ENGINE_PORT" \
VECTOR_DIMENSION="$VECTOR_DIMENSION" \
MACHINES_DIR="$MACHINES_DIR" \
QDRANT_URL="$QDRANT_URL" \
LOCAL_AI_API_URL="$LOCAL_AI_API_URL" \
  nohup java -jar "$RE_JAR" > "$RE_LOG_FILE" 2>&1 &
echo "$!" > "$RE_PID_FILE"

if ! wait_for_http "http://localhost:${REALITY_ENGINE_PORT}/api/health" "Scala RE"; then
  tail -60 "$RE_LOG_FILE" || true
  "$ROOT_DIR/stop.sh" "${INSTANCE_ID:+--instance=$INSTANCE_ID}" >/dev/null 2>&1 || true
  die "Scala Reality Engine failed to become healthy"
fi

echo "Starting Scala Perception Engine on $HOST:$PERCEPTION_ENGINE_PORT${INSTANCE_ID:+ [instance: $INSTANCE_ID]}"
HOST="$HOST" \
PORT="$PERCEPTION_ENGINE_PORT" \
PERCEPTION_ENGINE_PORT="$PERCEPTION_ENGINE_PORT" \
REALITY_ENGINE_URL="$REALITY_ENGINE_URL" \
VECTOR_DIMENSION="$VECTOR_DIMENSION" \
LOCAL_AI_API_URL="$LOCAL_AI_API_URL" \
LOCAL_AI_MACHINES_DIR="$LOCAL_AI_MACHINES_DIR" \
  nohup java -jar "$PE_JAR" > "$PE_LOG_FILE" 2>&1 &
echo "$!" > "$PE_PID_FILE"

if ! wait_for_http "http://localhost:${PERCEPTION_ENGINE_PORT}/api/health" "Scala PE"; then
  tail -60 "$PE_LOG_FILE" || true
  "$ROOT_DIR/stop.sh" "${INSTANCE_ID:+--instance=$INSTANCE_ID}" >/dev/null 2>&1 || true
  die "Scala Perception Engine failed to become healthy"
fi

cat <<EOF
Scala RealityEngine started
  Reality Engine:    http://localhost:${REALITY_ENGINE_PORT}
  Perception Engine: http://localhost:${PERCEPTION_ENGINE_PORT}
  Machines:          ${MACHINES_DIR}
  Vector dimension:  ${VECTOR_DIMENSION}
  Qdrant:            ${QDRANT_URL}
EOF
