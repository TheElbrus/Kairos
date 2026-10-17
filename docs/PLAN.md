# Kairos: Project Plan v2 (working title)

An exact-physics planner and executor that clears hand-built Minecraft parkour maps.
Put this file in `docs/PLAN.md`. Give it to every AI model you use as context.

**Plan version:** 2.0. **Changes from v1:** scope tiers, a thin vertical slice (Phase 0.5), a learning step, a version-decision guide, singleplayer-only rule, dedicated playbooks for the three hard problems (chaining, pixel-perfect yaw, executor drift), honest solver-completeness tests, time budgets with kill switches, and a realistic timeline.

---

## 0. Plain-English summary

- Minecraft movement is **deterministic**: the same inputs from the same state always give the same result.
- So we don't *teach* a bot to parkour. We write a **simulator** that copies the game's movement exactly, then a **planner** that searches for the keys and camera angles that land each jump.
- A **mod** feeds the planner the real map and plays the resulting inputs in the game.
- The real game is always the judge. If the simulator and the game disagree, the simulator is wrong.
- Why this fixes your last attempt: a bot trained or tuned on generated maps only learns generated maps. Here, the physics is exact and the score comes from **frozen hand-built maps** we never tune on.

---

## 1. Goals, scope, and what "possible" means

**Promise for v1:**

> `/Kairos start` in a hand-built singleplayer map. The bot runs to the goal and performs every jump that is physically possible in the chosen Minecraft version, **for the supported block set (section 1.1)**.

"Every possible jump" is the direction. The **block tiers below are the deliverable**, because a block list can be finished and "everything" can't.

### 1.1 Block support tiers

| Tier | Blocks / mechanics | Added in | Required for v1? |
|---|---|---|---|
| A | Full blocks, air, slabs, stairs, walls, fences, fence gates | Phase 2 | Yes |
| B | Ice, packed ice, blue ice, ladders, vines, trapdoors, carpets, panes, anvils, lily pads, snow layers | Phase 2 | Yes |
| C | Soul sand, honey, slime, cobweb, scaffolding, cauldrons, hoppers, heads, end rods, chains | After Gate 2 | Best effort |
| D | Water, lava, bubble columns, powder snow, pistons, entities, potion effects (speed, jump boost) | Post v1 | No |

A Tier C or D block that the dumper sees but the sim doesn't support must make the planner **refuse with a clear message**, never guess.

### 1.2 Non-goals for v1

Multiplayer servers, combat, survival mechanics, elytra, boats, water/lava parkour, multiple Minecraft versions.

### 1.3 Ground rule: where it runs

Kairos is for **singleplayer and private test worlds only**. Automating input on public multiplayer servers usually breaks their rules and can get you banned. Put this in the README and in the mod's startup message. The mod refuses to start in a multiplayer session unless you explicitly edit a config value, and the README says you do that at your own risk.

---

## 2. Metrics (your resume numbers, produced automatically)

| Metric | Meaning | Target |
|---|---|---|
| Sim accuracy | Max position error vs real game over all golden and fuzz traces | 0 expected, accept <1e-9 (ADR 0007) |
| Catalog solve rate | % of feasible-by-construction catalog jumps solved | 100% |
| False-solution rate | % of infeasible targets where the solver returns a plan | 0% |
| Oracle completeness | % of community-known hard jumps solved (section 6, Phase 3) | Measured, then set a floor |
| Replay success | % of solved plans that land in the real game | 100% |
| Exam-map completion | % of jumps cleared on the frozen exam set, by tier | Set after first baseline |
| Planning speed | Median ms per jump, sim ticks per second | Set after first baseline |

---

## 3. Decisions to lock (write each as `docs/adr/000N-*.md`)

| ADR | Decision | How to decide |
|---|---|---|
| 0001 | **Minecraft version** and trace semantics (section 3.1) | Use the version your target maps were built for. All physics is version-specific. |
| 0002 | **Loader** | Forge if 1.8.9 or 1.12.2, Fabric if modern. Loader code stays thin. |
| 0003 | **Language** | Java for core and mod. Python only for analysis scripts in `tools/`. |
| 0004 | **Physics source of truth** | Vanilla code (deobfuscated with MCP `stable_22`; Mojang official mappings do not exist for 1.8.9) and the MCPK wiki. Never from an AI's memory. |
| 0005 | **Determinism rules** | Section 3.2. |
| 0006 | **Exam set policy** | Exam maps are frozen, never used to tune. |
| 0007 | **Accuracy tolerance** | Decide now what "match" means, before you hit a mismatch. Default: exact, with a documented fallback only if a proven Java/JVM difference forces it. |
| 0008 | **Scope and ethics** | Section 1.3 and the tier table in 1.1. |
| 0009 | **Licences and credits** | Which reference projects you read, what you copied (ideally nothing), and how you credit them. |

### 3.1 Version decision guide

Pick the version in this order:

1. **Where are your target maps?** Open five maps you want to beat. Which version are they built for? That wins unless something below blocks it.
2. **Is there an oracle for it?** ParkourCalculator supports Forge 1.8.9, Forge 1.12.2 and Fabric 1.21.10, and the MPK Mod covers similar versions. An existing tool for your version gives you free cross-checks.
3. **Tooling pain.** Old versions (1.8.9) have simpler player physics but older, more awkward modding toolchains. Modern versions have easier tooling but more states (poses, newer blocks) to simulate.

Write the result as ADR 0001 with the reason. Do not support two versions in v1.

**Trace semantics (also in ADR 0001, never change):** line N records the **input applied during tick N** and the **state at the end of tick N**.

### 3.2 Determinism rules (ADR 0005)

- Mirror the game's numeric types exactly (float vs double), including its sine/cosine implementation for facing. If the game uses a lookup table, copy the same table generation. Don't substitute `Math.sin`.
- Use `StrictMath` only for **your own** planner math, where the game doesn't dictate the function.
- No `HashMap` iteration order in search, and seeded RNG everywhere.
- Sprint state and jump delay are part of the state.

---

## 4. Architecture

### 4.1 Principle

`core` has **zero Minecraft imports**. It is a pure Java library you can test with JUnit in seconds, without launching the game. The mod only adapts Minecraft to `core` and back.

```
        +------------------ loader-<version> (the mod) ------------------+
        |  WorldAdapter   InputInjector   Recorder   BlockDumper  Commands |
        |  (MC world ->   (plan -> keys   (writes    (writes      HUD/Debug|
        |   WorldView)     + yaw / tick)   traces)    blocks.json) render  |
        +--------------------------+--------------------------------------+
                                   |  depends on
        +--------------------------v--------------------------------------+
        |                              core                                |
        |  physics/   world/     plan/         trace/       bench/        |
        |  MovementSim WorldView SegmentSolver TraceReader  CatalogRunner |
        |  Collision   BlockProps YawRefiner   TraceCompare Metrics       |
        |                        RoutePlanner                             |
        +------------------------------------------------------------------+
```

### 4.2 Repository layout

```
Kairos/
  README.md
  AGENTS.md                  # context file for AI coding tools
  build.gradle(.kts), settings.gradle(.kts)
  docs/
    PLAN.md  STATUS.md  JOURNAL.md  physics-notes.md  FAILURES.md
    adr/                     # decision records
  core/
    src/main/java/.../physics/   Vec3, Aabb, PlayerState, Input, MovementSim, Collision
    src/main/java/.../world/     WorldView, BlockState, BlockProps, BlockRegistry
    src/main/java/.../plan/      Target, Plan, SegmentSolver, YawRefiner, RoutePlanner
    src/main/java/.../trace/     TraceFormat, TraceReader, TraceWriter, TraceCompare
    src/main/java/.../bench/     Catalog, CatalogRunner, Metrics
    src/test/java/...            unit + golden + property tests
  loader-<version>/
    src/main/java/.../           entrypoint, adapters, recorder, executor, commands
  tools/                     # analysis scripts, gate script (added at end of Phase 2)
  data/
    <version>/blocks.json    # generated by BlockDumper, never hand-written
    traces/  scenarios/  catalog/  oracle/
    maps/dev/  maps/exam/
  gates/                     # gate result files (committed)
```

### 4.3 Core interfaces (sketch; keep the shape, adjust names)

```java
public record PlayerState(
    double x, double y, double z,
    double vx, double vy, double vz,
    float yaw, float pitch,
    boolean onGround, boolean sprinting, boolean sneaking,
    int jumpDelay, boolean collidedH, boolean collidedV) {}

public record Input(boolean forward, boolean back, boolean left, boolean right,
                    boolean jump, boolean sneak, boolean sprint,
                    float yaw, float pitch) {}   // absolute yaw applied this tick

public interface MovementSim {
  PlayerState step(PlayerState s, Input in, WorldView w);   // pure
}

public interface WorldView {
  BlockState blockAt(int x, int y, int z);
  void collisionBoxes(Aabb region, List<Aabb> out);          // allocation-free
}

public record Target(Aabb landingSurface, double minEdgeMargin) {}
public record Plan(List<Input> inputs, PlayerState end, double edgeMargin) {}

public interface SegmentSolver {
  SolveResult solve(PlayerState start, Target t, WorldView w, SearchConfig cfg);
}
// SolveResult = Solved(plan) | ProvenInfeasible(reason) | GaveUp(budgetUsed)
```

`SolveResult` has **three** outcomes on purpose. "I found nothing" and "this is provably impossible" are different facts (see section 6, Phase 3).

### 4.4 Design rules

- `MovementSim.step` is a **pure function** on immutable state (use mutable scratch buffers internally for speed, but the public contract stays pure).
- **No jump classifier.** Ice, vine, slab, trapdoor and ladder jumps all fall out of block properties. Never write `if (jumpType == NEO)`.
- Block shapes and properties come from `blocks.json`, dumped from the game's registry and cross-checked against the vanilla data-generator reports. Nothing hand-typed.
- An architecture test (ArchUnit or Gradle) fails the build if `core` imports `net.minecraft.*`.
- The sim must be **allocation-free in the hot path**. Search will run millions of ticks.

---

## 5. Data formats

### 5.1 Trace (`.jsonl`, one JSON object per line)

One line per tick, plus one `"tick":-1` start-state line written first (all `in`
values zero). Keys are in a fixed order; see ADR 0010 for the field table.

```json
{"v":1,"mc":"<version>","scenario":"sprintjump_flat_01","tick":0,
 "in":{"w":1,"a":0,"s":0,"d":0,"jump":1,"sneak":0,"sprint":1,"yaw":0.0,"pitch":0.0,"mf":1.0,"ms":0.0},
 "pos":[0.5,65.0,0.5],"vel":[0.0,0.0,0.0],
 "onGround":true,"sprinting":true,"using":false,"colH":false,"colV":false,"te":1}
```

- `in.w/s` and `in.a/d` are the SIGNS of `movementInput.moveForward` / `moveStrafe`
  (left is positive strafe). `in.mf` / `in.ms` are those same axes as floats, read at
  the END of the tick, i.e. after the sneak `0.3` and item-use `0.2` scaling. Record
  both: the keys are what the planner presses, the axes are what the physics used.
- `in.sprint` is the sprint KEY; top-level `sprinting` is the resulting entity state.
  They are different things and both are kept.
- `using`, `colH`, `colV` are `isUsingItem()`, `isCollidedHorizontally`,
  `isCollidedVertically`.
- `te` is ticks since the anchor: `0` on the `tick:-1` line, then `1`, `2`, ... It is NOT
  the raw `ticksExisted` (which depends on how long the world has been open), so two runs
  of one scenario compare byte for byte.
- Doubles use `Double.toString`, floats use `Float.toString`; nothing is rounded, and
  `NaN` / `Infinity` make the writer throw. Files are UTF-8 with `\n` endings on every OS.
- The recorder watches the ABSOLUTE `ticksExisted`: if it does not increase by exactly 1
  between consecutive recorded ticks it stops and marks the trace invalid (a skipped tick
  is not recoverable).
- Server position corrections are NOT detected; see ADR 0010 for the hazard.

### 5.2 Scenario (`data/scenarios/*.json`)

```json
{"name":"sprintjump_ice_gap3","start":{"pos":[0.5,65,0.5],"yaw":0},
 "blocks":[{"from":[0,64,0],"to":[0,64,0],"id":"packed_ice"},
           {"from":[0,64,4],"to":[0,64,4],"id":"stone"}],
 "settleTicks":20,
 "inputs":[{"ticks":10,"w":1,"sprint":1},{"ticks":1,"w":1,"sprint":1,"jump":1},
           {"ticks":14,"w":1,"sprint":1}]}
```

### 5.3 Jump catalog (`data/catalog/*.json`)

```json
{"id":"gap4_flat_momentum","start":{"block":[0,64,0],"runup":[0,1,2,3]},
 "goal":{"block":[0,64,5]},"expect":"feasible|infeasible|unknown",
 "label_source":"construction|envelope|wiki|oracle","tags":["gap","flat"]}
```

`expect` comes from `label_source`, never from your gut.

### 5.4 Oracle set (`data/oracle/*.json`) - new

Known hard jumps with published solutions (from the MCPK wiki, community TAS plans, or ParkourCalculator plans you verify yourself in game). Each entry stores the jump geometry, the published inputs, and where it came from. Used as a **completeness** test in Phase 3: your solver should find *some* solution for each.

---

## 6. Testing strategy

| Layer | What | Runs |
|---|---|---|
| L0 | Formula unit tests (jump height, terminal velocity, jump duration) using values from the MCPK wiki for your version | CI, seconds |
| L1 | **Golden traces**: recorded from the real game, replayed in the sim, compared per tick | CI |
| L2 | **Fuzz traces**: random inputs in random block arenas, recorded once in game, committed | CI |
| L3 | Solver tests: catalog, reachable-by-construction targets, infeasible targets, oracle set | CI |
| L4 | **In-game replay**: solved plans run in the real game and must land | Harness, result files committed |
| L5 | Exam maps, end to end | Harness, result files committed |

**Rules:**

- The real game is the only ground truth. If sim and game disagree, the sim is wrong.
- Never edit a golden trace or loosen a tolerance to make a test pass.
- Every bug becomes a regression test before it is fixed.
- A second, independent check on physics: compare a sample of jumps against an existing tool (ParkourCalculator or MPK) for your version. Agreement from two tools is stronger than one.

**Gates:**

- Until the end of Phase 2, gates are a **checklist in `docs/STATUS.md`**, not automation. Don't build gate tooling before you have something to gate.
- From Phase 3, add `./gradlew gateN` plus `tools/gate.py N`. Each gate re-runs earlier gates, checks result files against thresholds, writes `gates/phaseN.json` (commit hash, date, numbers), and only then prints the tag command.
- No code for phase N+1 merges to `main` until tag `phaseN-done` exists. Experiments live in branches.

---

## 7. Phases, milestones, and timeline

Estimates assume about 10-15 hours per week, solo, first time. **Realistic** includes a buffer. Total realistic: about **30-40 weeks** (roughly 7-9 months, 300-550 hours) to Gate 5. The plan-v1 estimates were about 20-28 weeks, which is what you get if nothing goes wrong.

| Phase | Name | Realistic | Milestone |
|---|---|---|---|
| 0 | Foundations and learning | 2-3 wk | M0: mod loads, trace recorded |
| 0.5 | Thin vertical slice | 2 wk | **M1: bot clears one gap in game** |
| 1 | Exact simulator | 5-7 wk | M2: fuzz traces all match |
| 2 | Single-jump solver | 4-6 wk | **M3: first neo / tight jump solved and landed** |
| 3 | Route planner | 5-7 wk | **M4: first multi-jump chain with momentum** |
| 4 | Executor and UX | 3-4 wk | M5: `/Kairos start` works |
| 5 | Real maps | 6-10 wk | **M6: exam set scored** |
| 6 | ML layer (optional) | skip at first | Only if planning speed needs it |
| 7 | Packaging and release | 2 wk | M7: release, video |

Milestones in bold are the video-worthy ones. Record screen captures at each.

### Phase 0: Foundations and learning (2-3 weeks)

**Goal:** a repo that builds, a mod that loads, and a way to record and replay reality.

| ID | Task | Test |
|---|---|---|
| 0.0 | **Learn the basics**: one Java refresher, a Gradle multi-module tutorial, and the official modding getting-started guide for your loader | You build and run the loader's example mod |
| 0.1 | Write ADRs 0001-0009 | Each has a decision and a reason |
| 0.2 | Gradle multi-module skeleton, CI running `:core:test` | Green CI on an empty test |
| 0.3 | Mod loads in game, logs a line, has one keybind | Log excerpt in `JOURNAL.md` |
| 0.4 | **Recorder**: writes a trace every tick while enabled | 5 s of sprint-jumping gives one line per tick |
| 0.5 | **Scenario harness**: `/Kairos scenario run <name>` builds blocks, teleports, settles, feeds scripted inputs, writes trace | Run `sprintjump_flat_01` |
| 0.6 | Read the MCPK wiki, the vanilla movement code, and ParkourCalculator/MPK source (licences permitting); **write `physics-notes.md` yourself** | Notes list the exact per-tick order of operations, with source references |

**Gate 0 (checklist):**

- Mod loads; recorder and harness work.
- **Determinism:** the same scenario run 20 times gives byte-identical traces. If not, find out why (initial sprint state, jump delay, entity pushing, chunk-load timing, world settings) before anything else.
- The harness runs in a controlled world: fixed time, no weather, no mob spawning, no other entities.

### Phase 0.5: Thin vertical slice (2 weeks) - new

**Goal:** prove the whole loop early with the simplest possible version of each layer. The code is allowed to be rough and will be replaced.

| ID | Task | Test |
|---|---|---|
| 0.5.1 | Quick-and-dirty sim: flat ground, walk/sprint/jump, air movement, drag, full blocks only | Matches 3 golden flat traces |
| 0.5.2 | Brute-force solver for one straight gap (constant yaw, try every jump-start tick) | Solves gaps of 1 to 4 blocks |
| 0.5.3 | Play the solved inputs in game through the harness | The player lands on the far block |

**M1 (milestone):** a recording of the bot clearing a gap it planned itself. It's your first demo, and it proves that the recorder, sim, solver, and replay agree.

**Rule:** after M1, Phase 1 **rewrites** the simulator properly. Don't grow the thin slice into the real one.

### Phase 1: Exact simulator (5-7 weeks)

**Goal:** `core` reproduces the game's player movement tick for tick.

| ID | Task | Test |
|---|---|---|
| 1.0 | `BlockDumper` writes `data/<v>/blocks.json` (collision boxes per state, slipperiness, climbable, etc.), cross-checked against the vanilla data-generator reports | Counts match registry; spot-check slab, stair, trapdoor, fence, ladder, vine, ice |
| 1.1 | Flat movement: walk, sprint, jump, air acceleration, drag, momentum threshold | L0 tests and 10 golden flat traces |
| 1.2 | Collision: AABB sweep in the game's axis order, step-up, sneak edge clamp, head bumps | Traces: wall hit, head-hitter, step onto slab/stair |
| 1.3 | Slipperiness and block properties (Tier B, then Tier C) | One trace per block type |
| 1.4 | Ladders, vines, trapdoors, fences/walls, partial blocks | One trace per shape |
| 1.5 | Sprint state machine and jump delay | Traces: sprint stop on collision, releasing W, repeated jumps |
| 1.6 | Numeric fidelity: float/double semantics, the version's sine/cosine for facing | Traces at non-axis-aligned yaw (for example 33.3 and 161.9) |
| 1.7 | **Fuzz**: 1000 random scenarios (random arena, random inputs, 100 ticks), recorded in game, committed | All pass |
| 1.8 | Performance pass: allocation-free stepping | Ticks per second recorded |

**Gate 1 (checklist):**

- 100% of golden and fuzz traces match within the ADR 0007 tolerance.
- Sim speed recorded in `gates/phase1.json`.
- Architecture test: `core` has no Minecraft imports.
- **Do not proceed** while any trace mismatches. Each mismatch is a missing rule, so find it.

**Time-box:** if Phase 1 passes 10 weeks, stop and apply the kill switches in section 9.

### Phase 2: Single-jump solver (4-6 weeks)

**Goal:** given a start state and a target platform, find the input sequence that lands, or say why not.

| ID | Task | Test |
|---|---|---|
| 2.1 | Catalog generator and runner (gaps, height deltas, offsets, ice, slab, headhitter, ladder, etc.) | Runner reports per-entry result |
| 2.2 | Baseline solver: constant yaw plus input-schedule search | Solves simple gaps |
| 2.3 | Beam search with per-tick yaw deltas and sneak/sprint toggles, state de-duplication | Solves 4-block momentum jumps |
| 2.4 | Landing criteria with an **edge margin** parameter (0 = merely possible, above 0 = robust) | Boundary-case unit tests |
| 2.5 | Start-state freedom: start anywhere on the block footprint, variable run-up momentum | Catalog entries with run-up variants |
| 2.6 | **YawRefiner** (section 8.2): turns a rough plan into an exact one | Previously unsolved tight jumps now solve |
| 2.7 | Replay check: solved plans run through the Phase 0 harness | Result file committed |

**Gate 2 (checklist, then gate script is added):**

- 100% of feasible-by-construction targets solved.
- Replay success in game: 100% of a 200-plan sample lands. Anything less means a Phase 1 hole, so go back and add traces.
- Median solve time recorded; set a budget after the first baseline.
- Exam: **M3** video clip of a tight jump.

### Phase 3: Route planner (5-7 weeks)

**Goal:** chain jumps across a whole map, accounting for momentum carry-over.

| ID | Task | Test |
|---|---|---|
| 3.1 | `WorldAdapter` in loader (MC world to `WorldView`) and a JSON-map loader in core for CI tests | Same synthetic map gives identical `WorldView` from both |
| 3.2 | Platform extraction and goal/start definition (`/Kairos setstart`, `setgoal`) | Unit tests on synthetic maps |
| 3.3 | Graph search over (platform, entry-state bucket), lazy cached segment solves, cost = ticks, tie-break = margin | Greedy-fails-but-planner-succeeds maps |
| 3.4 | Keep a **diverse, Pareto-filtered** set of landing states per platform (section 8.1) | Test: the best landing for jump 1 blocks jump 2 |
| 3.5 | **Backward pass** from the goal: what entry states each jump can accept (section 8.1) | Heuristic cuts search time on long chains |
| 3.6 | Setup moves on a platform (stop, re-align, re-run-up) as planner actions | Maps that need a reset are solved |
| 3.7 | Plan horizon and backtracking; `plan()` callable from any live state | Replan from mid-air and mid-route states |
| 3.8 | 15+ synthetic courses with momentum-dependent chains, **and** the oracle set (section 5.4) | All solved |

**Solver honesty:** a solver that returns nothing has not proven the jump impossible. Test infeasibility in two ways:

- **Envelope bounds:** targets beyond an analytic upper bound (max reachable distance plus margin) must return `ProvenInfeasible`.
- **Budgeted give-up:** everything else returns `GaveUp` with the budget used. That is reported as "unknown", not "impossible".

**Gate 3:**

- All synthetic courses solved, including the adversarial ones.
- Oracle set: measured solve rate recorded; any miss becomes a FAILURES.md entry.
- 0% false solutions on infeasible targets.
- Whole routes replay in game to the goal: 100% of the sample.
- **M4** clip: first chained momentum route.

### Phase 4: Executor and UX (3-4 weeks)

**Goal:** `/Kairos start` works.

| ID | Task | Test |
|---|---|---|
| 4.1 | Inject inputs on the client tick (keys and exact yaw set directly, not simulated mouse movement), independent of frame rate | Same trace at 10 FPS and 240 FPS |
| 4.2 | Closed loop: compare actual vs predicted each tick; replan if deviation exceeds epsilon | Disturbance test below |
| 4.3 | Commands: `/Kairos start`, `stop`, `status`, `setstart`, `setgoal`; abort keybind; multiplayer refusal (section 1.3) | Manual checklist |
| 4.4 | Debug render: planned path boxes, current target platform, deviation readout | Visual check |
| 4.5 | Failure handling: fell, no route, chunk not loaded, plan timeout, unsupported block | Each has a clear message and a safe stop |
| 4.6 | Pre-flight check: loads the route's chunks and verifies every block is in a supported tier before starting | Unsupported block gives a clear refusal |

**Gate 4:**

- 20/20 consecutive runs complete every Phase 3 course (deterministic, so anything less is a bug).
- Max per-tick deviation stays below epsilon in undisturbed runs.
- Disturbance test: push or teleport the player mid-route; replan recovers on at least 90% of trials (record the exact number).
- Frame-rate independence proven.

### Phase 5: Real maps (6-10 weeks, iterative)

**Goal:** beat hand-built maps. This is where your last attempt failed, so the process matters more than the code.

1. **Collect 20-40 real hand-built map segments**, with permission from the authors or from maps whose licence allows it. Split into `dev` (tune on these) and **`exam` (frozen, never tuned on)**.
2. **Tier them by measurable features, not feeling**: tier 1 = only Tier A blocks and plain sprint jumps, tier 2 = Tier B blocks or momentum chains, tier 3 = tight landings (edge margin under a set value) or head-hitters, tier 4 = neos, triple neos, and multi-mechanic combos. Write the rule in `docs/adr`.
3. Run the bot. **Triage every failure into exactly one category:**
   - **A. Sim mismatch:** record the situation as a golden trace, fix the sim.
   - **B. Missing block/shape/property:** fix the dump or the rule, add a trace.
   - **C. Planner limit:** search too narrow or too slow; add a catalog entry.
   - **D. Executor/timing:** add a replay test.
   - **E. Out of scope:** Tier D block or mechanic; log it and move on.
4. Every fix ships with its regression test.
5. Keep `docs/FAILURES.md`: map, jump, category, fix, date.

**Gate 5:** set thresholds after the first baseline and write them into `gates/phase5.json` (for example: tier 1 at 100%, tier 2 at 90% or more, tier 3 at 70% or more; adjust to what you measure and explain why). Run the exam set **only at gate time**, and log every exam run.

### Phase 6: ML layer (optional)

Only start after Gate 5. The planner already works, so ML must **earn its place**.

- The dataset comes free from the planner (state, target, plan, ticks-to-goal).
- Options: a learned value function as the search heuristic, or distilling the planner into a fast policy with the planner as fallback.
- **Gate 6:** equal or better exam success and measurably faster planning. Otherwise leave it out and say so honestly in the writeup.

### Phase 7: Packaging and release (2 weeks)

- Build the mod jar. Make a modpack (for example a Modrinth `.mrpack`) with the required dependencies, such as the loader API.
- README: install, commands, limitations, tier table, benchmark table, credits, the singleplayer-only rule.
- Do not commit decompiled Minecraft source or maps you don't have rights to.
- Demo video; `JOURNAL.md` becomes the script; `gates/*.json` become resume numbers.

---

## 8. Playbooks for the three hard problems

### 8.1 Chaining jumps with momentum (Phase 3)

The hard part is not one jump. It is choosing landings so that the **next** jump is possible.

- **State key:** (platform, x/z bucket, velocity bucket, sprint flag, jump delay). Bucket size is a config value; tune it on dev maps.
- **Keep a Pareto front** of landing states per platform: fewer ticks, larger edge margin, different velocities. Never keep only "the best" one.
- **Backward pass:** starting from the goal, work out what entry states (position and velocity windows) each jump can accept. Use that as a search heuristic and to prune landings that can't lead anywhere.
- **Setup moves:** allow the bot to stop on a platform and re-align. This turns many "impossible with carried momentum" chains into solvable ones, at a cost in ticks.
- **Margin budget:** prefer robust plans (margin above 0) when they cost few ticks, and spend zero-margin jumps only where needed.
- **Adversarial tests:** build maps where jump 1's fastest landing kills jump 2, and maps that need a detour.

### 8.2 Pixel-perfect jumps (Phase 2)

Neos, triple neos, and tight wall-hugging jumps depend on exact yaw values and numeric rounding. Coarse beam search finds rough plans and cannot find these.

1. **Beam search** finds the input skeleton (which ticks jump, sprint, turn).
2. **YawRefiner** treats per-tick yaw as continuous variables, then optimizes them against **constraints**: the hitbox must avoid blocks on certain ticks and overlap the landing surface on the landing tick.
3. Coarse to fine: sweep, then local search, then a final exact check in the real sim.
4. **Every candidate is verified in the exact sim.** The refiner may suggest, only the sim decides.
5. Study how ParkourCalculator's angle solver frames constraints, and use it as an oracle for known neos.

### 8.3 Executor drift (Phase 4)

Sim exactness doesn't guarantee live exactness.

- Set yaw and pitch directly during the client tick. Don't emulate the mouse.
- Test at several frame rates, with and without the integrated server lagging.
- Compare actual vs predicted state every tick, and log the first tick where they diverge (that's your bug report).
- On deviation above epsilon, replan from the live state. On a safe-to-recover deviation, continue. Otherwise stop with a message.
- Keep a **tick-alignment test** from Phase 0: a known scenario whose trace must match line by line when played through the executor.

---

## 9. Time budgets and kill switches

A plan with no stop rules drifts. Decide these now.

| Trigger | Action |
|---|---|
| Phase 1 passes 10 weeks | Freeze Tier C, ship Tiers A and B only, move on |
| A mismatch stays unexplained for 2 weeks | Write it up in JOURNAL.md, ask in the MCPK/TAS community with the trace attached, and work on something else meanwhile |
| Phase 3 search is too slow for real maps | Add time budgets and caching first; consider Phase 6 only after |
| Exam set score is flat for 3 iterations | Stop tuning. Report the honest number, list the failure categories, and ship |
| Motivation dips | Look at the last milestone clip. If there isn't one, do the next milestone's smallest piece |

**Definition of done (per task):** code merged, tests added, JOURNAL.md has two lines (what you did, what surprised you), STATUS.md updated.

---

## 10. Test-set discipline

| Set | Purpose | Rule |
|---|---|---|
| Golden and fuzz traces | Sim correctness | Only from the real game |
| Catalog | Solver coverage | Labels from construction, envelope, or wiki |
| Oracle set | Solver completeness | Published solutions only |
| Dev maps | Day-to-day debugging | Free to tune on |
| Exam maps | Honest score | Frozen, run at gates only, every run logged |

A bot that clears generated courses but fails hand-built ones is overfit to a narrow distribution. The exam set is the defence.

---

## 11. Working with AI coding models

Create `AGENTS.md` at the repo root (a starter is provided alongside this plan) and begin every session with this plan plus the task ID.

**Task prompt template:**

```
Task <id>: <one sentence>
Context: PLAN.md section <x>, files: <list>
Allowed to modify: <paths>
Interfaces that must not change: <list>
Done when these pass: <exact gradle command>
Do not: edit tests/golden data, add dependencies, change architecture.
Cite: the vanilla class/method or MCPK page for any physics rule. If you can't, say so.
```

**Rules:**

- One task per branch, one commit per task. Read the diff yourself before merging.
- Use a second model to review the first model's physics code against the vanilla source.
- **`physics-notes.md` is drafted by the AI, then reviewed by a second model before Phase 1 starts.** The notes are only trustworthy if they are mechanical: every rule must cite the exact `mc-src/` file and line it came from, and anything that cannot be cited is marked **UNVERIFIED** in the text. The owner still has to read and understand the notes before Phase 1 (that is the interview check), but the drafting is not hand-written.
- Good uses of AI: Gradle, mixins, boilerplate, test harnesses, search optimizations, refactors.
- Risky uses: physics from memory (hallucinated constants), and "fixing" failing tests by loosening them. Golden traces catch both.
- When a model is stuck after two attempts, record the failing trace or test in JOURNAL.md and review it yourself instead of letting it thrash.

---

## 12. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Harness not deterministic | Medium | Gate 0 determinism test; controlled world settings |
| Sim mismatches on obscure blocks | High | Fuzz traces, registry dump plus data-generator cross-check, regression test for every miss |
| Search explodes on long chains | Medium | Segment decomposition, caching, Pareto pruning, backward pass, time budgets |
| Float/yaw precision makes tight jumps unsolvable | Medium | Mirror game numerics, YawRefiner, exact-sim verification |
| Executor drift in live play | Medium | Direct yaw setting, closed loop, tick-alignment test |
| Scope creep (more versions, swimming, etc.) | High | Tier table, non-goals list, one version |
| Overfitting to synthetic maps | High | Frozen exam set |
| Phase left "almost done" | High | Gates, tags, time-boxes |
| First-timer stall on tooling | Medium | Phase 0.0 learning task, M1 slice early |
| Losing motivation | Medium | Milestone clips, kill switches (section 9) |
| Rule or legal problems (servers, maps, code licences) | Medium | Singleplayer-only rule, ADR 0009, permission for maps, no copied code without a licence check |
| Game updates break the mod | Low | Pin versions |

---

## 13. First 14 days

1. Day 1: open five target maps, decide the version, write ADR 0001 and 0008.
2. Day 2: write ADRs 0002-0007 and 0009. Create the repo, Gradle skeleton, CI.
3. Days 3-4: do the modding getting-started guide; get a mod loading with a log line and a keybind (0.3).
4. Days 5-7: recorder (0.4). Record a sprint-jump. Eyeball the trace.
5. Days 8-9: scenario harness (0.5). Run one scenario.
6. Day 10: determinism test (20 runs, identical bytes). Fix any nondeterminism.
7. Days 11-13: read the MCPK wiki pages (Horizontal/Vertical Movement Formulas, Slipperiness, Momentum Threshold) and the vanilla movement code. Start `physics-notes.md` in your own words.
8. Day 14: tick Gate 0 on the checklist and start Phase 0.5.

---

## Appendix A: scaffold commands

```bash
mkdir -p Kairos/{docs/adr,core/src/{main,test}/java,loader/src/main/java,tools,gates}
mkdir -p Kairos/data/{traces,scenarios,catalog,oracle,maps/{dev,exam}}
cd Kairos && git init
touch README.md AGENTS.md docs/{PLAN.md,STATUS.md,JOURNAL.md,physics-notes.md,FAILURES.md}
```

## Appendix B: STATUS.md template

```
# Status
| Phase | Gate | Status | Tag | Date | Notes |
|---|---|---|---|---|---|
| 0   | checklist | [ ] | phase0-done | | |
| 0.5 | M1 clip   | [ ] | phase05-done | | |
| 1   | checklist | [ ] | phase1-done | | |
| 2   | checklist + gate script | [ ] | phase2-done | | |
| 3   | ./gradlew gate3 | [ ] | phase3-done | | |
| 4   | ./gradlew gate4 | [ ] | phase4-done | | |
| 5   | ./gradlew gate5 | [ ] | phase5-done | | |
```

## Appendix C: ADR template

```
# ADR 000N: <title>
Date:
Status: proposed | accepted | superseded
Decision:
Reason:
Alternatives considered:
Consequences:
```

## Appendix D: Credits and references to read (not to copy without a licence check)

- MCPK wiki: movement formulas, slipperiness, momentum threshold, jump lists.
- ParkourCalculator (Fabric 1.21.10, Forge 1.8.9 and 1.12.2): tick-exact TAS planner with an angle solver. Source: GitHub, Leg0shii/ParkourCalculatorMod.
- MPK Mod (MPKMod/MPKMod2): parkour movement tools.
- Baritone: pathfinding and execution design, including its parkour moves. It uses approximate movement, so use it for ideas on route search, not for exact physics.
- Vanilla Minecraft player movement code, read with official mappings, for your version only.
