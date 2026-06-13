#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REALITY_ENGINE_E2E_PORT="${REALITY_ENGINE_E2E_PORT:-3399}"
PERCEPTION_ENGINE_E2E_PORT="${PERCEPTION_ENGINE_E2E_PORT:-3401}"
VECTOR_DIMENSION="${VECTOR_DIMENSION:-7680}"
HEALTHKIT_BRIDGE_TOKEN="${HEALTHKIT_BRIDGE_TOKEN:-spezi-e2e-token}"
JAVA_OPTS="${JAVA_OPTS:--Xms64m -Xmx512m}"

# Reality Engine jar from the Scala runtime. Override only to test a different
# Scala RE build artifact; this e2e must not depend on another implementation.
REALITY_ENGINE_JAR="${REALITY_ENGINE_JAR:-../target/scala-2.13/reality-engine.jar}"

REALITY_PID=""
PERCEPTION_PID=""

cleanup() {
  if [ -n "$PERCEPTION_PID" ] && kill -0 "$PERCEPTION_PID" >/dev/null 2>&1; then
    kill "$PERCEPTION_PID" >/dev/null 2>&1 || true
    wait "$PERCEPTION_PID" >/dev/null 2>&1 || true
  fi
  if [ -n "$REALITY_PID" ] && kill -0 "$REALITY_PID" >/dev/null 2>&1; then
    kill "$REALITY_PID" >/dev/null 2>&1 || true
    wait "$REALITY_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_http() {
  local url="$1"
  local name="$2"
  local i
  for i in $(seq 1 40); do
    if curl -sf "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.25
  done
  echo "$name did not become ready at $url" >&2
  return 1
}

assert_status_code() {
  local actual="$1"
  local expected="$2"
  if [ "$actual" != "$expected" ]; then
    echo "expected HTTP $expected, got $actual" >&2
    exit 1
  fi
}

assert_registry() {
  python3 -c '
import json, sys
data = json.load(sys.stdin)
ids = {m.get("id") for m in data.get("sourceMappings", [])}
required = {
    "healthkit:HKCorrelationTypeIdentifierBloodPressure",
    "healthkit:HKWorkoutTypeIdentifierWorkout",
    "healthkit:HKCategoryTypeIdentifierSleepAnalysis",
}
missing = sorted(required - ids)
if data.get("loaded") is not True or missing:
    raise SystemExit(f"HealthKit Spezi registry mismatch missing={missing}: {data!r}")
'
}

assert_ingest() {
  local payload="$1"
  local expected_sensor="$2"
  local expected_mapping="$3"
  local expected_offset="$4"
  python3 -c '
import json, sys
data = json.loads(sys.argv[1])
expected_sensor = sys.argv[2]
expected_mapping = sys.argv[3]
expected_offset = int(sys.argv[4])
if data.get("success") is not True:
    raise SystemExit(f"ingest failed: {data!r}")
resolved = data.get("resolved", [])
if len(resolved) != 1:
    raise SystemExit(f"expected one resolved sample: {data!r}")
sample = resolved[0]
if sample.get("sensorId") != expected_sensor:
    raise SystemExit(f"sensor mismatch: {sample!r}")
if sample.get("sourceMappingId") != expected_mapping:
    raise SystemExit(f"mapping mismatch: {sample!r}")
if sample.get("region", {}).get("offset") != expected_offset:
    raise SystemExit(f"region mismatch: {sample!r}")
source = sample.get("source", {})
if source.get("lastValue") != sample.get("values"):
    raise SystemExit(f"source lastValue mismatch: {sample!r}")
' "$payload" "$expected_sensor" "$expected_mapping" "$expected_offset"
}

assert_merge_region() {
  local payload="$1"
  local offset="$2"
  local length="$3"
  python3 -c '
import json, sys
data = json.loads(sys.argv[1])
offset = int(sys.argv[2])
length = int(sys.argv[3])
for merge in data.get("step", {}).get("mergeBatch", []):
    region = merge.get("region", {})
    if region.get("offset") == offset and region.get("length") == length:
        break
else:
    raise SystemExit(f"expected merge region offset={offset} length={length}: {data!r}")
' "$payload" "$offset" "$length"
}

post_machine() {
  local payload="$1"
  curl -sf -X POST "http://localhost:${REALITY_ENGINE_E2E_PORT}/api/machines" \
    -H "Content-Type: application/json" \
    -d "$payload" >/dev/null
}

JAR="target/scala-2.13/perception-engine.jar"
[ -f "$JAR" ] || { echo "$JAR missing; run sbt assembly first" >&2; exit 1; }
[ -f "$REALITY_ENGINE_JAR" ] || { echo "$REALITY_ENGINE_JAR missing; run sbt assembly from the Scala repo root first" >&2; exit 1; }

echo "HealthKit Spezi bridge e2e (Scala)"
echo "  Reality Engine port:    $REALITY_ENGINE_E2E_PORT"
echo "  Perception Engine port: $PERCEPTION_ENGINE_E2E_PORT"
echo "  Reality Engine jar:     $REALITY_ENGINE_JAR"

PORT="$REALITY_ENGINE_E2E_PORT" \
  VECTOR_DIMENSION="$VECTOR_DIMENSION" \
  ALLOW_MISSING_QDRANT=true \
  java $JAVA_OPTS -jar "$REALITY_ENGINE_JAR" >/tmp/reality_engine_healthkit_spezi_scala_e2e.log 2>&1 &
REALITY_PID="$!"
wait_for_http "http://localhost:${REALITY_ENGINE_E2E_PORT}/api/health" "Reality Engine"

PORT="$PERCEPTION_ENGINE_E2E_PORT" \
  REALITY_ENGINE_URL="http://localhost:${REALITY_ENGINE_E2E_PORT}" \
  INTEGRATIONS_CONFIG="config/integrations.healthkit-spezi.example.json" \
  HEALTHKIT_BRIDGE_TOKEN="$HEALTHKIT_BRIDGE_TOKEN" \
  java $JAVA_OPTS -jar "$JAR" >/tmp/perception_engine_healthkit_spezi_scala_e2e.log 2>&1 &
PERCEPTION_PID="$!"
wait_for_http "http://localhost:${PERCEPTION_ENGINE_E2E_PORT}/api/health" "Perception Engine"

PE_URL="http://localhost:${PERCEPTION_ENGINE_E2E_PORT}"
curl -sf "$PE_URL/api/integrations/status" | assert_registry

bad_status="$(curl -s -o /tmp/healthkit_spezi_scala_unauthorized.json -w "%{http_code}" -X POST "$PE_URL/api/integrations/healthkit/ingest" \
  -H "Content-Type: application/json" \
  -d '{"bridgeId":"healthkit-ios-bridge","bridgeToken":"wrong","type":"HKCorrelationTypeIdentifierBloodPressure","values":[0.72,0.48,0.24,0.99]}')"
assert_status_code "$bad_status" "401"

bp_payload="$(curl -sf -X POST "$PE_URL/api/integrations/healthkit/ingest" -H "Content-Type: application/json" -d '{"bridgeId":"healthkit-ios-bridge","bridgeToken":"'"$HEALTHKIT_BRIDGE_TOKEN"'","type":"HKCorrelationTypeIdentifierBloodPressure","unit":"mm[Hg]","values":[0.72,0.48,0.24,0.99],"metadata":{"standard":"SpeziHealthKit","fhirCode":"85354-9"}}')"
assert_ingest "$bp_payload" "healthkit.blood-pressure" "healthkit:HKCorrelationTypeIdentifierBloodPressure" 4320

exercise_payload="$(curl -sf -X POST "$PE_URL/api/integrations/healthkit/ingest" -H "Content-Type: application/json" -d '{"bridgeId":"healthkit-ios-bridge","bridgeToken":"'"$HEALTHKIT_BRIDGE_TOKEN"'","type":"HKWorkoutTypeIdentifierWorkout","unit":"normalized","values":[0.65,0.58,0.42,0.97],"metadata":{"standard":"SpeziHealthKit","fhirCode":"55411-3"}}')"
assert_ingest "$exercise_payload" "healthkit.exercise" "healthkit:HKWorkoutTypeIdentifierWorkout" 4330

sleep_payload="$(curl -sf -X POST "$PE_URL/api/integrations/healthkit/ingest" -H "Content-Type: application/json" -d '{"bridgeId":"healthkit-ios-bridge","bridgeToken":"'"$HEALTHKIT_BRIDGE_TOKEN"'","type":"HKCategoryTypeIdentifierSleepAnalysis","unit":"normalized","values":[0.82,0.12,0.18,0.96],"metadata":{"standard":"SpeziHealthKit","fhirCode":"93832-4"}}')"
assert_ingest "$sleep_payload" "healthkit.sleep" "healthkit:HKCategoryTypeIdentifierSleepAnalysis" 4340

bp_machine='{"version":"1.0.0","machine":{"name":"HealthKit Spezi BP Consumer","description":"Consumes normalized SpeziHealthKit blood pressure source","arbiterRule":"PASSTHROUGH","perceptualMapping":{"input":{"offset":4320,"length":4},"output":{"offset":4350,"length":2}},"sequences":[{"id":"healthkit-spezi-bp-seq","name":"BP signal accepted","vectors":[{"id":"healthkit-spezi-bp-ready","elements":[{"value":0.72,"comparatorType":"equals"},{"value":0.48,"comparatorType":"equals"},{"value":0.24,"comparatorType":"equals"},{"value":0.99,"comparatorType":"equals"}],"isInitial":true,"outputVectors":[{"id":"healthkit-spezi-bp-out","vector":[1,0]}]}]}]}}'
exercise_machine='{"version":"1.0.0","machine":{"name":"HealthKit Spezi Exercise Consumer","description":"Consumes normalized SpeziHealthKit exercise source","arbiterRule":"PASSTHROUGH","perceptualMapping":{"input":{"offset":4330,"length":4},"output":{"offset":4352,"length":2}},"sequences":[{"id":"healthkit-spezi-exercise-seq","name":"Exercise signal accepted","vectors":[{"id":"healthkit-spezi-exercise-ready","elements":[{"value":0.65,"comparatorType":"equals"},{"value":0.58,"comparatorType":"equals"},{"value":0.42,"comparatorType":"equals"},{"value":0.97,"comparatorType":"equals"}],"isInitial":true,"outputVectors":[{"id":"healthkit-spezi-exercise-out","vector":[1,0]}]}]}]}}'
sleep_machine='{"version":"1.0.0","machine":{"name":"HealthKit Spezi Sleep Consumer","description":"Consumes normalized SpeziHealthKit sleep source","arbiterRule":"PASSTHROUGH","perceptualMapping":{"input":{"offset":4340,"length":4},"output":{"offset":4354,"length":2}},"sequences":[{"id":"healthkit-spezi-sleep-seq","name":"Sleep signal accepted","vectors":[{"id":"healthkit-spezi-sleep-ready","elements":[{"value":0.82,"comparatorType":"equals"},{"value":0.12,"comparatorType":"equals"},{"value":0.18,"comparatorType":"equals"},{"value":0.96,"comparatorType":"equals"}],"isInitial":true,"outputVectors":[{"id":"healthkit-spezi-sleep-out","vector":[1,0]}]}]}]}}'

post_machine "$bp_machine"
post_machine "$exercise_machine"
post_machine "$sleep_machine"

push_payload="$(curl -sf -X POST "$PE_URL/api/push" -H "Content-Type: application/json" -d '{"compact":true}')"
assert_merge_region "$push_payload" 4350 2
assert_merge_region "$push_payload" 4352 2
assert_merge_region "$push_payload" 4354 2

echo "HealthKit Spezi bridge e2e tests passed (Scala)"
