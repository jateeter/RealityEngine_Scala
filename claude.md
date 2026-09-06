# RealityEngine_Scala Guidance

Last reviewed: 2026-06-22

See `/Users/johnt/workspace/GitHub/claude.md` for the integrated application map. Update both this file and the root map when Scala RE, Scala PE, actor behavior, or parity responsibilities change.

## Role

This repo contains the Scala/Akka Reality Engine and a standalone Scala Perception Engine. It participates as `scala-1` in multi-engine runs and is a parity target against C++ and LSP.

## Codebase Map

- `src/main/scala/com/realityengine/Main.scala`: RE entrypoint.
- `src/main/scala/com/realityengine/api/`: RE HTTP routes and JSON contracts.
- `src/main/scala/com/realityengine/engine/`: core RE/PE model, simulation, sampling, and perception logic.
- `src/main/scala/com/realityengine/actors/`: machine actor execution.
- `src/main/scala/com/realityengine/services/`: vector store, machine loader, dispatch binding, and CES coverage.
- `src/main/scala/com/realityengine/models/`: machine, vector, perceptual-space, output, and CES domain types.
- `src/main/scala/com/realityengine/generated/`: generated machine bindings.
- `perception-engine/src/`: standalone Scala PE.
- `perception-engine/src/api/`: PE routes and websocket broadcast actor.
- `perception-engine/src/store/`: PE source persistence.
- `perception-engine/src/mqtt/`: MQTT bridge.
- `src/test/`, `perception-engine/tests/`: test coverage.

## Building

**Builds are controlled through `RealityEngine_CI`, not from here.** This repo is
an independent git repository, not a subproject of CI or of any other engine.
Read the contract before building or deploying:

    RealityEngine_CI/docs/BUILD_CONTROL_CONTRACT.md

```bash
cd ../RealityEngine_CI && ./scripts/regression-test.sh --build-only
```

**This repository contains two independent sbt builds, not one build with two
subprojects.** The root `build.sbt` declares no `lazy val` subprojects, no
`aggregate` and no `dependsOn`, so **the root assembly does not produce the
perception engine**. Each needs its own invocation from its own directory:

```bash
sbt clean assembly                          # → target/scala-2.13/reality-engine.jar
(cd perception-engine && sbt clean assembly) # → perception-engine/target/scala-2.13/perception-engine.jar
```

`compile` is not `assembly`. `startUniverse.sh` launches fat jars; building with
`compile` produces classes and leaves the jars untouched, and `start.sh` rebuilds
stale jars on the way up so a local run hides the mistake entirely
(`RealityEngine_CI#173`).

## Key Commands

```bash
sbt test
cd perception-engine && sbt test
cd perception-engine && make e2e-healthkit-spezi
```

## Runtime Contract

- Keep RE/PE routes and payloads aligned with C++ and LSP.
- Be careful with startup smoke tests that mutate active state before byte-equivalence checks.
- Keep machine loading, source counts, and serialized payloads explicit when debugging parity drift.
- Use the same ACP/OpenClaw environment defaults as the rest of the application.

## LSP Support

Use Metals with `sbt`. Import both root and `perception-engine` builds when working across RE and PE. Use markdown, JSON, and Bash support where relevant.

## Editing Rules

- Run `sbt test` for root engine changes.
- Run PE `make` targets for standalone PE changes.
- Do not commit generated runtime state or local data unless explicitly requested.
