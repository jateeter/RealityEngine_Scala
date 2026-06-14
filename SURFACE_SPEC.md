# RealityEngine Canonical Surface Specification

**Version:** 1.1.0  
**Date:** 2026-05-22  
**Scope:** All production runtimes — CPP, LSP, Scala

This document is the authoritative HTTP API contract for the RealityEngine platform. Every route listed here must be implemented by every runtime. The Manager frontend is built against this surface and performs no runtime-specific branching.

Runtimes: `CPP` = RealityEngine_CPP · `LSP` = RealityEngine_LSP · `Scala` = RealityEngine_Scala

Consumers/tooling: `Manager` = RealityEngine_Manager · `CI` = RealityEngine_CI

---

## Reality Engine (RE) Surface

Served by `reality_engine_server` (CPP), `reality-service` (LSP), `Routes` (Scala).  
Default ports: Scala 5001 · CPP 5301 · LSP 5601

### Info & Health

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/` | ✓ | ✓ | ✓ |
| GET | `/api` | ✓ | ✓ | ✓ |
| GET | `/api/health` | ✓ | ✓ | ✓ |
| GET | `/api/metrics` | ✓ | ✓ | ✓ |

### Configuration

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/config` | ✓ | ✓ | ✓ |
| PUT | `/api/config/dimension` | ✓ | ✓ | ✓ |
| PUT | `/api/config/threshold` | ✓ | ✓ | ✓ |

### Runtime Introspection

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/runtime/metrics` | ✓ | ✓ | ✓ |
| GET | `/api/runtime/vector-space` | ✓ | ✓ | ✓ |
| GET | `/api/runtime/storage-footprint` | ✓ | ✓ | ✓ |
| GET | `/api/runtime/options` | ✓ | ✓ | ✓ |
| PATCH | `/api/runtime/options` | ✓ | ✓ | ✓ |

### Vectors

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| POST | `/api/vectors/search` | ✓ | ✓ | ✓ |
| POST | `/api/vectors` | ✓ | ✓ | ✓ |
| GET | `/api/vectors/:id` | ✓ | ✓ | ✓ |
| DELETE | `/api/vectors/:id` | ✓ | ✓ | ✓ |

### Sequences

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/sequences` | ✓ | ✓ | ✓ |
| POST | `/api/sequences` | ✓ | ✓ | ✓ |
| GET | `/api/sequences/:id` | ✓ | ✓ | ✓ |
| DELETE | `/api/sequences/:id` | ✓ | ✓ | ✓ |
| POST | `/api/sequences/:id/reset` | ✓ | ✓ | ✓ |
| POST | `/api/sequences/:id/vectors` | ✓ | ✓ | ✓ |
| POST | `/api/sequences/persist` | ✓ | ✓ | ✓ |

### Engine

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/engine/stats` | ✓ | ✓ | ✓ |
| GET | `/api/engine/active` | ✓ | ✓ | ✓ |
| GET | `/api/engine/history` | ✓ | ✓ | ✓ |
| POST | `/api/engine/process` | ✓ | ✓ | ✓ |
| POST | `/api/engine/reset` | ✓ | ✓ | ✓ |

### Machines

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/machines` | ✓ | ✓ | ✓ |
| POST | `/api/machines` | ✓ | ✓ | ✓ |
| GET | `/api/machines/:id` | ✓ | ✓ | ✓ |
| PUT | `/api/machines/:id` | ✓ | ✓ | ✓ |
| PATCH | `/api/machines/:id` | ✓ | ✓ | ✓ |
| DELETE | `/api/machines/:id` | ✓ | ✓ | ✓ |
| POST | `/api/machines/:id/process` | ✓ | ✓ | ✓ |
| POST | `/api/machines/:id/process-universal` | ✓ | ✓ | ✓ |
| POST | `/api/machines/:id/whatif` | ✓ | ✓ | ✓ |
| POST | `/api/machines/:id/whatif-universal` | ✓ | ✓ | ✓ |
| POST | `/api/machines/process-universal/all` | ✓ | ✓ | ✓ |
| GET | `/api/machines/json/list` | ✓ | ✓ | ✓ |
| GET | `/api/machines/json/:name` | ✓ | ✓ | ✓ |
| POST | `/api/machines/json/import` | ✓ | ✓ | ✓ |
| GET | `/api/machines/:id/export` | ✓ | ✓ | ✓ |
| GET | `/api/machines/:id/checkpoints` | ✓ | ✓ | ✓ |
| POST | `/api/machines/:id/checkpoints` | ✓ | ✓ | ✓ |
| POST | `/api/machines/:machineId/checkpoints/:cpId/restore` | ✓ | ✓ | ✓ |
| DELETE | `/api/machines/:machineId/checkpoints/:cpId` | ✓ | ✓ | ✓ |

### Machine Graph

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/machine-graph` | ✓ | ✓ | ✓ |

### Perceptual Simulation

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| POST | `/api/perceptual-simulation/configure/chunk` | ✓ | ✓ | ✓ |
| POST | `/api/perceptual-simulation/configure/commit` | ✓ | ✓ | ✓ |
| POST | `/api/perceptual-simulation/start` | ✓ | ✓ | ✓ |
| POST | `/api/perceptual-simulation/stop` | ✓ | ✓ | ✓ |
| POST | `/api/perceptual-simulation/step` | ✓ | ✓ | ✓ |
| POST | `/api/perceptual-simulation/reset` | ✓ | ✓ | ✓ |
| GET | `/api/perceptual-simulation/state` | ✓ | ✓ | ✓ |
| GET | `/api/perceptual-simulation/history` | ✓ | ✓ | ✓ |

### Sampler

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| POST | `/api/sampler/start` | ✓ | ✓ | ✓ |
| POST | `/api/sampler/stop` | ✓ | ✓ | ✓ |
| POST | `/api/sampler/sample` | ✓ | ✓ | ✓ |
| GET | `/api/sampler/stats` | ✓ | ✓ | ✓ |

### Perception

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| POST | `/api/perception/observe` | ✓ | ✓ | ✓ |
| POST | `/api/perception/diagnostic` | ✓ | ✓ | ✓ |
| POST | `/api/perceive` | ✓ | ✓ | ✓ |

### Governance

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/governance/route` | ✓ | ✓ | ✓ |

### Demos

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/demo/multi-step` | ✓ | ✓ | ✓ |
| GET | `/api/demo/data-center` | ✓ | ✓ | ✓ |
| GET | `/api/demo/kleene-star` | ✓ | ✓ | ✓ |

### Streaming

| Protocol | Path | CPP | LSP | Scala |
|----------|------|-----|-----|-------|
| SSE | `/api/engine/stream` | ✓ | ✓ | ✓ |

---

## Perception Engine (PE) Surface

Served by `perception_engine_server` (CPP), `perception-service` (LSP), `PerceptionRoutes` (Scala).  
Default ports: Scala 5000 · CPP 5300 · LSP 5600

### Info & Health

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/` | ✓ | ✓ | ✓ |
| GET | `/api/health` | ✓ | ✓ | ✓ |
| GET | `/api/state` | ✓ | ✓ | ✓ |

### Push Cycle

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| POST | `/api/push` | ✓ | ✓ | ✓ |
| GET | `/api/push/:id` | ✓ | ✓ | ✓ |
| POST | `/api/auto/start` | ✓ | ✓ | ✓ |
| POST | `/api/auto/stop` | ✓ | ✓ | ✓ |

### Configuration & Reset

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| PATCH | `/api/config` | ✓ | ✓ | ✓ |
| POST | `/api/reset` | ✓ | ✓ | ✓ |

### Sources & Sensors

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/sources` | ✓ | ✓ | ✓ |
| POST | `/api/sources` | ✓ | ✓ | ✓ |
| PATCH | `/api/sources/:id` | ✓ | ✓ | ✓ |
| DELETE | `/api/sources/:id` | ✓ | ✓ | ✓ |
| POST | `/api/sources/bootstrap-from-machines` | ✓ | ✓ | ✓ |
| POST | `/api/sensors/:sensorId` | ✓ | ✓ | ✓ |

### Signals

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| POST | `/api/signals` | ✓ | ✓ | ✓ |

### Machines Proxy

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/machines` | ✓ | ✓ | ✓ |

### Integrations

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/integrations/status` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/completions` | ✓ | ✓ | ✓ |
| GET | `/api/integrations/ollama/status` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/ollama/dispatch` | ✓ | ✓ | ✓ |
| GET | `/api/integrations/openai/status` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/openai/dispatch` | ✓ | ✓ | ✓ |
| GET | `/api/integrations/acp/status` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/acp/dispatch` | ✓ | ✓ | ✓ |
| GET | `/api/integrations/healthkit/status` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/healthkit/ingest` | ✓ | ✓ | ✓ |
| GET | `/api/integrations/carekit/status` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/carekit/ingest` | ✓ | ✓ | ✓ |
| GET | `/api/integrations/localai/status` | ✓ | ✓ | ✓ |
| GET | `/api/integrations/localai/catalog` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/localai/bootstrap` | ✓ | ✓ | ✓ |
| POST | `/api/integrations/localai/invoke` | ✓ | ✓ | ✓ |

### Dispatch & Triggers

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/dispatch/ledger` | ✓ | ✓ | ✓ |
| GET | `/api/dispatch/records/:id` | ✓ | ✓ | ✓ |
| PATCH | `/api/dispatch/records/:id` | ✓ | ✓ | ✓ |
| GET | `/api/triggers/status` | ✓ | ✓ | ✓ |

### MQTT Bridge

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/mqtt/status` | ✓ | ✓ | ✓ |
| GET | `/api/mqtt/mappings` | ✓ | ✓ | ✓ |
| PUT | `/api/mqtt/mappings` | ✓ | ✓ | ✓ |
| POST | `/api/mqtt/enable` | ✓ | ✓ | ✓ |
| POST | `/api/mqtt/disable` | ✓ | ✓ | ✓ |

### Streaming

| Protocol | Path | CPP | LSP | Scala |
|----------|------|-----|-----|-------|
| SSE | `/api/events` | ✓ | ✓ | ✓ |
| WebSocket | `/ws` | ✓ | ✓ | ✓ |

---

## Gap Register

### Resolved gaps (v1.1.0)

| Runtime | Route | Resolution |
|---------|-------|------------|
| LSP RE | 8 routes (vectors/search, vectors, vectors/:id×2, sequences×4) | Promoted from dead `reality-routes` block into active route table |
| Scala RE | `GET /` | Added bare root handler outside `pathPrefix("api")` via outer `concat` |
| Scala PE | `GET /` | Confirmed present at `pathEndOrSingleSlash` outside any prefix |
| All | HealthKit/CareKit status+ingest semantic shape | Unified (see Semantic Contracts section below) |

### Open gaps

None. All routes listed in this spec are implemented by all three runtimes.

---

## Response Shape Conventions

All runtimes must conform to these envelope shapes. Deviations are bugs in the runtime, not workarounds to implement in the Manager.

### Success envelope
```json
{ "success": true, "<resource>": { ... } }
```

### Error envelope
```json
{ "error": "<message>" }
```
HTTP status: 400 for bad input, 404 for not found, 500 for runtime error.

### Health response
```json
{ "status": "healthy", "timestamp": 1748000000000, "version": "x.y.z" }
```

### Streaming events (SSE and WebSocket)

Both SSE (`/api/engine/stream`, `/api/events`) and WebSocket (`/ws`) deliver newline-delimited JSON event objects. Each event has a `type` field:

**RE stream** (`/api/engine/stream`):
- `{ "type": "step-result", "step": { ... } }` — emitted after every `POST /api/perceive`

**PE stream** (`/api/events` and `/ws`):
- `{ "type": "state-update", ... }`
- `{ "type": "push-result", ... }`
- `{ "type": "agent.completion.received", ... }`
- `{ "type": "carekit.ingest", ... }`
- `{ "type": "mqtt-ingest", ... }`
- `{ "type": "dispatch-updated", ... }`

SSE framing: `data: <json>\n\n` with `: keepalive\n\n` every 15 s.  
WebSocket framing: RFC 6455 text frames; ping frames sent every 15 s on idle.

---

## Acceptance Smoke Test

A conformance script must make one request to each route listed in this spec against a running runtime instance and verify:
- HTTP status is not 404 (route exists)
- HTTP status is not 500 (handler is wired)
- Response body is valid JSON

Script location: `scripts/smoke-test.sh` (accepts `--target <url>` for RE and `--pe-target <url>` for PE).

---

## Semantic Contracts

Route parity (a route exists) is necessary but not sufficient. The following contracts specify the exact JSON fields each endpoint must emit and accept. Deviations are bugs in the runtime.

### HealthKit Integration

#### `GET /api/integrations/healthkit/status`

All runtimes must return:

```json
{
  "bridgeId":              "healthkit-ios-bridge",
  "enabled":               true,
  "tokenConfigured":       false,
  "nativeAppRequired":     true,
  "nativeWorkOutsideRepo": true,
  "registryKey":           "healthkit:<typeIdentifier>",
  "statusEndpoint":        "/api/integrations/healthkit/status",
  "ingestEndpoint":        "/api/integrations/healthkit/ingest",
  "contract": {
    "transport":    "https",
    "singleSample": ["type", "value", "sourceName"],
    "batchSamples": ["bridgeId", "samples[]"],
    "auth":         "none"
  }
}
```

`tokenConfigured` is `true` when `HEALTHKIT_BRIDGE_TOKEN` is set; `auth` becomes `"bridgeToken"` in that case.  
Field `enabled` reflects `HEALTHKIT_ENABLED` env (Scala) or `true` always (CPP/LSP — routes are always active).

**Removed fields (no longer emitted):** `tokenRequired`, `configured`, `bridgeEndpoint`.

#### `POST /api/integrations/healthkit/ingest`

**Request — single sample (flat body):**
```json
{ "type": "HKQuantityTypeIdentifierHeartRate", "value": 72.0, "sourceName": "Apple Watch" }
```

**Request — batch:**
```json
{ "bridgeId": "healthkit-ios-bridge", "bridgeToken": "<token>", "samples": [ { "type": "...", "value": 72.0 } ] }
```

**Token auth rules (all runtimes):**
- If `HEALTHKIT_BRIDGE_TOKEN` is unset: ingest is accepted with no auth check (no-token / dev mode).
- If set: body must contain `bridgeToken` (primary) or `token` (secondary alias). Bearer `Authorization` header is **not** accepted.
- Missing / wrong token → `401 Unauthorized`.

**Mapping lookup order (two-level registry):**
1. Explicit `sourceMappingId` or `mappingId` field in the sample, if present.
2. `healthkit:<type>:<sourceName>` if `sourceName` is non-empty.
3. `healthkit:<type>` (generic fallback).

**Response:**
```json
{
  "success":  true,
  "bridgeId": "healthkit-ios-bridge",
  "resolved": [ { "resolved": true, "sensorId": "...", "type": "...", "sourceMappingId": "...", "values": [...], "ttlMs": 3600000 } ],
  "unmapped": []
}
```

HTTP status: `200` (all resolved) · `207` (mixed) · `400` (all unmapped).

**Unmapped entry shape:**
```json
{ "unmapped": true, "type": "...", "sourceName": "...", "reason": "no registry mapping (declare healthkit:<type>[:<sourceName>])" }
```

---

### CareKit Integration

#### `GET /api/integrations/carekit/status`

```json
{
  "bridgeId":               "carekit-ios-bridge",
  "enabled":                true,
  "defaultSourceMappingId": "carekit-activity",
  "tokenConfigured":        false,
  "nativeAppRequired":      true,
  "nativeWorkOutsideRepo":  true,
  "registryKey":            "carekit:<sampleType>",
  "statusEndpoint":         "/api/integrations/carekit/status",
  "ingestEndpoint":         "/api/integrations/carekit/ingest",
  "contract": {
    "transport":    "https",
    "singleSample": ["bridgeId", "sampleType", "sourceMappingId", "values"],
    "batchSamples": ["bridgeId", "samples[]"],
    "auth":         "external-transport"
  }
}
```

`auth` becomes `"bridgeToken"` when `CAREKIT_BRIDGE_TOKEN` is set.

**Removed fields:** `tokenRequired`, `configured`, `bridgeEndpoint`.

#### `POST /api/integrations/carekit/ingest`

Same token auth rules as HealthKit. Same no-token / dev mode behavior.

Top-level body fields are merged into each batch sample (sample keys win); `samples`, `bridgeToken`, `token` are stripped from the merge.

**Response:**
```json
{
  "success":  true,
  "bridgeId": "carekit-ios-bridge",
  "results": [ { "success": true, "sampleType": "...", "sourceMappingId": "...", "sensorId": "...", "taskId": null, "carePlanId": null } ]
}
```

HTTP status: `200` (all ok) · `207` (partial failures).

---

## Out of Scope

The following routes appeared in the locked historical RealityEngine_AI surface
that has been replaced by Scala, but are not part of the canonical surface and
must not be implemented in CPP, LSP, or Scala:

- `GET /api/mqtt/example`
- `GET /api/integrations/healthkit/example`
- `GET /api/integrations/carekit/example`
- `POST /api/triggers/replay/:dispatchId`
- `GET /api/logs/ingest` (Loki-specific, Manager visualizer backend only)
- `GET /api/viz/*` (Manager visualizer backend only)
