# ADR 0004: Physics source of truth

Date: 2026-10-17
Status: accepted

Decision:
The source of truth for all player-physics behavior is:
1. The vanilla 1.8.9 code, deobfuscated locally with MCP `stable_22` into `mc-src/` (read-only reference, never committed).
2. The MCPK wiki (movement formulas, slipperiness, momentum threshold) as a secondary reference.

Every physics rule implemented in `core` must cite the exact `mc-src/` class and method (or MCPK page) it comes from. Anything that cannot be cited is marked UNVERIFIED in `docs/physics-notes.md`. Physics is never written from an AI's memory.

Reason:
Minecraft movement is deterministic and version-specific; exact reproduction requires reading the actual implementation, including per-tick order of operations, float/double choices, and the game's own sine/cosine implementation for facing. Mojang official mappings do not exist for 1.8.9, so MCP `stable_22` is the only viable deobfuscation. AI memory hallucinates constants and orderings; golden traces (L1) catch it, but this rule prevents it up front.

Alternatives considered:
- Physics from memory or third-party summaries: rejected; unverifiable and historically wrong in fine detail.
- Mojang official mappings: do not exist for 1.8.9.
- Reading obfuscated bytecode directly: impractical.
- Copying ParkourCalculator/MPK source: only after a licence check (ADR 0009); the default is to read behavior and write our own code.

Consequences:
- `mc-src/` stays local: it is git-ignored and must never be committed (Mojang licence).
- `docs/physics-notes.md` is AI-drafted with a citation on every rule, reviewed by a second model, and read/understood by the owner before Phase 1 (PLAN.md section 11).
- When sim and game disagree, the sim is wrong until proven otherwise; the mismatch becomes a golden trace plus a regression test, never a tolerance change (ADR 0007).
