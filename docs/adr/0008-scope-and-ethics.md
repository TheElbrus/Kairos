# ADR 0008: Scope and ethics

Date: 2026-10-17
Status: accepted

Decision:
- Kairos is for singleplayer and private test worlds only. The mod refuses to start in a multiplayer session unless the user explicitly edits a config value; the README states this is at the user's own risk.
- v1 block support is the tier table (PLAN.md section 1.1): Tier A and Tier B are required, Tier C is best effort after Gate 2, Tier D is post-v1.
- An unsupported block or mechanic seen by the dumper or planner causes a clear refusal, never a guess.
- Non-goals for v1: multiplayer servers, combat, survival mechanics, elytra, boats, water/lava parkour, multiple Minecraft versions (ADR 0001).

Reason:
Automating input on public multiplayer servers usually breaks their rules and can get accounts banned; the project does not need that risk. A finite tier table makes "done" a checkable statement, unlike "every possible jump".

Alternatives considered:
- Allowing multiplayer behind a warning only: rejected; too easy to enable by accident.
- Chasing full block coverage: rejected; unbounded and unverifiable. Tiers make progress measurable and honest.

Consequences:
- The singleplayer-only rule appears in the README, the mod's startup message, and the command surface (Phase 4 task 4.3).
- Public maps are used only with permission or with licences that allow it (ADR 0009); private map copies stay in the git-ignored `data/maps/private/`.
