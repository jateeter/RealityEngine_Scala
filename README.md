# RealityEngine_Scala

Scala implementation of the Reality Engine (RE) and Perception Engine (PE).
Black-box equivalent to [`RealityEngine_AI`](../RealityEngine_AI) (TypeScript, default)
and [`RealityEngine_CPP`](../RealityEngine_CPP) (native C++) on the same machine JSON
corpus, governance contracts, and Prometheus metrics shape.

## Structure

- `src/` — Reality Engine Scala implementation (Akka actors, state machine engine, REST API)
- `perception-engine/` — Perception Engine Scala implementation
- `build.sbt` — SBT build configuration for the Reality Engine

## Integrated Specification

Cross-repository deployment rules are owned by
[`RealityEngine_CI/DEPLOYMENT_CONTRACT.md`](../RealityEngine_CI/DEPLOYMENT_CONTRACT.md)
and [`RealityEngine_CI/INTEGRATED_SPECIFICATION.md`](../RealityEngine_CI/INTEGRATED_SPECIFICATION.md).
The active machine and RE/PE operations contract is described in
[`RealityEngine_Machines/docs/REALITY_PERCEPTION_OPERATIONS.md`](../RealityEngine_Machines/docs/REALITY_PERCEPTION_OPERATIONS.md).

## Machine Model

The Reality Engine executes **machines** — deterministic finite automata (DFAs) that
observe a contiguous window of shared perceptual space, advance through a set of pattern
recognizers (CriticalEventSequences), and write computed output back into that space on
match. Machines compose by overlapping their input/output perceptual regions.

See [MACHINE_CONCEPT.md](MACHINE_CONCEPT.md) for the full specification: DFA theory,
JSON schema, perceptual mapping, regex equivalences, compose event bus, sequence
lifecycle fields, and the cross-runtime field support matrix.

## Key Domain Classes

| Concept | Scala class | Responsibility |
|---|---|---|
| `RealityVector` | `com.realityengine.models.RealityVector` | Comparator matching and output assertions |
| `CriticalEventSequence` | `com.realityengine.models.CriticalEventSequence` | Deferred activation over active vector graph |
| `OutputArbiter` | `com.realityengine.models.OutputArbiter` | AND / OR / PASSTHROUGH output decisions |
| `Machine` | `com.realityengine.models.Machine` | Runs CES graphs, emits machine transition results |
| `PerceptualSpaceSimulator` | `com.realityengine.engine.PerceptualSpaceSimulator` | Snapshot → process → deterministic merge loop, compose event bus |
| `MachineLoader` | `com.realityengine.services.MachineLoader` | JSON ↔ domain model, version validation |

## Build and Run

```bash
sbt compile
sbt test
sbt run
```

Defaults:

- Reality Engine: `http://localhost:5001`
- Machine directory: `../RealityEngine_Machines/machines`
- Vector dimension: `7680` (configurable floor; grows elastically to span all machine mappings)

## Documentation

- [Machine Concept](MACHINE_CONCEPT.md) — DFA theory, JSON schema, regex equivalences, STA
- [Surface Specification](SURFACE_SPEC.md) — canonical API surface across all runtimes
