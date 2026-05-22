# RealityEngine Canonical Surface Specification

**Version:** 1.0.0  
**Date:** 2026-05-22  
**Scope:** All production runtimes — CPP, LSP, Scala

This document is the authoritative HTTP API contract for the RealityEngine platform. Every route listed here must be implemented by every runtime. The Manager frontend is built against this surface and performs no runtime-specific branching.

Runtimes: `CPP` = RealityEngine_CPP · `LSP` = RealityEngine_LSP · `Scala` = RealityEngine_Scala

---

## Reality Engine (RE) Surface

Served by `reality_engine_server` (CPP), `reality-service` (LSP), `Routes` (Scala).  
Default ports: CPP 3000 · LSP 3299 · Scala 5001

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
| POST | `/api/vectors/search` | ✓ | **GAP** | ✓ |
| POST | `/api/vectors` | ✓ | **GAP** | ✓ |
| GET | `/api/vectors/:id` | ✓ | **GAP** | ✓ |
| DELETE | `/api/vectors/:id` | ✓ | **GAP** | ✓ |

### Sequences

| Method | Path | CPP | LSP | Scala |
|--------|------|-----|-----|-------|
| GET | `/api/sequences` | ✓ | **GAP** | ✓ |
| POST | `/api/sequences` | ✓ | **GAP** | ✓ |
| GET | `/api/sequences/:id` | ✓ | **GAP** | ✓ |
| DELETE | `/api/sequences/:id` | ✓ | ✓ | ✓ |
| POST | `/api/sequences/:id/reset` | ✓ | ✓ | ✓ |
| POST | `/api/sequences/:id/vectors` | ✓ | ✓ | ✓ |
| POST | `/api/sequences/persist` | ✓ | **GAP** | ✓ |

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
Default ports: CPP 3003 · LSP 4000 · Scala 5000

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

### Streaming

| Protocol | Path | CPP | LSP | Scala |
|----------|------|-----|-----|-------|
| SSE | `/api/events` | ✓ | ✓ | ✓ |
| WebSocket | `/ws` | ✓ | ✓ | ✓ |

---

## Gap Register

All gaps must be resolved before Phase 2 is considered complete. The Manager is built against the full surface above with no workarounds for any gap.

### LSP RE — 8 routes (Priority: High)

All eight routes exist in the dead first `reality-routes` function in `src/reality-service.lisp` (lines 917–989) but are absent from the active second `reality-routes` function (line 1280+) that is actually wired to the server via `start-reality-service`. The implementations are correct — they simply need to be moved into the active route list.

| Method | Path | File | Dead-block line |
|--------|------|------|-----------------|
| POST | `/api/vectors/search` | reality-service.lisp | 917 |
| POST | `/api/vectors` | reality-service.lisp | 937 |
| GET | `/api/vectors/:id` | reality-service.lisp | 946 |
| DELETE | `/api/vectors/:id` | reality-service.lisp | 949 |
| GET | `/api/sequences` | reality-service.lisp | 959 |
| POST | `/api/sequences` | reality-service.lisp | 967 |
| GET | `/api/sequences/:id` | reality-service.lisp | 975 |
| POST | `/api/sequences/persist` | reality-service.lisp | 956 |

**Fix:** Copy the 8 route forms from the dead block into the active `reality-routes` list. The dead block itself can remain as historical reference or be removed.

### Scala PE — 1 route (Priority: Low)

| Method | Path | Note |
|--------|------|------|
| GET | `/` | Bare root handler. CPP and LSP PE both return `{ name, version, status }`. Scala PE has `/api/health` but no bare root. |

### Scala RE — 1 route (Priority: Low)

| Method | Path | Note |
|--------|------|------|
| GET | `/` | Bare root handler. CPP and LSP RE return `{ name, version, status }`. Scala RE has `/api` (pathEndOrSingleSlash inside pathPrefix("api")) but no bare `/`. |

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

## Out of Scope

The following routes appeared in RealityEngine_AI but are not part of the canonical surface and must not be implemented in CPP, LSP, Scala, or the Manager:

- `POST /api/mqtt/enable`
- `POST /api/mqtt/disable`
- `GET /api/mqtt/example`
- `GET /api/integrations/healthkit/example`
- `GET /api/integrations/carekit/example`
- `POST /api/triggers/replay/:dispatchId`
- `GET /api/logs/ingest` (Loki-specific, Manager visualizer backend only)
- `GET /api/viz/*` (Manager visualizer backend only)
