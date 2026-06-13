#!/usr/bin/env bash
# Conformance smoke test against SURFACE_SPEC.md.
# Verifies every canonical route returns a non-404, non-500 response with valid JSON.
#
# Usage:
#   ./scripts/smoke-test.sh [options]
#
# Options:
#   --target  <url>    RE runtime base URL  (default: https://localhost:3000)
#   --pe-target <url> PE runtime base URL  (default: https://localhost:3004)
#   --pe        <url> Compatibility alias for --pe-target
#   --skip-pe          Skip PE surface tests
#   --skip-re          Skip RE surface tests
#   --verbose          Print each request/response

set -euo pipefail

RE_URL="https://localhost:3000"
PE_URL="https://localhost:3004"
SKIP_PE=0
SKIP_RE=0
VERBOSE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)  RE_URL="$2";  shift 2 ;;
    --pe-target|--pe) PE_URL="$2";  shift 2 ;;
    --skip-pe) SKIP_PE=1;    shift   ;;
    --skip-re) SKIP_RE=1;    shift   ;;
    --verbose) VERBOSE=1;    shift   ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

PASS=0
FAIL=0
ERRORS=()

check() {
  local method="$1"
  local base_url="$2"
  local path="$3"
  local body="${4:-}"
  local url="${base_url}${path}"
  local body_file
  body_file="$(mktemp "${TMPDIR:-/tmp}/re-smoke.XXXXXX")"

  local args=(-s -k -o "$body_file" -w "%{http_code}" --max-time 5)
  if [[ -n "$body" ]]; then
    args+=(-X "$method" -H "Content-Type: application/json" -d "$body")
  else
    args+=(-X "$method")
  fi

  local status
  set +e
  status=$(curl "${args[@]}" "$url" 2>/dev/null)
  local curl_rc=$?
  set -e
  if [[ "$curl_rc" -ne 0 ]]; then
    status="000"
  fi

  if [[ "$VERBOSE" == "1" ]]; then
    echo "  $method $path -> $status"
  fi

  if [[ "$status" == "000" ]]; then
    FAIL=$((FAIL + 1))
    ERRORS+=("CONN  $method $path  (connection refused / timeout)")
  elif [[ "$status" == "404" ]]; then
    FAIL=$((FAIL + 1))
    ERRORS+=("404   $method $path")
  elif [[ "$status" == "500" ]]; then
    FAIL=$((FAIL + 1))
    ERRORS+=("500   $method $path")
  elif ! valid_json "$body_file"; then
    FAIL=$((FAIL + 1))
    ERRORS+=("JSON  $method $path  (response body is not valid JSON)")
  else
    PASS=$((PASS + 1))
  fi

  rm -f "$body_file"
}

valid_json() {
  local file="$1"
  if command -v node >/dev/null 2>&1; then
    node -e 'const fs=require("fs"); const s=fs.readFileSync(process.argv[1],"utf8").trim(); if (!s) process.exit(1); JSON.parse(s);' "$file" >/dev/null 2>&1
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "$file" >/dev/null 2>&1 <<'PY'
import json
import sys
from pathlib import Path
s = Path(sys.argv[1]).read_text().strip()
if not s:
    raise SystemExit(1)
json.loads(s)
PY
  else
    return 0
  fi
}

# ── Minimal valid bodies for POST/PUT/PATCH routes ──────────────────────────
VEC_BODY='{"vector":[0.1,0.2,0.3],"id":"smoke-vec"}'
SEARCH_BODY='{"query":[0.1,0.2,0.3],"topK":1}'
SEQ_BODY='{"id":"smoke-seq","name":"smoke","vectors":[]}'
MACHINE_BODY='{"id":"smoke-machine","name":"smoke","states":[],"sequences":[]}'
PROCESS_BODY='{"inputVector":[0.1,0.2,0.3]}'
UNIVERSAL_BODY='{"universalInputSpace":[0.1,0.2,0.3]}'
WHATIF_BODY='{"inputVector":[0.1,0.2,0.3]}'
CHUNK_BODY='{"vectors":[],"reset":true}'
PERCEIVE_BODY='{"vector":[0.1,0.2,0.3]}'
SAMPLER_BODY='{"strategy":"manual"}'
OBSERVE_BODY='{"data":[0.1,0.2,0.3]}'
DIAG_BODY='{"universalInputSpace":[0.1,0.2,0.3]}'
ENGINE_PROC_BODY='{"vector":[0.1,0.2,0.3]}'
DIM_QUERY='?dimension=7680'
THRESH_QUERY='?threshold=0.5'
RUNTIME_PATCH='{"historyLimit":100}'
CONFIG_PATCH='{"matchThreshold":0.5}'
SOURCE_BODY='{"name":"smoke","type":"test","active":true,"region":{"offset":0,"length":1}}'
SIGNAL_BODY='{"type":"test","payload":{}}'
PUSH_BODY='{"sources":[]}'

if [[ "$SKIP_RE" == "0" ]]; then
  echo ""
  echo "━━━ Reality Engine  $RE_URL ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  echo "── Info & Health"
  check GET  "$RE_URL" "/"
  check GET  "$RE_URL" "/api"
  check GET  "$RE_URL" "/api/health"
  check GET  "$RE_URL" "/api/metrics"

  echo "── Configuration"
  check GET  "$RE_URL" "/api/config"
  check PUT  "$RE_URL" "/api/config/dimension${DIM_QUERY}"
  check PUT  "$RE_URL" "/api/config/threshold${THRESH_QUERY}"

  echo "── Runtime"
  check GET   "$RE_URL" "/api/runtime/metrics"
  check GET   "$RE_URL" "/api/runtime/vector-space"
  check GET   "$RE_URL" "/api/runtime/storage-footprint"
  check GET   "$RE_URL" "/api/runtime/options"
  check PATCH "$RE_URL" "/api/runtime/options" "$RUNTIME_PATCH"

  echo "── Vectors"
  check POST   "$RE_URL" "/api/vectors" "$VEC_BODY"
  check POST   "$RE_URL" "/api/vectors/search" "$SEARCH_BODY"
  check GET    "$RE_URL" "/api/vectors/smoke-vec"
  check DELETE "$RE_URL" "/api/vectors/smoke-vec"

  echo "── Sequences"
  check GET    "$RE_URL" "/api/sequences"
  check POST   "$RE_URL" "/api/sequences" "$SEQ_BODY"
  check GET    "$RE_URL" "/api/sequences/smoke-seq"
  check POST   "$RE_URL" "/api/sequences/smoke-seq/reset"
  check POST   "$RE_URL" "/api/sequences/smoke-seq/vectors" "$VEC_BODY"
  check DELETE "$RE_URL" "/api/sequences/smoke-seq"
  check POST   "$RE_URL" "/api/sequences/persist"

  echo "── Engine"
  check GET  "$RE_URL" "/api/engine/stats"
  check GET  "$RE_URL" "/api/engine/active"
  check GET  "$RE_URL" "/api/engine/history"
  check POST "$RE_URL" "/api/engine/process" "$ENGINE_PROC_BODY"
  check POST "$RE_URL" "/api/engine/reset"

  echo "── Machines"
  check GET    "$RE_URL" "/api/machines"
  check POST   "$RE_URL" "/api/machines" "$MACHINE_BODY"
  check GET    "$RE_URL" "/api/machines/smoke-machine"
  check PUT    "$RE_URL" "/api/machines/smoke-machine" "$MACHINE_BODY"
  check PATCH  "$RE_URL" "/api/machines/smoke-machine" '{"name":"smoke-patched"}'
  check POST   "$RE_URL" "/api/machines/smoke-machine/process" "$PROCESS_BODY"
  check POST   "$RE_URL" "/api/machines/smoke-machine/process-universal" "$UNIVERSAL_BODY"
  check POST   "$RE_URL" "/api/machines/smoke-machine/whatif" "$WHATIF_BODY"
  check POST   "$RE_URL" "/api/machines/smoke-machine/whatif-universal" "$UNIVERSAL_BODY"
  check POST   "$RE_URL" "/api/machines/process-universal/all" "$UNIVERSAL_BODY"
  check GET    "$RE_URL" "/api/machines/json/list"
  check GET    "$RE_URL" "/api/machines/smoke-machine/export"
  check GET    "$RE_URL" "/api/machines/smoke-machine/checkpoints"
  check POST   "$RE_URL" "/api/machines/smoke-machine/checkpoints" '{"label":"smoke-cp"}'
  check DELETE "$RE_URL" "/api/machines/smoke-machine"

  echo "── Machine Graph"
  check GET "$RE_URL" "/api/machine-graph"

  echo "── Perceptual Simulation"
  check POST "$RE_URL" "/api/perceptual-simulation/configure/chunk" "$CHUNK_BODY"
  check POST "$RE_URL" "/api/perceptual-simulation/configure/commit"
  check POST "$RE_URL" "/api/perceptual-simulation/start"
  check POST "$RE_URL" "/api/perceptual-simulation/stop"
  check POST "$RE_URL" "/api/perceptual-simulation/step"
  check POST "$RE_URL" "/api/perceptual-simulation/reset"
  check GET  "$RE_URL" "/api/perceptual-simulation/state"
  check GET  "$RE_URL" "/api/perceptual-simulation/history"

  echo "── Sampler"
  check POST "$RE_URL" "/api/sampler/start" "$SAMPLER_BODY"
  check POST "$RE_URL" "/api/sampler/sample" "$OBSERVE_BODY"
  check GET  "$RE_URL" "/api/sampler/stats"
  check POST "$RE_URL" "/api/sampler/stop"

  echo "── Perception"
  check POST "$RE_URL" "/api/perception/observe" "$OBSERVE_BODY"
  check POST "$RE_URL" "/api/perception/diagnostic" "$DIAG_BODY"
  check POST "$RE_URL" "/api/perceive" "$PERCEIVE_BODY"

  echo "── Governance"
  check GET "$RE_URL" "/api/governance/route?machineId=x&sequenceId=y&values=0"

  echo "── Demos"
  check GET "$RE_URL" "/api/demo/multi-step"
  check GET "$RE_URL" "/api/demo/data-center"
  check GET "$RE_URL" "/api/demo/kleene-star"

  echo "── SSE stream (HEAD only — no full consume)"
  curl -s --max-time 2 -o /dev/null -w "" "${RE_URL}/api/engine/stream" &
  SSE_PID=$!
  sleep 1
  kill $SSE_PID 2>/dev/null || true
  PASS=$((PASS + 1))
fi

if [[ "$SKIP_PE" == "0" ]]; then
  echo ""
  echo "━━━ Perception Engine  $PE_URL ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  echo "── Info & Health"
  check GET "$PE_URL" "/"
  check GET "$PE_URL" "/api/health"
  check GET "$PE_URL" "/api/state"

  echo "── Push Cycle"
  check POST "$PE_URL" "/api/push" "$PUSH_BODY"
  check GET  "$PE_URL" "/api/push/smoke-push-id"
  check POST "$PE_URL" "/api/auto/start" '{}'
  check POST "$PE_URL" "/api/auto/stop"

  echo "── Config & Reset"
  check PATCH "$PE_URL" "/api/config" "$CONFIG_PATCH"
  check POST  "$PE_URL" "/api/reset"

  echo "── Sources & Sensors"
  check GET    "$PE_URL" "/api/sources"
  check POST   "$PE_URL" "/api/sources" "$SOURCE_BODY"
  check PATCH  "$PE_URL" "/api/sources/smoke-source" '{"active":false}'
  check DELETE "$PE_URL" "/api/sources/smoke-source"
  check POST   "$PE_URL" "/api/sources/bootstrap-from-machines"
  check POST   "$PE_URL" "/api/sensors/smoke-sensor" '{"value":0.5}'

  echo "── Signals"
  check POST "$PE_URL" "/api/signals" "$SIGNAL_BODY"

  echo "── Machines proxy"
  check GET "$PE_URL" "/api/machines"

  echo "── Integrations"
  check GET  "$PE_URL" "/api/integrations/status"
  check POST "$PE_URL" "/api/integrations/completions" '{"sourceId":"x","prompt":"test"}'
  check GET  "$PE_URL" "/api/integrations/ollama/status"
  check POST "$PE_URL" "/api/integrations/ollama/dispatch" '{"model":"llama3","prompt":"test"}'
  check GET  "$PE_URL" "/api/integrations/openai/status"
  check POST "$PE_URL" "/api/integrations/openai/dispatch" '{"model":"gpt-4o","prompt":"test"}'
  check GET  "$PE_URL" "/api/integrations/acp/status"
  check POST "$PE_URL" "/api/integrations/acp/dispatch" '{"type":"test"}'
  check GET  "$PE_URL" "/api/integrations/healthkit/status"
  check POST "$PE_URL" "/api/integrations/healthkit/ingest" '{"samples":[]}'
  check GET  "$PE_URL" "/api/integrations/carekit/status"
  check POST "$PE_URL" "/api/integrations/carekit/ingest" '{"tasks":[]}'
  check GET  "$PE_URL" "/api/integrations/localai/status"
  check GET  "$PE_URL" "/api/integrations/localai/catalog"
  check POST "$PE_URL" "/api/integrations/localai/bootstrap" '{}'
  check POST "$PE_URL" "/api/integrations/localai/invoke" '{"model":"x","prompt":"test"}'

  echo "── Dispatch & Triggers"
  check GET   "$PE_URL" "/api/dispatch/ledger"
  check GET   "$PE_URL" "/api/dispatch/records/smoke-record"
  check PATCH "$PE_URL" "/api/dispatch/records/smoke-record" '{"status":"reviewed"}'
  check GET   "$PE_URL" "/api/triggers/status"

  echo "── MQTT"
  check GET "$PE_URL" "/api/mqtt/status"
  check GET "$PE_URL" "/api/mqtt/mappings"
  check PUT "$PE_URL" "/api/mqtt/mappings" '{"defaults":{},"mappings":[]}'

  echo "── Streaming (connection check only)"
  curl -s --max-time 2 -o /dev/null -w "" "${PE_URL}/api/events" &
  SSE2_PID=$!
  sleep 1
  kill $SSE2_PID 2>/dev/null || true
  PASS=$((PASS + 1))
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Results: ${PASS} passed, ${FAIL} failed"

if [[ ${#ERRORS[@]} -gt 0 ]]; then
  echo ""
  echo "Failures:"
  for e in "${ERRORS[@]}"; do
    echo "  ✗ $e"
  done
  echo ""
  exit 1
else
  echo ""
  echo "All routes present and responding."
  exit 0
fi
