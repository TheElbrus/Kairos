# ADR 0001: Minecraft version and trace semantics

Date: 2026-10-17
Status: accepted

Decision:
Kairos targets Minecraft 1.8.9 (Forge) only. No second version is supported in v1.
Trace semantics are frozen: line N records the input applied during tick N and the state at the end of tick N.

Reason:
The target maps are built for 1.8.9, and in Minecraft all movement physics is version-specific. Oracles exist for 1.8.9 (ParkourCalculator and MPK Mod support it), which gives free cross-checks while developing the simulator. 1.8.9's player physics is simpler than modern versions (fewer poses and newer states to simulate), which shrinks the surface area for mismatch.

Alternatives considered:
- 1.12.2: also supported by ParkourCalculator and still old, but the target maps are not built for it.
- 1.21.x (Fabric): best tooling, but more movement states to simulate and no reason to pick it when the target maps are 1.8.9.
- Multi-version support: rejected for v1. All physics and data are version-specific; a second version doubles the verification work.

Consequences:
- All data is version-scoped (`data/1.8.9/...`) and every trace carries the version string.
- The trace semantics cannot change after the first golden trace is recorded; a change invalidates every recorded trace and test layers L1 and L2.
- The local physics reference must be a 1.8.9 tree (MCP `stable_22`, see ADR 0004).
- The per-field trace table is documented in ADR 0010 when the format is frozen.
