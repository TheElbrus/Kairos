# AGENTS.md - the rulebook for AI tools working on Kairos

Kairos is a tick-exact Minecraft 1.8.9 parkour planner: a pure-Java simulation core (`core/`) plus a thin Forge 1.8.9 mod (`loader-1.8.9/`) that reads the world, plans exact inputs, records traces, and replays plans. Singleplayer and private worlds only. The real game is always the judge: if the sim and the game disagree, the sim is wrong.

Read `docs/PLAN.md` before doing anything. Every task uses the task prompt template in PLAN.md section 11 and names its task ID. Decisions are locked in `docs/adr/`; do not relitigate them inside a task.

## Architecture rules

- `core/` has ZERO Minecraft imports. It is a pure Java library, JUnit-tested in seconds without launching the game. A build-failing architecture test enforces this.
- `MovementSim.step` is a pure function on immutable state; the hot path is allocation-free.
- The loader is thin: WorldAdapter, InputInjector, Recorder, BlockDumper, commands, debug render only. No physics in the loader.
- No jump classifier. Never write `if (jumpType == NEO)`; ice/vine/slab/trapdoor/ladder behavior must fall out of block properties.
- Block shapes and properties come only from `blocks.json` dumped from the game registry; nothing hand-typed.
- One version (1.8.9), one loader (Forge). Interfaces in PLAN.md section 4.3 must not change without explicit approval in the task.

## Physics citation rules

- Every physics rule cites the exact `mc-src/` class and method (or the MCPK wiki page). If you cannot cite it, say so and mark it UNVERIFIED.
- Physics is NEVER written from memory. No guessed constants, no guessed operation order.
- `mc-src/` is a read-only local reference. It is git-ignored and NEVER committed.
- When sim and game disagree, the sim is wrong: record the situation as a golden trace and fix the sim.

## Forbidden actions

- Do NOT edit golden or fuzz traces, catalog labels, exam maps, or `gates/*.json`.
- Do NOT loosen tolerances, skip, or edit tests to make a failure pass. A bug becomes a regression test before it is fixed.
- Do NOT add dependencies, refactor, or change architecture outside the task's "Allowed to modify" paths.
- Do NOT commit decompiled Minecraft source, game assets, maps without licence or permission, or credentials/secrets.
- Do NOT build multiplayer features. Kairos is singleplayer/private only; the mod must refuse multiplayer unless the config is explicitly edited.
- Do NOT start phase N+1 code before tag `phaseN-done` exists; experiments live on branches.

## How to work

- One task per branch, one commit per task. Read the diff yourself before merging.
- Work only inside "Allowed to modify" for the current task. Name what you did not touch.
- Do not thrash: if you fail twice at the same thing, stop and write the failing trace or test into `docs/JOURNAL.md` for the owner.
- Definition of done per task: code merged, tests pass, `docs/JOURNAL.md` has two lines (what was done, what surprised you), `docs/STATUS.md` updated.
- Gates and test layers are defined in PLAN.md section 6; time budgets and kill switches in section 9. Gates are honored, not negotiated.
