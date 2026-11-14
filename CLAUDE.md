# RealityEngine_Scala Guidance

Last reviewed: 2026-11-04

See `/Users/johnt/workspace/GitHub/CLAUDE.md` for the integrated application map. Update both this file and the root map when Scala RE, Scala PE, actor behavior, or parity responsibilities change.

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
- `perception-engine/src/main/scala/com/realityengine/perception/`: standalone Scala PE; `PerceptionMain.scala` is the entrypoint.
- `perception-engine/src/main/scala/com/realityengine/perception/api/`: PE routes and websocket broadcast actor.
- `perception-engine/src/main/scala/com/realityengine/perception/store/`: PE source persistence.
- `perception-engine/src/main/scala/com/realityengine/perception/mqtt/`: MQTT bridge.
- `perception-engine/src/main/scala/com/realityengine/perception/healthkit/`, `.../triggers/`: HealthKit ingest and trigger dispatch.
- `src/test/`, `perception-engine/tests/`: test coverage.

## Building

**Builds are controlled through `RealityEngine_CI`, not from here.** This repo is
an independent git repository, not a subproject of CI or of any other engine.
Read the contract before building or deploying:

    RealityEngine_CI/docs/BUILD_CONTROL_CONTRACT.md

```bash
cd ../RealityEngine_CI && ./scripts/regression-test.sh --execute --build-only
```

**Container images are built from CI's Dockerfiles, not from here**:
`RealityEngine_CI/docker/scala/Dockerfile` (image `re-scala`, this repo as the
context) and `RealityEngine_CI/docker/scala-perception-engine/Dockerfile`
(image `re-scala-pe`, `perception-engine/` as the context). The release image
catalog is `RealityEngine_CI/release/images.json`. This repo kept its own
copies until 2026-10-17; they had drifted (no non-root user, and the perception
engine one copied paths outside its build context), so they were removed.

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

## Step completion point (RealityEngine_CI#375)

`runPhases` composes every machine from ISRE(n) in parallel — a `Future` per
machine on `ArbiterParallelism.ec`, joined by `Await.result(Future.sequence(...))`
— with the semantic audit off, then records each machine's audit
(`Machine.recordSemanticAudit`), coverage and fold serially in canonical order,
and only then resolves OSRE(n). One monitor, `stepLock`, guards every step,
reset, configure and trajectory-history read; the commit sets `completedStep` and
`notifyAll`s. `awaitStepPair` waits on it (timed `wait`), and
`GET /api/engine/steps/:n/pair?timeoutMs=` runs that wait on its own daemon
pool, never Akka's dispatcher. Steps are numbered from 0.

## Arbitration retention and the instance clock (RealityEngine_CI#296)

The commit (`recordTrajectory`, under `stepLock`) also ticks the instance's
Lamport clock and, with `arbitrationRetention` on, keeps `lastArbitration` in
`arbitrationSteps` (a `TreeMap` keyed by step) with the Lamport value, before
`notifyAll`. A reset clears `lastArbitration` and the retained steps; the clock
keeps its value. `GET /api/arbitration` answers the legacy object while retention
is off (its bytes unchanged) and the window's steps as a list while it is on
(`?step=N` reads one; 404/410/409/400): records by cell, contributions by
`(provider, originId, cesId, outputVectorId)`, and a machine contribution's
`outputVectorId` as the contract's `"0"` (legacy keeps `null`). Controls
`arbitrationRetention` (default `false`) and `arbitrationWindow` (default 1,
max 1024).

The clock is `{instance, lamport, step}` (`GET /api/engine/clock`,
`engine/InstanceClock.scala`). A UUID belongs to an **instance**, never an
engine type or image: CI allocates it (`INSTANCE_UUID`); without one the runtime
mints a v7 UUID, and `Main` boots it before the corpus loads. `lamport` ticks
once per committed step and never resets. An allocated instance keeps
`<uuid>.lamport` in `INSTANCE_CLOCK_DIR` (default `~/.reality-engine/clock/`) —
a high-water mark reserved 1024 ahead, written (temp + atomic move) before any
tick past it — and holds `FileChannel.tryLock` on `<uuid>.lock` for its life.
That lock is `fcntl`-based, so it excludes the C++ and LSP runtimes' `lockf` as
well: no two live instances of any engine type can share a UUID.

## Lane TLS

With `RE_TLS_CERT` set (RealityEngine_CI#668, this repo's #210), the RE and PE
serve TLS 1.3 with mTLS on their own ports, and the PE calls internal peers
over `https` with the lane CA and its own certificate. With it unset, nothing
changes, and `KEYSTORE_PATH` (Manager's proxied pair) keeps working as before.
The contract, including which peers are internal and which PE routes are open
to devices, is `RealityEngine_CI/docs/TRANSPORT_SECURITY.md`, and C++ and LSP
implement the same choices. The code is `com.realityengine.tls.LaneTls`, one
identical file in each build (`src/main/scala/...` and
`perception-engine/src/main/scala/...`), and `PerceptionRoutes.laneGuard`.
`LaneTlsSpec` (PE build) pins it. JDK 17 has no X25519MLKEM768, so the engines
negotiate x25519 and report it at startup. On the device routes a source named
in `HEALTHKIT_CLIENT_CERTIFICATE_REQUIRED` must still present a certificate
(owner, 2026-11-12; `PerceptionRoutes.deviceCertificate`).

## Standing rules

The standing rules are in
`../RealityEngine_CI/docs/ENGINEERING_CONTRACT.md`, which is
authoritative. They are not listed or summarised here, so this file never
falls behind the contract. Read it before changing anything here.
