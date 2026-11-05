# RealityEngine_Scala Source Guidance

This directory contains the root Scala Reality Engine implementation.

- Keep routes, JSON contracts, actor behavior, machine loading, and active state aligned with C++ and LSP.
- Use Metals with `sbt`.
- Treat generated machine bindings as derived code unless the user asks for generated updates.
- Verify with `sbt test` after source changes.

## Standing rules

The standing rules are in
`../../RealityEngine_CI/docs/ENGINEERING_CONTRACT.md`, which is
authoritative. They are not listed or summarised here, so this file never
falls behind the contract. Read it before changing anything here.
