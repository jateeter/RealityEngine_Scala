# RealityEngine_Scala PE Guidance

This directory contains the standalone Scala Perception Engine.

- `src/main/scala/com/realityengine/perception/PerceptionMain.scala`: PE entrypoint.
- Under that package: `api/` (PE routes and websocket support), `engine/` (PE behavior), `store/` (source store), `mqtt/` (MQTT bridge), `healthkit/` (HealthKit ingest), `triggers/` (trigger dispatch), and `models/`, `metrics/`, `logging/`.
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

## Ollama `format` (structured output) — three-engine parity

Whether a native Ollama dispatch sends `format` depends on the model: Ollama
refuses it for some (`nemotron-3-nano` → `structured output is unavailable`).
The C++, LSP and Scala PEs and the Manager TypeScript PE must send or omit it
**identically** for every model — a divergence reads as an engine defect in the
parity sweep. The rule and the model list are canonical in
`RealityEngine_CI/docs/OLLAMA_INTEGRATION.md` § "Structured output"; here it is
`ollamaSupportsStructuredOutput` (`PerceptionRoutes.scala`) with the `formatJson` body switch. When `format` is omitted the engine instead
puts the mapping's required keys and an example shape in the system prompt
(`completionContractPrompt`, byte-identical text in all four) and, for these
models, sends `think: false`. A change to the list or the rule lands in all four
repos together.

## Standing rules

The standing rules are in
`../../RealityEngine_CI/docs/ENGINEERING_CONTRACT.md`, which is
authoritative. They are not listed or summarised here, so this file never
falls behind the contract. Read it before changing anything here.
