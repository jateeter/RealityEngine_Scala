# RealityEngine Scala — Perception Engine

Scala Perception Engine (PE): assembles perceptual vectors from sensor sources and pushes them to the Reality Engine.

## Build and Run

```bash
sbt compile
sbt run
# or via Makefile
make assembly
make e2e-healthkit-spezi
```

## HealthKit / Spezi Bridge

The PE exposes `/api/integrations/healthkit/ingest` and `/api/integrations/carekit/ingest` for Apple-platform sensor ingest via a Stanford SpeziHealthKit iOS bridge.

- [docs/HEALTHKIT_SPEZI_BRIDGE.md](docs/HEALTHKIT_SPEZI_BRIDGE.md) — bridge contract, iOS responsibilities, payload shape, FHIR metadata
- [config/integrations.healthkit-spezi.example.json](config/integrations.healthkit-spezi.example.json) — PE source mapping registry for BP / exercise / sleep
- [config/healthkit-spezi-app.example.env](config/healthkit-spezi-app.example.env) — iOS app configuration template
- [config/healthkit-spezi-payloads.example.json](config/healthkit-spezi-payloads.example.json) — canonical ingest payload examples
- [tests/e2e_healthkit_spezi.sh](tests/e2e_healthkit_spezi.sh) — service-boundary e2e validation

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `HEALTHKIT_BRIDGE_ID` | `healthkit-ios-bridge` | Expected bridge identity in ingest payloads |
| `HEALTHKIT_DEFAULT_SOURCE_MAPPING_ID` | `healthkit-activity` | Fallback source mapping when none specified |
| `HEALTHKIT_BRIDGE_TOKEN` | unset | Shared token required in ingest payloads (unset = no auth) |
| `CAREKIT_BRIDGE_ID` | `carekit-ios-bridge` | Expected CareKit bridge identity |
| `CAREKIT_DEFAULT_SOURCE_MAPPING_ID` | `carekit-task` | Default CareKit source mapping |
| `CAREKIT_BRIDGE_TOKEN` | unset | Shared token for CareKit ingest |
| `INTEGRATIONS_CONFIG` | `config/integrations.json` | Path to PE source mapping registry |
| `ACP_ENABLED` | `true` | Enable ACP/OpenClaw adapter metadata in PE status |
| `ACP_GATEWAY_URL` / `OPENCLAW_GATEWAY_URL` | `ws://127.0.0.1:18789` | OpenClaw gateway URL recorded for ACP handoff |
| `ACP_SESSION_KEY` / `OPENCLAW_ACP_SESSION` | `agent:main:main` | OpenClaw gateway session key for example ACP handoff |
| `ACP_TARGET_AGENT` | `openclaw` | Default OpenClaw target agent for ACP handoff |
| `ACP_COMPLETION_SOURCE_MAPPING_ID` | `acp-openclaw-completion` | Default source mapping for ACP/OpenClaw completion commits |
