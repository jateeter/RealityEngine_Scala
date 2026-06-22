# RealityEngine_Scala PE Guidance

This directory contains the standalone Scala Perception Engine.

- `src/PerceptionMain.scala`: PE entrypoint.
- `src/api/`: PE routes and websocket support.
- `src/engine/`: PE behavior.
- `src/store/`: source store.
- `src/mqtt/`: MQTT bridge.
- Keep PE source behavior aligned with C++, LSP, and Manager TypeScript PE expectations.
- Use Metals and the local Makefile targets for compile/test/e2e.

