# Machine Concept — Version 1.0

A **Machine** is the fundamental computational unit of the Reality Engine. It is a finite-state automaton (FSA) that observes a contiguous window of a shared perceptual space, advances through a set of recognizers, and writes computed output back into that space when a pattern is matched. Machines compose by sharing perceptual space regions — the output window of one machine overlaps the input window of another.

---

## 1. Finite-State Automaton Structure

Each Machine is a **deterministic finite automaton (DFA)** whose alphabet is the set of quantized perceptual vectors. The machine's DFA state is the complete activation pattern across all RealityVectors in all CriticalEventSequences (CES) — the tuple of which vectors are currently active. Given that global activation pattern and an input symbol, the acceptance rules produce exactly one next activation pattern; there is no branching.

```
Machine  ←  DFA state = activation pattern across ALL vectors in ALL CESs
 └─ CriticalEventSequence (CES)   ← one recognizer component
     ├─ RealityVector (contributes bits to the machine's DFA state)
     │    elements: [(value, comparator), ...]   ← acceptance condition for one input symbol
     │    outputVectors: [...]                    ← Mealy-style emission on acceptance
     │    nextVectorIds: [q₁, q₂, ...]           ← successor vectors to activate
     └─ RealityVector ...
```

A single vector with multiple `nextVectorIds` activates all listed successors on acceptance — this is not non-deterministic choice but **powerset construction already baked in**: the runtime tracks the full *set* of active vectors, and the transition `(active-set, input) → next-active-set` is single-valued and total.

### DFA State Representation

The machine DFA state is the **activation pattern** — the set of RealityVectors currently marked active. Each RealityVector contributes one bit to this pattern. The start state has exactly the `isInitial` vectors active; all other vectors start inactive.

Each individual RealityVector holds:

| Field | Type | Role |
|---|---|---|
| `id` | string | Unique state identifier |
| `isInitial` | bool | Whether this is a start state (q₀) |
| `elements` | `[{value, comparatorType, threshold}]` | Acceptance condition for one input symbol |
| `outputVectors` | `[{id, vector}]` | Mealy-style emission on acceptance |
| `nextVectorIds` | string[] | State transitions to activate on acceptance |
| `matchAlgorithm` | string | Element-level comparator (`gte` or `equals`) |

All initial vectors are **always active** (they are part of the fixed start state and remain active across cycles). A vector activates its successors when its acceptance condition is met; successors join the active set in the *next* cycle (deferred activation prevents same-cycle cascade). The resulting active set is the new DFA state.

### Transitions

A vector accepts an input symbol when every element's comparator is satisfied:

| Comparator | Condition |
|---|---|
| `gte` (default) | `inputCell ≥ value` |
| `equals` | `inputCell == value` (exact) |
| `threshold` | `inputCell ≥ threshold` |

Acceptance is checked per element, then ANDed across all elements. The machine-level `matchAlgorithm` sets the default; individual elements may override with their own `comparatorType`.

### Recognized Languages are Regular

Because the machine is a DFA, the set of input sequences that drive it to an emitting activation pattern is a **regular language**. Each CES contributes a regular sub-language; the machine's overall recognized language is the union of those sub-languages filtered through the arbiter rule.

```
L(machine) ⊆ { w ∈ Σ* : w drives the DFA to an activation pattern where the arbiter fires }
```

Within a single CES, the contribution is:

```
L(CES) = { w ∈ Σ* : w drives the CES component from its initial vectors to some emitting vector }
```

A single-vector CES with one `isInitial` vector recognizes **single-symbol patterns** — equivalent to the regular expression `{w : elements(w)}`. A chain of vectors `q₀ → q₁ → q₂` recognizes sequences matching `r₀ · r₁ · r₂` (concatenation of three symbol regexes). Branches in `nextVectorIds` (multiple successors) expand the active set — equivalent to **union** (`r₀ · (r₁ | r₂)`). Loops create **Kleene closure** (`r*`).

---

## 2. Machine Architecture

```
┌─────────────────────────────────────────────────────────┐
│                        Machine                          │
│                                                         │
│   ┌──────────────┐   ┌──────────────┐                  │
│   │    CES 0     │   │    CES 1     │  ...              │
│   │  (FSA over   │   │  (FSA over   │                   │
│   │   Σ* slice)  │   │   Σ* slice)  │                   │
│   └──────┬───────┘   └──────┬───────┘                  │
│          │                  │                           │
│   ┌──────▼──────────────────▼───────┐                  │
│   │         OutputArbiter           │                   │
│   │   PASSTHROUGH | AND | OR        │                   │
│   └──────────────┬──────────────────┘                  │
│                  │                                      │
│   ┌──────────────▼──────────────────┐                  │
│   │       PerceptualMapping         │                   │
│   │  input:  {offset, length}       │                   │
│   │  output: {offset, length}       │                   │
│   │  bitsPerElement: 1|2|4|8        │                   │
│   └─────────────────────────────────┘                  │
└─────────────────────────────────────────────────────────┘
```

### Input Phase

The machine reads `perceptualMapping.input.length` cells starting at `input.offset` from the shared perceptual space. This slice is the input symbol for all CES in this cycle.

### Processing Phase

Each CES independently runs `transition(inputSymbol)`:
1. Every active vector tests acceptance against the current input symbol.
2. Accepted vectors emit their output vectors and enqueue successor IDs.
3. Successors are activated **after** the full active-vector loop (atomic / deferred).

All CES run simultaneously on the same snapshot of the input region (snapshot isolation).

### Arbiter Phase

The `OutputArbiter` selects the final machine output from CES emissions:

| Rule | Behaviour |
|---|---|
| `PASSTHROUGH` | Pass any output directly (first emitting CES wins) |
| `AND` | Output only when every CES with outputs has fired |
| `OR` | Output when at least one CES fires |

### Output Phase

The arbiter's chosen output vector is **merged** into `perceptualMapping.output` region of the perceptual space, making it available as input to downstream machines in subsequent cycles.

---

## 3. Perceptual Mapping and Bit Packing

```json
"perceptualMapping": {
  "input":          { "offset": 64, "length": 4 },
  "output":         { "offset": 68, "length": 2 },
  "bitsPerElement": 1
}
```

| Field | Values | Meaning |
|---|---|---|
| `input.offset` | ≥ 0 | First cell index this machine reads |
| `input.length` | ≥ 1 | Number of cells in the input window |
| `output.offset` | ≥ 0 | First cell index this machine writes |
| `output.length` | ≥ 1 | Number of cells in the output window |
| `bitsPerElement` | 1, 2, 4, 8 | Quantization depth per cell |

`bitsPerElement = 1` (the corpus default, used in 961 of 1014 machines) means each perceptual cell is a binary value: the machine is a **Boolean FSA** operating over a binary alphabet. `bitsPerElement = 8` gives a full unsigned-byte range (0–255 normalized to [0.0, 1.0]).

---

## 4. Machine JSON Schema

```json
{
  "version": "1.0.0",
  "machine": {
    "name":             "string (required)",
    "description":      "string",
    "arbiterRule":      "PASSTHROUGH | AND | OR",
    "matchAlgorithm":   "gte | equals",
    "perceptualMapping": {
      "input":          { "offset": 0, "length": 4 },
      "output":         { "offset": 4, "length": 2 },
      "bitsPerElement": 1
    },
    "sequences": [
      {
        "id":           "string",
        "name":         "string",
        "schemaVersion": "string (optional)",
        "deprecatedAt": "ISO-8601 date (optional)",
        "replacedBy":   "sequence id (optional)",
        "metadata":     {},
        "vectors": [
          {
            "id":        "string",
            "isInitial": true,
            "elements":  [{ "value": 1, "comparatorType": "gte" }],
            "outputVectors": [{ "id": "out-0", "vector": [1, 0] }],
            "nextVectorIds": []
          }
        ]
      }
    ],
    "metadata": {
      "dispatchableAgent": "agent-name",
      "aiTrigger":         "trigger-id",
      "triggerConfig":     { "rules": [...] },
      "governance":        { "ownerTeam": "...", "ragStatusCode": "RED|AMBER|GREEN" },
      "severity":          "life-safety",
      "compose": {
        "subscriptions": [
          { "producerMachineId": "m1", "producerSequenceId": "s1", "bitOffset": 42 }
        ]
      }
    },
    "inputSequences": [...]
  }
}
```

### Required Fields

| Field | Validated by |
|---|---|
| `version` (must be `"1.x.x"`, major = 1) | All runtimes (LSP, Scala, C++) |
| `machine.name` | All runtimes |

### Optional Fields with Defaults

| Field | Default | Notes |
|---|---|---|
| `machine.description` | `""` | |
| `machine.arbiterRule` | `PASSTHROUGH` | |
| `machine.matchAlgorithm` | `gte` | Machine-level default; per-element override in `elements[].comparatorType` |
| `machine.perceptualMapping.bitsPerElement` | `8` | All runtimes default; corpus typically uses `1` |
| `sequences[].schemaVersion` | absent | C++, Scala, LSP all surface this in STA/coverage reports |
| `sequences[].deprecatedAt` | absent | ISO-8601; runtime emits deprecation telemetry on fire |
| `sequences[].replacedBy` | absent | ID of successor sequence; surfaced in coverage registry |

---

## 5. Sequence Lifecycle Fields

Sequences carry optional lifecycle annotations to support graceful deprecation across rolling deployments.

```json
{
  "id": "rs-reset-sequence",
  "deprecatedAt": "2026-02-01",
  "replacedBy":   "rs-set-sequence",
  "schemaVersion": "1.0"
}
```

When a deprecated sequence fires, all three runtimes emit a `deprecated_fire` coverage event. The `replacedBy` field identifies the preferred successor, enabling tooling to automatically migrate machines. `schemaVersion` is informational — it allows multiple incompatible machine schemas to coexist in the same corpus.

---

## 6. Compose / Meta-CES Event Bus

A machine may subscribe to the outputs of other machines via `metadata.compose.subscriptions`. When a producer's sequence fires in a step, the runtime writes `1.0` to the subscriber's `bitOffset` in the perceptual space, making the event visible as a binary input bit to the subscriber in subsequent cycles.

```
Producer Machine ──fires seq "seq-a"──► Event Bus
                                             │
                    ┌────────────────────────▼────┐
                    │  subscriber: "meta-machine"  │
                    │  bitOffset: 42               │
                    └────────────────────────┬─────┘
                                             │
                             perceptualSpace[42] := 1.0
                                             │
                    Meta-Machine reads bit 42 next cycle
```

Latched event bits persist across cycles so the subscriber has at least one full cycle to observe the signal.

---

## 7. Regular-Expression Equivalences

Because the machine is a DFA, standard DFA-to-regex constructions apply directly. The table below describes how CES vector topologies map to regular expressions — read as contributions to the machine's overall recognized language.

| CES vector topology | DFA activation-pattern behaviour | Equivalent regex |
|---|---|---|
| Single initial vector, no successors | Active set never grows beyond start | `r₀` (one-symbol pattern) |
| Chain: q₀ → q₁ → q₂ (output on q₂) | Active set advances one step per matching symbol | `r₀ · r₁ · r₂` |
| Branch: q₀ → {q₁, q₂} | Both successors join active set simultaneously | `r₀ · (r₁ \| r₂)` |
| Loop: q₀ → q₁ → q₀ | Active set cycles back to initial vector | `(r₀ · r₁)*` |
| Multi-start (two initial vectors) | Both initial vectors always active | `r₀ \| r₁` |

The "branch" row (one vector activating multiple successors) is the key insight: it is **not** non-determinism — it is the DFA tracking multiple simultaneously active positions in the activation pattern, which is precisely the powerset construction expressed directly in the runtime data structure.

### Worked Example — `RSFlipFlopDeprecatedDemo.json`

```
SET sequence:   isInitial(s=1,r=0) → output(Q=1)
RESET sequence: isInitial(s=0,r=1) → output(Q=0)   [deprecated 2026-02-01, replacedBy: SET]
```

Each CES contributes one vector to the activation pattern. The machine DFA start state has both initial vectors active. On input `(1,0)` the SET vector accepts and emits `Q=1`; on input `(0,1)` the RESET vector accepts and emits `Q=0`. The recognized language is `{(1,0)} | {(0,1)}` — the regular expression `(1,0) | (0,1)` over a 2-bit alphabet, provably non-cyclic (no Kleene closure) and therefore strongly STA-compliant.

### STA (Single-Transition Assumption)

For **life-safety machines** (`metadata.severity = "life-safety"`), each intra-sequence transition must have Hamming distance ≤ 1 between consecutive vector states. This constrains the recognized language to a subclass of regular languages where each adjacent symbol pair differs in at most one bit — equivalent to restricting the regex alphabet to **adjacent Gray codes**.

---

## 8. Cross-Runtime Field Support Matrix

| Field | LSP | Scala | C++ |
|---|---|---|---|
| `version` validation | ✓ (file load) | ✓ (always) | ✓ (file load) |
| `bitsPerElement` (parse + storage footprint) | ✓ | ✓ | ✓ |
| `sequences[].schemaVersion` | ✓ | ✓ | ✓ |
| `sequences[].deprecatedAt` | ✓ | ✓ | ✓ |
| `sequences[].replacedBy` | ✓ | ✓ | ✓ |
| `metadata.compose` event bus | ✓ | ✓ | ✓ |
| `matchAlgorithm = "equals"` | ✓ | ✓ | ✓ |
| `arbiterRule` AND / OR / PASSTHROUGH | ✓ | ✓ | ✓ |
| `metadata.governance` / `triggerConfig` | ✓ | ✓ | ✓ |
| `metadata.severity = "life-safety"` (STA gate) | ✓ | ✓ | ✓ |

All five discrepancies identified in the pre-1.0 audit have been resolved. The field support matrix is now uniform across all three runtimes.
