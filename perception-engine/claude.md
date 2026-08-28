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

Governed by the canonical contract, which lives in `RealityEngine_CI` and
nowhere else:

    RealityEngine_CI/SURFACE_SPEC.md  §  Machine ingestion

Do not restate it here. It defines what ingesting a machine interns, how
`PE_SOURCE_BOOTSTRAP` gates it, and how those sources compose `ISRESeed(n)` —
and it governs this repository's implementation of all three.

Implemented in `PerceptionMain.seedSources`. This runtime read no flag at all
until #64, so `--pe-source-bootstrap=off` silently did nothing here (#63). It
still declares HealthKit sensors from `INTEGRATIONS_CONFIG` whether or not the
bridge is enabled — the open half of #63.
