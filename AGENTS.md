# Codex Guidance: RealityEngine_Scala

Read `claude.md` for the current codebase map and parity context.

## Role

This repo contains the Scala/Akka Reality Engine and standalone Scala Perception Engine. It is a parity target against C++ and LSP.

## Development Rules

- Keep RE routes, PE routes, JSON codecs, machine loading, actor state, and source store behavior aligned with C++ and LSP.
- Treat generated machine bindings as derived code unless the user asks for generated updates.
- Use Metals/sbt conventions and existing Makefile targets inside `perception-engine`.
- Be careful with smoke tests that mutate active state before parity checks.

## Bug Triage

- For endpoint drift, inspect route code and JSON codecs together.
- For missing machines/sources, check loader path, source bootstrap, and active engine state.
- For PE issues, inspect `perception-engine/src/api`, `src/store`, `src/engine`, and `src/mqtt` before changing tests.

## Verification

Common commands:

```bash
sbt test
cd perception-engine && make compile
cd perception-engine && make test
cd perception-engine && make e2e-healthkit-spezi
```

## Artifact Hygiene

Do not commit `target/`, generated runtime state, local data, or logs unless explicitly requested.

