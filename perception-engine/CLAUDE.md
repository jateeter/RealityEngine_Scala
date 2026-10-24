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
`ollamaSupportsStructuredOutput` (`PerceptionRoutes.scala`) with the `formatJson` body switch. A change to the list or the rule lands in all four repos together.

## Standing rules — authoritative in `../../RealityEngine_CI/docs/ENGINEERING_CONTRACT.md`

These apply here and are **not** restated in this file. The table is an index
to the contract, not a copy of it: it names every rule so you know what to look
up, and the contract's wording governs wherever the two differ.

| Rule | In short |
| --- | --- |
| Qualify every "registry" | Never the bare word — instance / machine / cesgen / arbitration / domain / semantic-bus / tag. |
| Regenerate a stale `<name>` registry, don't fail it | Each `<name>` registry is a view of the running system. A gate regenerates it and fails only on a disagreement that survives regeneration. |
| Verify a merge beyond the hosted checks | A green PR is not a verified PR; the hosted path cannot reach the integration points. Name what you could not exercise, and record what you noticed but did not chase. |
| _CI is the authority | Peripheral repos keep minimal CI that forces local validation; RealityEngine_CI verifies fixes against a live universe. Check its `docs/` before adding CI anywhere else. |
| Name it `CLAUDE.md` | Uppercase, always. On a case-insensitive filesystem `claude.md` is the same inode; dedupe on `st_ino`, never on a resolved path. |
| Never commit to main | Branch from `origin/main`, PR, verify, squash-merge, clean up. |
| Use bash, not zsh | Shell work runs in `/opt/homebrew/bin/bash` (5.x), not zsh or macOS `/bin/bash` 3.2: any loop, unquoted variable, glob or `set --` goes through it with `set -euo pipefail`, and you check the command's exit status, not the pipeline tail. |

Read the contract for the full text, the qualifier table, and the cleanup steps.
