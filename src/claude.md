# RealityEngine_Scala Source Guidance

This directory contains the root Scala Reality Engine implementation.

- Keep routes, JSON contracts, actor behavior, machine loading, and active state aligned with C++ and LSP.
- Use Metals with `sbt`.
- Treat generated machine bindings as derived code unless the user asks for generated updates.
- Verify with `sbt test` after source changes.

## Standing rules — authoritative in `../../RealityEngine_CI/docs/ENGINEERING_CONTRACT.md`

These apply here and are **not** restated in this file. They were previously
copied into eighteen `claude.md` files across six repositories, which is the
duplication problem the rules themselves warn about: copies drift, a rule added
to one applies only where someone looked, and with no authority a reader cannot
tell which copy is current.

| Rule | In short |
| --- | --- |
| Qualify every "registry" | Never the bare word — instance / machine / cesgen / arbitration / domain / semantic-bus / tag. |
| Verify a merge beyond the hosted checks | A green PR is not a verified PR; the hosted path cannot reach the integration points. Name what you could not exercise, and record what you noticed but did not chase. |
| Never commit to main | Branch from `origin/main`, PR, verify, squash-merge, clean up. |
| _CI is the authority | Peripheral repos keep minimal CI that forces local validation; RealityEngine_CI verifies fixes against a live universe. Check its `docs/` before adding CI anywhere else. |

Read the contract for the full text, the qualifier table, and the cleanup steps.
