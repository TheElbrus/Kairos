# ADR 0009: Licences and credits

Date: 2026-10-17
Status: accepted

Decision:
- Reference projects are read for behavior and design, not copied. Default: nothing is copied from any project.
- Any code ever reused must first pass a licence check: the licence permits reuse, and its terms plus the exact files or ranges are recorded here. Otherwise the behavior is re-implemented.
- Credits (README, PLAN.md Appendix D): MCPK wiki (movement knowledge), ParkourCalculator by Leg0shii (oracle and angle-solver reference), MPK Mod (MPKMod/MPKMod2; movement tooling reference), Baritone (route-search ideas only; its movement is approximate), and vanilla Minecraft code read via the local MCP `stable_22` decompile.
- ParkourCalculator (Leg0shii) is GPL-3.0: used strictly as a black-box oracle; no code from it is ever copied (copying would force copyleft on Kairos).
- Never committed: decompiled Minecraft source (`mc-src/`), game assets, or maps we do not have the right to redistribute (`data/maps/private/`).
- Specific facts taken from a specific page or project are credited at that point in the notes or code reference.

Reason:
Kairos must be publishable. A lot of Minecraft tooling sits in a licence grey zone; the clean default (read behavior, write our own code, cite sources, commit nothing we lack rights to) keeps the project distributable and legally boring.

Alternatives considered:
- Copy code freely and sort out licensing later: rejected; later never comes, and a repo with unclear provenance cannot ship.
- Avoid reading other tools entirely: rejected; ParkourCalculator and MPK are exactly the oracles the plan cross-checks against, and reading behavior is legitimate.

Consequences:
- This ADR is updated (or superseded) if any reuse decision changes.
- The README carries the credits section before release (Phase 7).
- Catalog and oracle data files record their source (`label_source`), so knowledge provenance stays auditable.
