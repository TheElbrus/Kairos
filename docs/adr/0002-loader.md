# ADR 0002: Loader

Date: 2026-10-17
Status: accepted

Decision:
Forge for Minecraft 1.8.9. The mod (`loader-1.8.9/`) is thin: WorldAdapter, InputInjector, Recorder, BlockDumper, commands, and debug render only. No physics logic lives in the loader; `core` has zero Minecraft imports.

Reason:
ADR 0001 pins 1.8.9, and Forge is the standard, best-supported loader for 1.8.9. The plan's rule is Forge for 1.8.9/1.12.2, Fabric for modern versions. The 1.8.9 oracle tools (ParkourCalculator) are also Forge.

Alternatives considered:
- Legacy Fabric for 1.8.9: niche community, thinner tooling and documentation for this version; not worth the risk for a first-time modding project.
- Fabric on a modern version: blocked by ADR 0001.
- Standalone/mixin-only tooling: Forge already provides the lifecycle, keybind, and command infrastructure needed.

Consequences:
- Exactly one loader, one version, pinned. No dual-loader support, ever, for v1.
- Loader code stays thin so a future port would be cheap (PLAN.md section 4.1).
- 1.8.9 tooling is old; any friction encountered is recorded in `docs/JOURNAL.md`.
