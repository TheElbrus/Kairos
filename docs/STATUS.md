# Status

Environment for task 0.2 (exact versions used; CI reproduces the JDK):

- JDK: Eclipse Temurin 25.0.4.1+1 (LTS), 64-bit, Windows (local) / ubuntu-latest (CI)
- Gradle: 9.8.1, via wrapper; distribution `gradle-9.8.1-bin.zip` with pinned sha256 `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`
- JUnit: 5.14.4 (Jupiter, via `junit-bom`) - test scope only
- ArchUnit: 1.5.1 - test scope only
- CI: GitHub Actions, `actions/setup-java@v6` with Temurin 25, runs `./gradlew :core:test`
- Loader (separate build): Gradle 8.8 wrapper, essential Loom 0.10.0.5, MCP `stable_22`, Forge `1.8.9-11.15.1.2318-1.8.9`; Gradle runs on JDK 21 (`~/.jdks/ms-21.0.12.1`), game bytecode on Java 8 via Foojay-provisioned Temurin `8u504-b01` (`.gradle-home/jdks/`)

| Phase | Gate | Status | Tag | Date | Notes |
|---|---|---|---|---|---|
| 0   | checklist | [x] | phase0-done | 2026-10-17 | 0.1-0.6 done, 0.6 reviewed APPROVE; Phase 0 closing |
| 0.5 | M1 clip   | [x] | phase05-done | 2026-10-18 | 0.5.1 FlatSim exact on 3 flat goldens (18/20/25 ticks); 0.5.2 solver: 1-4 block gaps solved (jump ticks 8/12/15/19), 6 refused; 0.5.3 M1: the solver's 4-block-gap plan recorded in game replays bit-exact (GapGoldenTest, 26 tests green); clip kept locally, never committed |
| 1   | checklist | [x] | phase1-done | | 1.0 done: 198 blocks / 7806 states dumped from the live registry, `data/1.8.9/blocks.json` committed (SHA-256 B625023BEA6F2576BB87A66C3B17EF2F46DC816A0E5F46617BC583B0F16D50D7, schema 2), `BlockData` + `BlockDataDumpTest` green (34 tests). 1.1 done: `MovementSim` flat movement (walk/sprint/jump/air) with 7 L0 formula tests, 10 new live-recorded scenarios, all 13 flat goldens bit-exact (42 tests). 1.2 done: `CollisionWorld` + `MovementSim` collision (game's Y/X/Z AABB sweep, step-up, sneak edge clamp) from dumped boxes; 6 new collision scenarios live-recorded and bit-exact (line counts 41/47/51/61/71/71), harness restores the pre-run world and resends S23 states (44 tests); 1.3 done: per-block slipperiness from the dump (ice/packed ice 0.98, stone/air 0.6) via `CollisionWorld.slipperinessAt` at both `moveEntityWithHeading` f4 sites; 3 new scenarios live-recorded and bit-exact (line counts 71/71/81), Tier C (slime bounce) deferred until after Gate 2 (51 tests); 1.4 done: schema-2 dump (block connectors + per-state `solid` side masks, old boxes/slipperiness/climbable unchanged), neighbour-dependent fence/wall/pane collision and ladder/vine climbing from `ForgeHooks`/`EntityLivingBase`; 9 new scenarios live-recorded and bit-exact (line counts 66/66/51/31/31/21/41/41/41), `CollisionWorldConnectorTest` + `MovementSimShapesGoldenTest` (62 tests); 1.4.1: data-sized shape scratch (hopper/cauldron 5-box states); 1.5 done: sprint state machine + `jumpTicks` cooldown proven by 4 live goldens (wall stop 41, W release 54, double-tap 35, held-jump ceiling 41) and 11 transition tests (75 tests); 1.6 done: `MathHelper` sine/cosine table mirrored exactly, `ms * 0.98f` strafe axis, real-yaw `moveFlying` (game angle shape), sprint jump boost along the heading (its own parenthesized angle), scope throws removed; 4 live yaw goldens bit-exact (41/41/37/41), `MathHelperTest` + `MovementSimYawTest` + `MovementSimYawGoldenTest` (86 tests); lily pad deferred (Tier D water); 1.7.1 done: deterministic 1000-scenario fuzz corpus (seed 0x4B4149524F533137, 8,173,724 bytes, SHA-256 412A5FD39C4EE7FB136474DD6AD19BC1598D4D1C5FA87353F6DC7D92F1A29319, 100 ticks each, LF-only) pinned by generator-equality/shape/palette/disjointness tests, pure `FuzzScenarios` reader, `/kairos scenario fuzz <count> [resume]` batch harness (preflight parse+resolve, overwrite refusal, progress every 25); 3-scenario live smoke 101 lines/trace, 0 CR, traces deleted; 93 tests; 1.7.2 done: 1000 live fuzz traces committed under `data/traces/fuzz/` (36,503,106 bytes, 101 LF-only lines each, 0 CR), `FuzzGoldenTest` replays every trace bit-exact; 94 tests; 1.8 done: `SimBench` on fuzz_0000 (100-tick segment, 2,000,000-step warmup, 10,000,000 steps x 5 reps, median) recorded 14,980,777.438 ticks/s (66.752210 ns/tick) on Temurin 8u504-b01, the primary runtime, and 14,636,751.683 ticks/s (68.321170 ns/tick) on Temurin 25.0.4.1; `MovementSimAllocationTest` measured 0 bytes over 1,000,000 steps (0-byte no-step baseline); gate 95 tests, 0 failures, 0 skipped (40 goldens + 1000 fuzz all match, architecture passed); Gate 1 evidence in `gates/phase1.json`; tag pending verification |
| 2   | checklist + gate script | [ ] | phase2-done | | |
| 3   | ./gradlew gate3 | [ ] | phase3-done | | |
| 4   | ./gradlew gate4 | [ ] | phase4-done | | |
| 5   | ./gradlew gate5 | [ ] | phase5-done | | |

Phase 0 tasks (PLAN.md section 7):

| Task | What | Status |
|---|---|---|
| 0.0 | Java/Gradle/modding basics | [ ] |
| 0.1 | ADRs 0001-0009, AGENTS.md | [x] |
| 0.2 | Gradle skeleton + CI (`:core:test`, Java 8 bytecode) | [x] |
| 0.3 | Mod loads, logs a line, one keybind | [x] |
| 0.4 | Recorder writes one trace line per tick | [x] |
| 0.5 | Scenario harness `/Kairos scenario run <name>` | [x] |
| 0.6 | `physics-notes.md` draft with citations | [x] reviewed |

Phase 0.5 tasks (PLAN.md "Phase 0.5: Thin vertical slice"):

| Task | What | Status |
|---|---|---|
| 0.5.1 | Quick-and-dirty sim: flat ground, walk/sprint/jump, air movement, drag, full blocks only | [x] |
| 0.5.2 | Brute-force solver for one straight gap (constant yaw, try every jump-start tick) | [x] |
| 0.5.3 | Play the solved inputs in game through the harness | [x] |
