> **Current status — 2026-11-05: GATE 2 PASS; M3 confirmed.**
>
> All seven findings in the 2026-11-01 review are resolved, and the human-recorded M3
> tight-jump clip is confirmed. The original body and its NOT CLEAN verdict are kept
> unchanged as history; see the two dated addenda at the end. This commit is the
> `phase2-done` gate commit.

# Phase 2 audit — 2026-11-01

**Verdict: NOT CLEAN. Do not tag `phase2-done` and do not treat the current
`CatalogCoverageTest` as Gate 2 evidence.** The Phase 2 solver, replay verifier and
200-plan in-game evidence are largely sound, but the catalog/coverage evidence contains
three gate-blocking defects and several evidence-integrity gaps. Fixing them is cheaper
now than allowing them to contaminate Phase 3 route planning.

This audit covers tasks 2.1–2.8 and the offline part of task 2.9. The 200 in-game traces
themselves are not in question: all 200 verify bit-exactly and land.

---

## Gate blockers

### 1. The ladder catalog rows are physically invalid, and the test enshrines the bad geometry

`data/catalog/ladder_climb2.json` and `ladder_climb4.json` currently put the goal block
directly above the start block:

- `ladder_climb2`: start `[0,64,0]`, goal `[0,66,0]`
- `ladder_climb4`: start `[0,64,0]`, goal `[0,68,0]`

A 1.8-block player standing on the start block needs the space at `y=65` and `y=66`. The
goal block at `y=66` occupies that space, so the start cannot be stood on. The audit probe
built exactly those worlds and `RunupStartBuilder` returned **0 starts** for both.

`CatalogCoverageTest.ladderEntriesAreClimbsOutsideTheSingleJumpScope()` then asserts
`startX == goalX` and `startZ == goalZ`, i.e. it asserts the invalid same-column geometry
and excludes both entries from solve coverage. This is not a defensible scope decision; it
is a test that hides a broken catalog row.

**Buildable replacement:** put the goal one block forward (`goalZ = startZ + 1`) and make
the goal column the wall the ladder is attached to:

- wall: `(startX, startY+1 .. goalY, goalZ)`
- ladder: `(startX, startY+1 .. goalY, startZ)`, facing the wall
- climb `W` only, no jump

The audit probe reached the top of both climbs:

| Entry | Goal block | Top | Tick reached |
|---|---|---|---|
| `ladder_climb2` | `[0,66,1]` | `y=67` | 21 |
| `ladder_climb4` | `[0,68,1]` | `y=69` | 38 |

The ladder entries must stay in the Phase 2 evidence because ladders are Tier B and
required in Phase 2 (PLAN.md 1.1), but they need a real climb test rather than a fake
single-jump exclusion.

### 2. `headhitter_gap1_ceiling2` is mislabeled `infeasible`

The row is `start [0,64,0]`, goal `[0,64,2]`, tag `ceiling2`. A 2-block ceiling means the
bottom of the ceiling is at `y=67` (air space `y=65..66`). A 1-block gap under that
ceiling is an ordinary feasible headhitter jump, not an infeasible target.

Audit probe results:

- canonical 1-wide start/goal with ceiling at `y=67`: `JumpSolver` returns a **5-input**
  plan (`MultiStartSolver.solveJump`, edge margin 0.1)
- 5-wide probe with ceiling at `y=67`: `BeamSolver` returns a **6-input** plan
- ceiling at `y=66` (1-block air space, player does not fit): no plan, as expected

The current label is a false negative in the catalog and makes the catalog coverage claim
incomplete. A feasible-by-construction catalog entry must be solved, not excluded.

`CatalogCoverageTest` also never adds the defining ceiling to the world, so even after a
label change it would test a plain gap, not a headhitter.

**Required:** change `expect` to `feasible` and `label_source` to `construction` (the
catalog reader forbids an `envelope` source on a feasible entry), and make the coverage
world include the 2-block ceiling over the run-up/gap. The ceiling must be wide enough
that a plan cannot simply strafe around it.

### 3. The catalog coverage world is too generous and hides solver failures/false solutions

`CatalogCoverageTest` currently builds:

- a start/run-up platform **5 blocks wide**,
- a goal platform **5 blocks wide and 11 blocks deep**,
- **zero** edge margin,
- `BeamSolver` only, no baseline `JumpSolver`.

That is not a canonical jump world. Consequences observed in the audit:

- `offset_side8_infeasible` solves in **26 inputs** in the 5-wide fixture, even though the
  catalog calls it infeasible. No test catches this because infeasible entries are not
  tested.
- `offset_side1_gap1` can land with only about **1e-8** overlap under the zero margin,
  which is not robust evidence.
- In a 1-wide canonical world, `BeamSolver` alone fails `gap1_flat_sprint`,
  `height_down1_jump` and `ice_gap1_sprint`; the baseline `JumpSolver` solves them. The
  current fixture never exercises that complementarity.

**Recommended canonical gate fixture:** exactly one block wide for the start block, the
run-up path and the goal block; `JumpSolver` first, `BeamSolver` (width 512, yaw delta 15,
tick budget 80) as fallback; edge margin **0.1**. The audit probe solved all **14**
physically feasible single jumps that way:

- 12 via `JumpSolver`
- `offset_side1_gap1` via `BeamSolver` (18 inputs, ~2.4 s)
- `offset_side2_gap2` via `BeamSolver` (17 inputs, ~0.27 s)
- all other feasible entries in milliseconds

For height-up entries the goal should be a real 1-wide support column, not a floating
block, so the 2-block `height_up2_wall` case is a wall and the 1-block step is a step.

---

## Evidence-integrity defects (must fix before the gate)

### 4. Only one infeasible catalog entry has a no-plan test

`gap6_flat_infeasible` appears in solver unit tests. `height_up2_wall`,
`ice_gap8_infeasible` and `offset_side8_infeasible` are never checked for a plan. In the
canonical 1-wide fixture all three return no plan, and `offset_side8` is exactly the entry
that the current 5-wide fixture wrongly solves.

Add a catalog-level test that, for every `expect=infeasible` entry, runs the canonical
world through `JumpSolver` and then `BeamSolver` and requires **no plan**. This is the
Gate 2 false-solution check (0% target). It is slow with width 512 (about 100 s for all
four), but it is a one-time gate cost and it is the metric that matters.

### 5. Replay tests skip when evidence is missing

- `SampleReplayTest` still uses `assumeTrue(traceDir.isDirectory(), ...)`.
- `YawRefinerReplayTest` still uses `assumeTrue(trace.isFile(), ...)`.

Once the evidence is committed, a missing directory/file must be a **failure**, not a
skip. Otherwise a clean checkout without the manual artifacts can report a green gate.
Change both to `assertTrue` and update the class comments.

### 6. The sample verifier does not check the trace header or `using`

`SampleReplayVerifier` checks scenario/tick/te/input/state but never checks:

- `"v"` is `TraceLine.FORMAT_VERSION` (1),
- `"mc"` is `"1.8.9"`,
- `"using"` is `false`.

A trace with a wrong format version, wrong game version, or an item-use state would pass.
Add those checks to the anchor and every tick line, and add mutation tests in
`SampleReplayVerifierTest` for all three.

### 7. Catalog coverage allows a zero-jump plan

`CatalogCoverageTest.assertPlanLands` asserts `jumps <= 1`, not `jumps == 1`. The plans are
supposed to be single jumps; a walk/fall plan must not pass. Assert exactly one jump for
the single-jump entries. The ladder climb test is the separate zero-jump case.

---

## Defensible limitations (document, do not block the gate)

1. **Start-region grid:** `StartRegion` covers the centre and the eight positions inset by
   0.2 blocks, i.e. the corners of the legal 0.4 x 0.4 footprint. It is not a continuous
   start-space proof. This is documented in `StartRegion`; keep it documented rather than
   claiming exhaustive start freedom.
2. **CatalogRunner scope:** `CatalogRunner` reports metadata/validation, not solve results.
   The solve coverage lives in `CatalogCoverageTest`. This is a reasonable separation, but
   the docs should say so explicitly so the runner line is not mistaken for a solve result.
3. **200-plan sample diversity:** the sample is all stone flat gap/drop jumps (gaps 1–4,
   height 0/−1, five yaws, five start x). It meets the literal Gate 2 bullet — 200 real
   in-game plans, all bit-exact and landing — but it does not exercise ice, slab, offset,
   headhitter or ladder geometry in game. A small additional in-game catalog batch would
   strengthen Phase 2; it is a residual risk, not a reason to block the tag if time is
   short.
4. **Timing budget:** the 1,260 ms budget is `min(10 x median, 2000 ms)` rounded up to
   10 ms, measured on the 200 easy sample plans (median 125.184 ms). It is a Phase 2
   product baseline, not a Phase 3 route-segment guarantee; revisit it when Phase 3 has
   real route segments.

---

## Gate 2 status after the audit

| Check | Status | Evidence |
|---|---|---|
| 100% feasible-by-construction catalog targets solved | **BLOCKED** | Ladder rows invalid; headhitter mislabeled; coverage world not canonical |
| 200-plan in-game replay lands | **PASS (200/200)** | `data/replay/sample_01_result.json` after verifier ULP fix |
| Median solve time recorded + budget set | **PASS (baseline)** | median 125.184 ms; proposed budget 1,260 ms |
| M3 tight-jump video | **PENDING** | human recording; keep local, never commit |

The 200 traces, result file and timing file are valid deliverables, but they must not be
committed with the current lax coverage/replay tests. Fix the blockers first, then commit
task 2.9 and finish the gate.

---

## Addendum — 2026-11-05: blockers resolved, evidence accepted

The 2026-11-01 body above is kept as history. All seven findings are resolved in the
accepted Task 2.9/2.10 work:

1. **Ladder rows (blocker 1):** `ladder_climb2`/`ladder_climb4` now use goals
   `[0,66,1]`/`[0,68,1]`; the coverage fixture builds the real wall and ladder column
   (`ladderWorld`), and `CatalogCoverageTest.ladderEntriesClimbTheirWalls` replays both
   climbs. Evidence: 2/2 climbs reach the wall top at zero-based ticks 21/38.
2. **Headhitter row (blocker 2):** `headhitter_gap1_ceiling2` is now
   `feasible`/`construction`; the canonical world adds the defining 5-wide, 2-block
   ceiling over the run-up and gap, and `JumpSolver` finds a 5-input plan.
3. **Canonical coverage world (blocker 3):** one-block-wide start/run-up/goal,
   `edgeMargin` 0.1, `JumpSolver` first and `BeamSolver` fallback. Evidence: all 14
   feasible single jumps solve, 12 via `JumpSolver` and
   `offset_side1_gap1`/`offset_side2_gap2` via `BeamSolver` (18/17 inputs). Task 2.10a
   removed the fixture's overlapping support/goal region; the effective world is
   unchanged.
4. **Infeasible coverage (finding 4):** `gap6_flat_infeasible`, `height_up2_wall`,
   `ice_gap8_infeasible` and `offset_side8_infeasible` all return no plan from both
   solvers in the canonical world.
5. **Replay skips (finding 5):** `SampleReplayTest` and `YawRefinerReplayTest` assert
   their committed evidence; missing traces/result files fail, never skip.
6. **Verifier headers (finding 6):** `SampleReplayVerifier` checks `v = 1`,
   `mc = "1.8.9"` and `using = false` on the anchor and every tick line;
   `SampleReplayVerifierTest` has mutation tests for all three.
7. **Zero-jump plans (finding 7):** `CatalogCoverageTest.assertPlanLands` requires
   exactly one jump per single-jump plan; the ladder test is the separate zero-jump
   climb case.

### Current Gate 2 status

| Check | Status | Evidence |
|---|---|---|
| 100% feasible-by-construction catalog targets solved | **PASS** | 14/14 single jumps (12 `JumpSolver` + 2 `BeamSolver`); 2/2 ladders; 4/4 infeasible entries return no plan; catalog 21 entries (16 feasible, 4 infeasible, 1 unknown) |
| 200-plan in-game replay lands | **PASS (200/200)** | `data/traces/replay/sample/` (200 traces) and `data/replay/sample_01_result.json`; corpus SHA-256 `0B92DB878F77F490FB9CB57F76BDDBE63A21BC9F79833D0E70FC856CDE348672`, result SHA-256 `2A4AC976E536F530D3F9F06F829F99300BB96D80F8FAD7ED4F07F7A2DEF0BEAC` |
| Median solve time recorded + budget set | **PASS** | median 125.184 ms; Gate 2 budget 1,260 ms = `min(10 x median, 2000 ms)` rounded up to 10 ms; `data/replay/sample_01_timing.json` SHA-256 `4829772971105F49AC24954EF0E84B279557516F78534A8831CB8FDCEE7B47DD` |
| M3 tight-jump video | **PASS** | human-recorded in the real 1.8.9 dev client with `/kairos scenario run yaw_refiner_tight_wall_01`; clip kept local, never committed; rerun trace 7,500 bytes, SHA-256 `C2D192D812B7CB09BCBDC793A5FE66F028A6F24FAEEC0D479C006A3B895832E0` |

Verification: `.\gradlew.bat :core:cleanTest :core:test --console=plain --offline`
passes with 163 tests, 0 failures, 0 errors and 0 skipped in 44 classes; the separate
loader build passes on JDK 21; the frozen replay/scenario paths are unchanged.

**This addendum accepts the Task 2.9/2.10 evidence for commit.**

---

## M3 addendum — 2026-11-05: tight-jump clip confirmed

The human recorded the M3 clip in the real 1.8.9 dev client with
`/kairos scenario run yaw_refiner_tight_wall_01`. The rerun recorded 19 ticks and
reproduced the committed 20-line trace byte-for-byte:

- `data/traces/replay/yaw_refiner_tight_wall_01.jsonl`
- 7,500 bytes, SHA-256 `C2D192D812B7CB09BCBDC793A5FE66F028A6F24FAEEC0D479C006A3B895832E0`
- client log: `run 1/1 done: 19 ticks -> yaw_refiner_tight_wall_01.jsonl`

The clip itself is kept outside the repository and is not committed.

**Gate 2 verdict: PASS. This commit is tagged `phase2-done`.**
