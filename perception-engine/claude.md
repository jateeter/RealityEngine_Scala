# RealityEngine_Scala PE Guidance

This directory contains the standalone Scala Perception Engine.

- `src/PerceptionMain.scala`: PE entrypoint.
- `src/api/`: PE routes and websocket support.
- `src/engine/`: PE behavior.
- `src/store/`: source store.
- `src/mqtt/`: MQTT bridge.
- Keep PE source behavior aligned with C++, LSP, and Manager TypeScript PE expectations.
- Use Metals and the local Makefile targets for compile/test/e2e.

## Machine ingestion

**Interning a machine's test source is part of ingesting the machine**, and it
happens by default. `PerceptionMain` calls `seedSources` at boot — a machine's
`inputSequences` become a test source over that machine's own input region —
unless `PE_SOURCE_BOOTSTRAP` is `off`/`0`/`false`/`no`.

Those sources are the material the **ISRE seed queue** is composed from: the
seed at step n is the merge of every active test source's n-th vector, each
written into its own machine's region. A runtime holding a corpus but no test
sources has nothing to be presented with.

This runtime seeded unconditionally and read no flag at all, so
`--pe-source-bootstrap=off` silently did nothing here while C++ and LSP honoured
it — three runtimes, three post-boot source sets, before any comparison began
(#63, fixed in #64). Seeding by default was already right; the opt-out is what
was missing. `off` is what a harness driving its own per-iteration bootstrap
wants (`test-corpus-parity-loop.sh`).
`POST /api/sources/bootstrap-from-machines` is unaffected either way.

Machine-derived test sources are the one source kind that does not wait for an
external integration to register — they arrive with the machines. MQTT, ACP,
MCP, HealthKit and localAI are external and register on their own terms. Note
this runtime still declares HealthKit sensor sources from `INTEGRATIONS_CONFIG`
whether or not the bridge is enabled, which is the open half of #63.

Master contract: `RealityEngine_CI/SURFACE_SPEC.md`, "Machine ingestion".

