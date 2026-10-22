# Journal

## 2026-10-17 - Task 0.1: ADRs 0001-0009 + AGENTS.md
- Done: nine ADRs on the Appendix C template plus the AI rulebook; corrected the plan's nonexistent 1.13-era "data-generator reports" cross-check and added the ParkourCalculator GPL-3.0 note.
- Surprised: 1.8.9 predates the vanilla data-generator reports, so block cross-checks rest on registry state counts plus per-shape spot-check traces.

## 2026-10-17 - Task 0.2: Gradle multi-module skeleton + CI
- Done: `core` module on Gradle 9.8.1 (checksum-pinned wrapper), Java 8 bytecode via `options.release = 8`, placeholder class + JUnit test, ArchUnit rule "core never depends on net.minecraft.*", CI workflow running `./gradlew :core:test` on Temurin 25.
- Surprised: the wrapper jar checked into the gradle/gradle repo at tag v9.8.1 does not match the officially published 9.8.1 wrapper checksum, so the wrapper must be generated from the distribution; Gradle 9 needs `junit-platform-launcher` explicitly on the test runtime classpath; JDK 25 warns that source/target 8 is obsolete while still supporting it; a `gradlew` committed from Windows lands as mode 100644, so CI fails until `git update-index --chmod=+x gradlew`.

## 2026-10-17 - Task 0.3: minimal Forge 1.8.9 loader
- Done: separate `loader-1.8.9/` build (Gradle 8.8, Loom 0.10.0.5, MCP stable_22) that logs `[Kairos] Kairos 0.1.0 loaded. core PLAN_VERSION=2.0` on startup and registers a P keybind whose chat line echoes `dev.kairos.core.Kairos.PLAN_VERSION` -- proof the core jar is shaded into the mod; `runclient.ps1`/`runclient.sh` work around the broken `runClient` task via `printRunClientCommand`; banner in `run/runclient.log` and the in-game chat line verified in the dev client.
- Surprised: Loom launches 1.8.9 through `net.fabricmc.devlaunchinjector.Main` with no game args, so the manual runner only needs jvmargs + classpath; the foojay-provisioned JDK 8 lands at `.gradle-home/jdks/temurin-8-amd64-windows/jdk8u504-b01/` (glob, do not hardcode the patch version); FML logs a missing-version warning unless `@Mod` carries an explicit `version` even when it sits in `mcmod.info` (FMLModContainer.java:196).

## 2026-10-17 - Task 0.4: trace recorder + frozen trace format v1
- Done: ADR 0010 freezes the JSONL format (loader-1.8.9/run/kairos/traces/<name>.jsonl, UTF-8, `\n`, relative `te`, gap guard); `core` gained `dev.kairos.core.trace` (TraceInput/TraceLine/TraceWriter, NaN/Infinity rejected, floats never widened) with byte-pinning tests (9 core tests green incl. ArchUnit); loader records at `ClientTickEvent.END` via `KairosRecorder` with client-side `/kairos record start|stop|status`; live 688-line trace (1 anchor + 687 ticks, 431 of them jumping, 432 sprinting) validated: fixed key order on all lines, `te = tick + 1`, zero CR bytes, gap guard never fired.
- Surprised: `:core:test` does not rebuild the core jar, so the loader (separate build) first compiled against a stale jar missing `dev.kairos.core.trace` -- run `:core:jar` (or `:core:build`) before building the loader; also FML posts `ClientTickEvent` from `onPreClientTick`/`onPostClientTick` (FMLCommonHandler.java:331/:336), which `Minecraft.runTick()` calls at :1456/:1777 around `updateEntities()` (:1739) exactly as the recorder's END-phase assumption requires.

## 2026-10-17 - Task 0.5: scenario harness (arena, scripted inputs, runmany determinism)
- Done: `core` gained `dev.kairos.core.scenario` (dependency-free JSON reader, Scenario model, parser with named-field errors, ScenarioLocator with override/marker/fallback search order) with 11 new tests (18 core tests green via `./gradlew :core:test`); the loader gained `KairosScenarioRunner` (server-side arena build with flag-2 setBlockState, teleport via `setPlayerLocation`, settle on steady ground `(0-0.08)*0.98`, fake `MovementInput` and a scripted sprint `KeyBinding`, per-tick recording through `KairosRecorder`, runmany cleanup + in-process byte compare) plus `/kairos scenario run|runmany`; live: `/kairos scenario run sprintjump_flat_01` wrote 19 valid lines (1 anchor + 18 ticks, zero CR bytes, `te = tick + 1`, all lines match the frozen shape), `/kairos scenario runmany sprintjump_flat_01 20` produced 20 files whose `Get-FileHash -Algorithm SHA256` is ONE hash: `0794DCA7449A927001369B63BE9BDED9C37AEBF51DB441F2B88D79F232A81247`.
- Surprised: the first live runmany caught a real determinism bug the unit tests could never see -- run 8 of 20 differed from run 1 at recorded tick 9 only in `in.sprint` (`0` while the state showed `sprinting:true`), because `Minecraft.runTick()`'s physical keyboard loop re-sets binding states from queued OS events between our `EventPriority.HIGHEST` START handler and physics (`Minecraft.java:1561-1563`); a stray press of the sprint key (default keycode 29, left ctrl, `GameSettings.java:145`) clobbered the faked state; fixed by swapping `GameSettings.keyBindSprint` (a plain public field) for a `ScriptedSprintKey` during runs: its key code is `KEY_NONE` (0), which `setKeyBindState`/`onTick` early-return on (`KeyBinding.java:32-48`) and the keyboard loop re-maps away from 0 (`Minecraft.java:1562`), and its `isKeyDown` is overridden so even `unPressAllKeys` (`Minecraft.java:1261`) cannot clear it -- physics (`EntityPlayerSP.java:607,:613`) and the recorder now both read the script, never the keyboard.

## 2026-10-17 - Task 0.6: physics-notes.md (cited reference + worked example)
- Done: `docs/physics-notes.md` written docs-only from this repo's `mc-src/` tree: tick shape (runTick order, FML START/END, World.updateEntityWithOptionalForce, player onUpdate/onLivingUpdate chain), input-to-sprint chain, `EntityLivingBase.onLivingUpdate` core, all formulas with the exact float/double mix (widened constants listed: `(double)0.42f = 0.41999998688697815`, `(double)0.98f = 0.9800000190734863`, ...), collision order incl. `AxisAlignedBB` offsets and `resetPositionToBB`, ladder/water/lava/flying cited-but-untested, MCPK wiki cross-checks (section 2.10) plus UNVERIFIED items; worked example reproduces six ticks (0, 5, 6, 10, 16, 17) of `loader-1.8.9/run/kairos/traces/sprintjump_flat_01_run01.jsonl` and a throwaway float-exact checker (temp dir, not committed) reproduces all 18 ticks with identical `Double.toString` output.
- Surprised: the ground friction is `0.6f * 0.91f = 0.54600006f` (not `0.546`) and the sprint aimove is `0.13000001f` because the `0.3f` modifier widens to `0.30000001192092896` -- last-digit trace reproduction needs those; the momentum threshold truncates the jump apex (tick 10 `posY` does not move; the theoretical next step `0.003016...` is dropped); the landing tick (16) uses the AIR friction because `f4` is recomputed before `moveEntity` while `onGround` is still false; and all archived-review corrections (pushOutOfBlocks in the sequence, `!flag2`, `EntityPlayerSP.java:663`, `EntityPlayer.java:534-538`) check out against this tree.
- Least-sure claims (for the second reviewer): (1) Java 8 (game) and modern-JDK float rounding are assumed identical -- the checker matched this trace on JDK 25 but was never run on a Java 8 JVM; (2) the unloaded-chunk gravity fallback `motionY = -0.1/0.0` (`EntityLivingBase.java:1283`) was never observed, only read; (3) `newPosRotationIncrements` is assumed to stay 0 for the local player; (4) the client-vs-server split for entity pushing (`collideWithNearbyEntities` only when `!worldObj.isRemote`) is code-read, not traced, and the integrated server could still correct the client; (5) the MCPK momentum-threshold "same moment" equivalence in section 2.10 is my phrasing, not a quote from the page.

## 2026-10-17 - Task 0.6 review (second model): APPROVE
- Done: independent review of physics-notes.md by a second model session: ~70 citations
  line-checked in mc-src/; worked-example numbers re-derived in a different runtime
  (.NET IEEE-754) and compared bit-for-bit with the trace (ticks 0, 5, 6, 16 all exact);
  one citation-range nit fixed (paused frames now Minecraft.java:1735-1740, 1749-1753).
- Surprised: air friction on the landing tick and the tick-10 apex truncation reproduce
  to the last digit in an unrelated runtime; no physics rule needed correction.

## 2026-10-18 - Task 0.5.1: FlatSim thin slice, 3 flat goldens exact
- Done: throwaway `dev.kairos.core.sim.FlatSim` (yaw=0, one floor block at y=64..65, flat
  subset of docs/physics-notes.md, every rule cited, `UnsupportedOperationException` for
  yaw != 0.0f or sneak) + `FlatSimGoldenTest` replaying the committed goldens
  `data/traces/flat/{sprintjump,walk,sprint}_flat_01.jsonl` (byte-identical copies of the
  loader output; SHA256 pairs match) with `Double.compare == 0` on pos/vel/flags and pinned
  line counts 19/21/26; new `data/scenarios/sprint_flat_01.json` (15 ticks sprint-forward,
  10 ticks no keys) recorded live: walk 21 lines, sprint 26 lines, both "no issues";
  `.\gradlew.bat :core:test` green (21 tests: 3 goldens exact at 18/20/25 ticks + 18
  existing; corrected 2026-10-18 in task 0.5.3 -- this hand count missed ArchitectureTest's
  `@ArchTest`); anti-vacuity self-check: threshold 0.005 -> 0.003 fails with "first divergence
  at tick 10, field posY: expected (trace) 66.24918707874468 but sim produced
  66.25220334025373", reverted, green again.
- Surprised: (1) the collision-box extents PERSIST across ticks -- `resetPositionToBB`
  only re-derives pos from the box (Entity.java:756-760) and `setPosition`, which rebuilds
  the box from pos, is not part of the normal physics tick; recomputing pos +/-
  (double)0.3f each tick diverges at tick 13 with a 1-ulp `posZ` (sim 4.3243237719333125
  vs trace 4.324323771933313), so State carries minX/maxX/minZ/maxZ; (2) after that one
  fix, the walk and sprint traces matched with zero further sim changes; (3) the repo root
  has `settings.gradle`, not `settings.gradle.kts` (that is the loader's nested build), so
  `ScenarioLocator.ROOT_MARKER` never matches at the root and production finds `data/`
  only via the locator's fallback parent walk; the test accepts either marker.

## 2026-10-18 - Task 0.5.1-hygiene: LF-only goldens + the real repo-root marker
- Done: added `.gitattributes` (`*.jsonl text eol=lf`, the only line); `ScenarioLocator`
  `ROOT_MARKER` is now `settings.gradle` (the actual root build file; the loader's nested
  `settings.gradle.kts` is deliberately not a marker) and its javadoc says so; loader search
  from `loader-1.8.9/run` confirmed via a temp probe: resolves
  `<root>/data/scenarios/walk_flat_01.json` through the marker walk (loader-1.8.9 has no
  `settings.gradle`, the root has it); `FlatSimGoldenTest` back to a single marker plus an
  ADR 0010 LF guard reading raw bytes -- demonstrated on a CRLF copy of the walk golden
  ("golden traces must be LF-only, ADR 0010: ... has a 0x0D byte at offset 294"), then
  re-materialized; `Remove-Item data\traces\flat\*.jsonl; git checkout -- data/traces/flat/`
  leaves all three goldens at 0 CR bytes and raw-identical to the recordings
  (`git hash-object --no-filters` 51c9bc80/1af789b4/a6e6d59d == HEAD blobs == loader files);
  `.\gradlew.bat :core:test` green (21 tests; corrected count).
- Surprised: the committed blobs were correct all along -- only the worktree checkout was
  CRLF (core.autocrlf=true, no .gitattributes): `git hash-object --no-filters` on the
  worktree gave f01a15df/d5b1720c/f9ec41f5 vs the LF blobs, purely from the 19/21/26 CR
  bytes, because Part B's SHA256 comparison ran on the fresh LF copies before the branch
  switch; and the old `settings.gradle.kts` marker never matched any ancestor of this repo,
  so the locator's marker path had silently been dead code, with the fallback walk doing
  all the work.

## 2026-10-18 - Task 0.5.2: brute-force solver for one straight gap
- Done: new `GapWorld` (floor top y=65, one straight +Z gap, `NONE` sentinel; the support
  test is the strict AxisAlignedBB z early-out `k < maxZ && k+1 > minZ`, AxisAlignedBB.java:134)
  and `GapSolver` (sprint + single-tick jump, scans jump-start ticks ascending, `Plan`
  {sprintTicks, jumpTick, tailTicks, landingZ, landTick}); the only public `FlatSim` change
  is the additive `step(State, TraceInput, GapWorld)` (the 2-arg method delegates to
  `GapWorld.NONE`, and the 3 flat goldens still pass byte-exact); `GapSolverTest`
  independently replays every plan and asserts the landing criterion (onGround, y == 65.0
  exactly, box strictly overlapping a far-floor column) plus determinism, strict-edge
  geometry and argument validation; `.\gradlew.bat :core:test` green (25 tests; corrected
  count -- the hand counted entries missed ArchitectureTest's `@ArchTest`). Plans for
  `GapWorld.of(5, n, 12)`: 1 block -> jump 8, land 19, z=6.03334; 2 -> 12/23, z=7.15880;
  3 -> 15/26, z=8.00089; 4 -> 19/30, z=9.12340; 6 blocks -> empty.
- Deviation from the task text (recorded loudly): the solver starts from the real
  post-settle anchor -- `pos [0.5, 65.0, 0.5]`, `vel [0.0, -0.0784000015258789, 0.0]`,
  `onGround`, `colV` -- exactly what all three goldens' tick:-1 lines record, NOT the
  literal all-zero velocity/flags; the -0.0784 is the standing gravity step held by the
  ground clamp (notes section 2.8 step 11) and a zero start would shift every run.
  `TAIL_TICKS` is 12, not the "e.g. 10": the arc lands exactly 11 ticks after the jump
  (sprintjump golden: jump 5, land 16), so the window must observe tick J+11.
- Surprised: gap 4 only clears via the jump pressed on the tick AFTER the box has left the
  near floor (start-of-tick box z=5.49424, its minZ 5.194 > 5.0): `onGround` is still true
  from the previous tick's clamp, the jump only reads that flag (not actual support), and
  the ascent never queries the floor -- J=18 falls into the gap, J=19 lands at z=9.12340,
  0.12 blocks past the far edge; gap 3's earliest plan (J=15) lands at z=8.00089, only
  8.9e-4 past the far edge, so one ulp-level difference would have pushed it to J=16 and
  changed the plan; and the strict faces mean a box starting exactly at z=5.0 is not
  supported by the near floor at all (touching faces do not block; also asserted in the
  test).

## 2026-10-18 - Task 0.5.3: M1 -- solve, play, record, replay (4-block gap cleared in game)
- Done: new `data/scenarios/sprintjump_gap4_01.json` (near floor y=64 z=-2..4, gap z=5..8,
  far floor y=64 z=9..20, air corridor y=65..70; inputs = the 0.5.2 plan: 19 sprint, 1 jump,
  11 sprint); the user started the M1 screen recording, ran
  `/kairos scenario run sprintjump_gap4_01` in the dev client, and it worked fully in-game
  ("1/1 run done in 31 ticks"); the recorded
  `loader-1.8.9/run/kairos/traces/sprintjump_gap4_01.jsonl` (32 lines, 0 CR bytes, 10703
  bytes, SHA256 FC1F27BCAEAE5C2D46D5A57036D6CF496BE3F63FB8DAE1377BC1BB240ABAB8A3) was copied
  byte-for-byte to `data/traces/gap/` (hashes match; recorder paths asserted before the
  run); new `GapGoldenTest` replays it against `FlatSim.step(..., GapWorld.of(5, 4, 12))`
  with `Double.compare == 0` on every field, pins the tick:-1 anchor to the solver's start
  state, and cross-checks M1: `GapSolver.solve(WORLD, 25)` must return the plan the scenario
  encodes (sprintTicks 19, jumpTick 19, tailTicks 11, landTick 30) and the final line must
  have posY == 65.0 and posZ == plan.landingZ exactly (9.123401902694745);
  `.\gradlew.bat :core:test` green (26 tests, 0 failures -- confirmed against the JUnit
  XML). The clip itself stays with the user, uncommitted.
- Surprised: the real game reproduced the sim bit-for-bit, including the jump pressed on
  tick 19, the tick AFTER the box left the near floor (`onGround` persisting one tick held
  in the live client) and the landing at z=9.123401902694745 == `plan.landingZ` exactly; the
  corridor's z<=4 pre-jump segment is the same treadmill as the sprint golden, so those
  ticks are bit-identical to it; `GapGoldenTest` duplicates the private trace-parsing
  helpers from the frozen `FlatSimGoldenTest` (deliberate, frozen file); and every earlier
  journal test count had silently missed `ArchitectureTest`'s `@ArchTest` (corrected above:
  21/21/25 -- the JUnit XML is the source of truth).

## 2026-10-18 - Task 1.0: BlockDumper -- 1.8.9 registry dumped and committed (198 blocks / 7806 states)
- Done: Part A: new `loader-1.8.9/.../BlockDumper.java` behind `/kairos dumpblocks`
  (USAGE + wiring in CommandKairos/KairosMod, README updated). It enumerates
  `Block.blockRegistry.getKeys()` (Block.java:204) sorted by registry name and per state
  from `getValidStates()` (BlockState.java:82), and dumps the entity-vs-block collision
  boxes through the real chain -- Entity.moveEntity (Entity.java:553) ->
  World.getCollidingBoundingBoxes (World.java:1003) -> Block.addCollisionBoxesToList
  (Block.java:613) -> Block.getCollisionBoundingBox (Block.java:620) -- with a huge mask,
  entity=null and a probe world whose only non-air block is the state under test; plus
  `slipperiness` (Block.java:321) and `climbable` (isLadder, Block.java:1250). Schema 1:
  keys and states sorted, one state per line, LF only, Double/Float.toString numbers
  (TraceWriter precedent), a registry-count cross-check that aborts red without writing,
  temp file + atomic move, success line with the SHA-256. Part B: the user ran the command
  twice in the dev client (the first try had only seen stale classes -- the client was
  still up from before the build; relaunching fixed it); both runs agreed
  (blocks=198 states=7806, 1391703 bytes, 0 CR bytes, SHA-256
  42910374D9B261862E76D16C76AB2E09367B5988B600AC7888ADE5632E00672E). The dump was copied
  byte-for-byte to `data/1.8.9/blocks.json` (hashes equal), `.gitattributes` gained
  `*.json text eol=lf` (git reported no existing *.json as modified, so no
  re-materialization was needed; the four scenario json files in this worktree still hold
  CRLF from older checkouts and will materialize as LF on the next checkout), and new pure
  `core` code: `dev.kairos.core.block.BlockData` (parse/validate/lookup only, no physics)
  and `BlockDataDumpTest` (LF-only raw-byte guard, header vs contents, pinned 198/7806,
  spot-checks with mc-src citations: slab BlockSlab.java:46-57/:71-73, stairs
  BlockStairs.java:89-95/:225-300/:302-380/:385-389, trapdoor BlockTrapDoor.java:70-72/:95/
  :99, fence BlockFence.java:57-95, ladder BlockLadder.java:33-35/:45/:57, vine
  BlockVine.java:131-133, ice BlockIce.java:31). `.\gradlew.bat :core:test` green: 34
  tests, 0 failures (JUnit XML).
- Surprised: (1) the task text's `getCollisionBoundingBoxFromPool`/`addCollidingBlockToList`
  do not exist in this MCP stable_22 mapping; the chain above is the real one and
  `World.getCollisionBoxes` (World.java:1067) is only the null-entity convenience wrapper;
  (2) ladders DO collide in 1.8.9 -- BlockLadder.java:33-36 sets a 1/8-thick plate on the
  FACING side -- contrary to the task text; vines really have none
  (BlockVine.java:131-133); both are climbable (BlockLadder.java:137, BlockVine.java:366);
  the dump follows the source, is never edited to match the task text, and the test pins
  the thin ladder plate; (3) some blocks only get their real bounds from
  `setBlockBoundsBasedOnState` (anvil BlockAnvil.java:76-83) which the live client calls
  from the render path (BlockModelRenderer.java:34) and ray trace (Block.java:776) but NOT
  from the collision path, so the dumper primes the bounds before every query -- without
  priming the anvil would be dumped with its constructor-default box; this priming is a
  no-op or redundant for every block whose collision override computes its own box;
  (4) `minecraft:piston_extension` derives its box from a tile entity
  (BlockPistonMoving.java:125-137); the probe has none, so the dump has the instance
  default (documented, not special-cased -- that block only exists while a piston moves);
  (5) fence collision comes from per-connection branches inside
  `addCollisionBoxesToList` (BlockFence.java:57-95), not from a state-derived bound: with
  all-air neighbours all 16 property combinations produce the same lone post box
  (0.375,0,0.375,0.625,1.5,0.625), which is what the dump shows for every state.

## 2026-10-18 - Task 1.1: MovementSim -- flat movement rewrite, 13 flat goldens bit-exact
- Done: new pure `dev.kairos.core.sim.MovementSim` (walk/sprint/jump/air acceleration, drag,
  the 0.005 threshold, the sprint state machine; every rule cited to notes+mc-src; throws for
  yaw != 0, strafe and sneak) taking over `FlatSim`'s role for the flat subset (FlatSim /
  GapWorld / GapSolver untouched and still green); `MovementSimL0Test` (7 tests: first jump
  tick, sprint-jump worked-example tick 5, apex 1.24918707874468 + 12-tick jump duration,
  terminal fall -3.9200038147008747, walk/sprint terminal advances
  0.21585903893404823/0.28061673748548444, momentum threshold 0.004/0.005, scope guards);
  `MovementSimFlatGoldenTest` replays every `data/traces/flat/*.jsonl` bit-exact (pinned
  table 3 -> 13; line counts 21/26/19 + 46/56/46/56/22/37/37/37/31/51); 10 new
  `data/scenarios/*_flat_01.json` recorded live by the user in one sitting and copied
  byte-for-byte to `data/traces/flat/` (0 CR bytes; the 3 old goldens unchanged);
  `.\gradlew.bat :core:test` green: 42 tests, 0 failures (JUnit XML), all 13 golden replays
  exact on the first run.
- Surprised: (1) the air-wap scenario passed first try -- the sprint re-engage mid-air is
  modelled without an onGround check (EntityPlayerSP.java:613-614), and the re-pressed tick
  still uses the PREVIOUS tail's walking jumpMovementFactor (0.02), the sprint factor (0.026)
  only from the tick after, and the live client follows the code exactly; (2) the wiki "max
  walking/sprinting speed" 0.215/0.280 is the per-tick ADVANCE (pre-drag velocity), while the
  velocity the recurrence locks to is 0.11785904894762855/0.15321675646360336 -- both are now
  pinned (advance as a 20-tick average, the locked value bit-exact) so the two readings can
  never be confused later; (3) a scratch C#/PowerShell re-derivation of the constants was
  wrong at the 8th digit because both evaluators constant-fold `0.6f * 0.91f` in double
  (0.54600003743171754 instead of the float-rounded 0.54600006341934204) -- only a real JVM
  run produced values that agree with the recorded traces, so the pins were taken from that
  run and the goldens stay the arbiter.

## 2026-10-22 - Task 1.2: MovementSim collision -- 6 game traces bit-exact
- Done: new pure `dev.kairos.core.sim.CollisionWorld` (dense grid from `blocks.json`: per-block box lists, prefix `cellStart`, one reusable query buffer, no hashing or allocation on the hot path) and `MovementSim` collision -- the game's one-query Y/X/Z sweep of the planned box (`Entity.moveEntity`, Entity.java:553-712), step-up with the game's candidate pick (`d20 > d10`) and revert, the sneak edge clamp (0.05 loops while `onGround && sneaking` and the floor below is empty), head bumps and the landing reset from `collidedVertically`; per-region `"props"` in the scenario model/parser (stairs need facing+half; through the world shape is not storable, see below); loader resolves region states on the command thread, builds with flag 2 like `/setblock`, and now snapshots the pre-run world and restores it on every finish/abort (plus a 20-tick BUILD retry and an explicit S23 resend for states the chunk reports as unchanged). Six scenarios (`wall_hit_01`, `head_bump_01`, `slab_step_01`, `stairs_step_01`, `sneak_edge_01`, `fall_off_01`), recorded live (line counts 41/47/51/61/71/71, all 0 CR), replayed bit-exact by `MovementSimCollisionGoldenTest`; the 13 flat goldens still pass and `.\gradlew.bat :core:test` is green: 44 tests, 0 failures.
- Surprised: (1) all 1.8.9 chunks store 16-bit state IDs and `BlockStairs.getMetaFromState` (BlockStairs.java:537-543) ignores SHAPE, so all five shapes of a facing/half share one ID and `getByValue` returns whichever registered last -- for south/bottom stairs that is outer_right, so a shape=straight placement can never read back as straight; the game itself recomputes stair shape from neighbours for collision (`setBaseCollisionBounds`), the sim's straight boxes are correct, and the harness now compares against the world-storage form (`Block.BLOCK_STATE_IDS` round trip); (2) setting an unchanged state through `World.setBlockState` sends no update packet at all (`Chunk.setBlockState` returns null), so a stale client can only be repaired with explicit `S23PacketBlockChange` sends -- that explained the phantom "client shows outer_right" aborts; (3) two "sim divergences" (fall tick 29/37) were contaminated recordings, not sim bugs: leftover blocks and the user's own terrain inside drop corridors made the game land early, so scenario regions must control the whole path (fall_off_01 now clears the corridor with an air region) and recordings from a dirty world were discarded rather than edited.

## 2026-10-22 - Task 1.3: slipperiness from the dump -- ice/packed ice, 3 goldens bit-exact
- Done: `CollisionWorld` now retains the resolved region states and the per-cell state table and exposes `slipperinessAt(posX, feetY, posZ)` (O(1), no allocation; floors like `MathHelper.floor_double`, looks at the block one below the feet, unassigned and out-of-grid positions are air = Block's 0.6f default); `MovementSim` replaces both hard-typed 0.6f f4 sites with that lookup (EntityLivingBase.java:1254-1264, called twice per tick with identical pre-move inputs); `CollisionWorldSlipperinessTest` (ice/packed ice 0.98f, stone 0.6f, air/unassigned/outside 0.6f, floor semantics, JVM float product 0.98f*0.91f = 0.891800045967102) and `MovementSimSlipperyGoldenTest` (3 live goldens, line counts 71/71/81, replayed through each scenario's own collision world; glide pins at ice ticks 20-23, transition ice ticks 44-49 and post-boundary tick 59) green: `.\gradlew.bat :core:cleanTest :core:test --offline` = 51 tests, 0 failures; the 13 flat and 6 collision goldens are untouched and still exact.
- Surprised: (1) the ice sprint ADVANCE locks at ~0.2757, slightly BELOW stone's 0.2806, because f = 0.16277136f/(f4*f4*f4) accelerates less when f4 is large; the long glide comes from the post-move velocity locking at ~0.2459 (0.8918 of it kept per tick instead of 0.546), and the slide only freezes because the 0.005 momentum threshold zeroes it; (2) ice and packed ice are both 0.98 in the dump, so the two per-type traces are dynamically identical and the stone-ice-stone transition trace is what proves the lookup is positional per tick; (3) Tier C stays out: slime reads 0.8 from the dump but its bounce and entity effects are not modelled, so it remains unsupported until after Gate 2 (no special cases were added).
