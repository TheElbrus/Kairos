package dev.kairos.core.catalog;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.solver.BeamSearchConfig;
import dev.kairos.core.solver.JumpTarget;
import dev.kairos.core.solver.MultiStartResult;
import dev.kairos.core.solver.MultiStartSolver;
import dev.kairos.core.solver.RunupStartBuilder;
import dev.kairos.core.solver.StartRegion;
import dev.kairos.core.solver.StartState;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 2 canonical catalog coverage. Every feasible single jump must solve in a minimal
 * one-block-wide world and land with real edge clearance; every ladder entry must climb
 * its own wall world; every infeasible entry must produce no plan.
 *
 * <p>The world is the catalog row, nothing more: a one-block-wide start/run-up path at
 * {@code x=startX}, one goal block at {@code (goalX,goalY,goalZ)}, a one-wide support
 * column for height-up goals, a two-block ceiling over headhitter run-up/gap, and a
 * separate climb world for ladders (start block, forward wall column, ladder column
 * facing north). No five-wide platforms: they hide false solutions such as
 * {@code offset_side8_infeasible}.
 *
 * <p>Every jump target uses {@code edgeMargin = 0.1}. This is a gate robustness choice,
 * not a physics constant: roughly one sixth of the 0.6 player width. It rejects
 * numerically grazing landings (an overlap of {@code 1e-8} is not evidence) while still
 * accepting legitimate tight jumps. The solver order is the baseline {@link
 * MultiStartSolver#solveJump} first, then the bounded beam with the Phase 2 gate
 * parameters (width 512, yaw delta 15, tick budget 80). The ladder test is the separate
 * zero-jump case.
 */
class CatalogCoverageTest {

    private static final int BEAM_WIDTH = 512;
    private static final float YAW_DELTA = 15.0f;
    private static final int TICK_BUDGET = 80;
    private static final int LADDER_TICK_BUDGET = 80;
    private static final float APPROACH_YAW = 0.0f;
    private static final double EDGE_MARGIN = 0.1;

    private static final BeamSearchConfig BEAM =
            BeamSearchConfig.of(APPROACH_YAW, YAW_DELTA, BEAM_WIDTH, TICK_BUDGET);

    private static final String STONE_PROPS = ",\"props\":{\"variant\":\"stone\"}";
    private static final String SLAB_PROPS =
            ",\"props\":{\"half\":\"bottom\",\"variant\":\"stone\"}";
    private static final String LADDER_PROPS = ",\"props\":{\"facing\":\"north\"}";

    private static final TraceInput LADDER_INPUT = new TraceInput(
            1, 0, 0, 0, 0, 0, 0, APPROACH_YAW, 0.0f, 1.0f, 0.0f);

    /** The 14 physically feasible single jumps (the two ladder rows are climbs). */
    private static final List<String> SINGLE_JUMP_FEASIBLE = Arrays.asList(
            "gap1_flat_sprint", "gap2_flat_sprint", "gap3_flat_sprint",
            "gap4_flat_momentum", "headhitter_gap1_ceiling2",
            "height_down1_jump", "height_down3_jump", "height_up1_jump",
            "ice_gap1_sprint", "ice_gap4_sprint",
            "offset_side1_gap1", "offset_side2_gap2",
            "slab_down_half", "slab_up_half");

    private static final List<String> LADDER_FEASIBLE = Arrays.asList(
            "ladder_climb2", "ladder_climb4");

    private static final List<String> INFEASIBLE = Arrays.asList(
            "gap6_flat_infeasible", "height_up2_wall",
            "ice_gap8_infeasible", "offset_side8_infeasible");

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void allSingleJumpFeasibleEntriesSolveAndLand() throws IOException {
        List<CatalogEntry> catalog = CatalogReader.readDirectory(catalogDir());
        assertEquals(SINGLE_JUMP_FEASIBLE, feasibleIds(catalog, false),
                "the feasible single-jump scope changed; update the Gate 2 evidence");
        assertEquals(LADDER_FEASIBLE, feasibleIds(catalog, true),
                "the feasible ladder scope changed; update the Gate 2 evidence");

        List<String> jumpSolved = new ArrayList<String>();
        List<String> beamSolved = new ArrayList<String>();
        for (String id : SINGLE_JUMP_FEASIBLE) {
            CatalogEntry entry = entry(catalog, id);
            CollisionWorld world = jumpWorld(entry);
            JumpTarget target = jumpTarget(entry);
            List<StartState> starts = RunupStartBuilder.build(entry, world, 0, 1);
            assertFalse(starts.isEmpty(), id + ": no start variants in the canonical world");

            MultiStartResult result = MultiStartSolver.solveJump(
                    starts, target, world, APPROACH_YAW, TICK_BUDGET);
            if (result.isSolved()) {
                jumpSolved.add(id);
            } else {
                result = MultiStartSolver.solveBeam(starts, target, world, BEAM);
                assertTrue(result.isSolved(), id + ": no plan within the gate config ("
                        + starts.size() + " starts)");
                beamSolved.add(id);
            }
            assertPlanLands(id, result, target, world);
        }

        assertEquals(SINGLE_JUMP_FEASIBLE.size(), jumpSolved.size() + beamSolved.size(),
                "every feasible single jump must solve exactly once");
        assertEquals(12, jumpSolved.size(),
                "audit expects 12 JumpSolver plans, got " + jumpSolved);
        assertEquals(Arrays.asList("offset_side1_gap1", "offset_side2_gap2"), beamSolved,
                "audit expects only the two offset jumps to need the beam fallback");
    }

    @Test
    void ladderEntriesClimbTheirWalls() throws IOException {
        List<CatalogEntry> catalog = CatalogReader.readDirectory(catalogDir());
        int ladders = 0;
        for (CatalogEntry entry : catalog) {
            if (!entry.tags.contains("ladder")) {
                continue;
            }
            ladders++;
            assertEquals(CatalogEntry.Expect.FEASIBLE, entry.expect, entry.id + " must be feasible");
            assertTrue(entry.runup.isEmpty(), entry.id + " must not declare a run-up");
            assertEquals(1, entry.goalZ - entry.startZ, entry.id + " must climb one block forward");

            CollisionWorld world = ladderWorld(entry);
            List<StartState> starts = StartRegion.ofBlockTop(
                    entry.startX, entry.startY, entry.startZ).startStates(world);
            assertFalse(starts.isEmpty(), entry.id + ": ladder start block is not standable");
            StartState center = centerStart(starts);
            assertTrue(center != null, entry.id + ": no centre start variant");

            JumpTarget target = ladderTarget(entry);
            MovementSim.State state = copy(center.state);
            int topTick = -1;
            int landedTick = -1;
            for (int tick = 0; tick < LADDER_TICK_BUDGET; tick++) {
                MovementSim.step(state, LADDER_INPUT, world);
                if (topTick < 0 && state.onGround
                        && Double.compare(state.y, target.surfaceY) == 0) {
                    topTick = tick;
                }
                if (target.isReachedBy(state)) {
                    landedTick = tick;
                    break;
                }
            }
            assertTrue(topTick >= 0, entry.id + ": W-only climb did not stand on the wall top "
                    + target.surfaceY + " within " + LADDER_TICK_BUDGET + " ticks; final=("
                    + state.x + "," + state.y + "," + state.z + ")");
            assertTrue(landedTick >= 0, entry.id + ": climb must finish on the goal block with"
                    + " edge margin " + EDGE_MARGIN + "; final=("
                    + state.x + "," + state.y + "," + state.z + ")");
            // The audit's 21/38 are the zero-based ticks when the player first stands on the
            // wall top. The edgeMargin=0.1 target is reached one tick later (22/39); it only
            // has to happen within LADDER_TICK_BUDGET.
            assertEquals(entry.id.equals("ladder_climb2") ? 21 : 38, topTick,
                    entry.id + " deterministic climb tick (zero-based trace tick)");
        }
        assertEquals(2, ladders, "expected exactly two ladder entries");
    }

    @Test
    void infeasibleEntriesHaveNoPlanInTheCanonicalWorld() throws IOException {
        List<CatalogEntry> catalog = CatalogReader.readDirectory(catalogDir());
        List<String> checked = new ArrayList<String>();
        for (CatalogEntry entry : catalog) {
            if (entry.expect != CatalogEntry.Expect.INFEASIBLE) {
                continue;
            }
            checked.add(entry.id);
            CollisionWorld world = jumpWorld(entry);
            JumpTarget target = jumpTarget(entry);
            List<StartState> starts = RunupStartBuilder.build(entry, world, 0, 1);
            assertFalse(starts.isEmpty(), entry.id + ": no start variants");

            MultiStartResult jump = MultiStartSolver.solveJump(
                    starts, target, world, APPROACH_YAW, TICK_BUDGET);
            assertFalse(jump.isSolved(),
                    entry.id + ": JumpSolver found a false plan in the canonical world");

            MultiStartResult beam = MultiStartSolver.solveBeam(starts, target, world, BEAM);
            assertFalse(beam.isSolved(),
                    entry.id + ": BeamSolver found a false plan in the canonical world");
        }
        assertEquals(INFEASIBLE, checked, "every infeasible entry needs a no-plan check");
    }

    private static void assertPlanLands(String id, MultiStartResult result,
            JumpTarget target, CollisionWorld world) {
        assertTrue(result.start != null, id + ": a solved result must name its start");
        MovementSim.State state = copy(result.start.state);
        int jumps = 0;
        for (TraceInput in : result.plan().inputs) {
            if (in.jump == 1) {
                jumps++;
            }
            MovementSim.step(state, in, world);
        }
        assertEquals(1, jumps, id + ": a single-jump plan must press jump exactly once");
        assertTrue(target.isReachedBy(state), id + ": independent replay did not land;"
                + " final=(" + state.x + "," + state.y + "," + state.z + ")");
    }

    private static List<String> feasibleIds(List<CatalogEntry> catalog, boolean ladder) {
        List<String> ids = new ArrayList<String>();
        for (CatalogEntry entry : catalog) {
            if (entry.expect == CatalogEntry.Expect.FEASIBLE
                    && entry.tags.contains("ladder") == ladder) {
                ids.add(entry.id);
            }
        }
        return ids;
    }

    private static CatalogEntry entry(List<CatalogEntry> catalog, String id) {
        for (CatalogEntry entry : catalog) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        throw new AssertionError("catalog has no entry " + id);
    }

    private static StartState centerStart(List<StartState> starts) {
        for (StartState start : starts) {
            if (Double.compare(start.offsetX, 0.0) == 0
                    && Double.compare(start.offsetZ, 0.0) == 0) {
                return start;
            }
        }
        return null;
    }

    private static File catalogDir() throws IOException {
        return new File(repoRoot(), CatalogRunner.RELATIVE_DIR);
    }

    /**
     * The canonical jump world: one-wide start/run-up path, one goal block, a one-wide
     * support column under a raised goal, and the headhitter ceiling over the run-up and
     * gap.
     */
    private static CollisionWorld jumpWorld(CatalogEntry entry) {
        int startX = entry.startX;
        int startY = entry.startY;
        int startZ = entry.startZ;
        int maxRunup = entry.runup.get(entry.runup.size() - 1);
        boolean ice = entry.tags.contains("ice");
        boolean slab = entry.tags.contains("slab");

        StringBuilder blocks = new StringBuilder();
        block(blocks, startX, startY, startZ - maxRunup,
                startX, startY, startZ, ice ? "ice" : "stone",
                ice ? "" : STONE_PROPS);

        // A raised goal is the top of a one-wide support column, not a floating block.
        // Fill only the cells strictly below the goal block; the goal itself is placed
        // separately after this column, so no region overlaps it.
        int supportTop = entry.goalY - 1;
        if (supportTop >= startY) {
            block(blocks, entry.goalX, startY, entry.goalZ,
                    entry.goalX, supportTop, entry.goalZ, "stone", STONE_PROPS);
        }

        block(blocks, entry.goalX, entry.goalY, entry.goalZ,
                entry.goalX, entry.goalY, entry.goalZ,
                slab ? "stone_slab" : (ice ? "ice" : "stone"),
                slab ? SLAB_PROPS : (ice ? "" : STONE_PROPS));

        if (entry.tags.contains("headhitter")) {
            // Two-block ceiling (bottom at startY+3) over the run-up and the gap. Five
            // blocks wide so the plan cannot strafe around the defining obstacle.
            block(blocks, startX - 2, startY + 3, startZ - maxRunup,
                    startX + 2, startY + 4, entry.goalZ - 1, "stone", STONE_PROPS);
        }

        return world(entry, blocks);
    }

    /**
     * The canonical ladder world: start block, the wall one block forward at
     * {@code goalZ}, and the ladder column above the start block, back against the wall.
     */
    private static CollisionWorld ladderWorld(CatalogEntry entry) {
        StringBuilder blocks = new StringBuilder();
        block(blocks, entry.startX, entry.startY, entry.startZ,
                entry.startX, entry.startY, entry.startZ, "stone", STONE_PROPS);
        block(blocks, entry.startX, entry.startY + 1, entry.goalZ,
                entry.startX, entry.goalY, entry.goalZ, "stone", STONE_PROPS);
        block(blocks, entry.startX, entry.startY + 1, entry.startZ,
                entry.startX, entry.goalY, entry.startZ, "ladder", LADDER_PROPS);
        return world(entry, blocks);
    }

    private static CollisionWorld world(CatalogEntry entry, StringBuilder blocks) {
        String json = "{\"name\":\"coverage_" + entry.id + "\","
                + "\"start\":{\"pos\":[" + (entry.startX + 0.5) + ","
                + (entry.startY + 1) + "," + (entry.startZ + 0.5) + "]},"
                + "\"blocks\":[" + blocks + "],\"inputs\":[{\"ticks\":1}]}";
        return CollisionWorld.of(BLOCKS, ScenarioParser.parse(json));
    }

    private static void block(StringBuilder out, int x0, int y0, int z0,
            int x1, int y1, int z1, String id, String props) {
        if (out.length() > 0) {
            out.append(',');
        }
        out.append("{\"from\":[").append(x0).append(',').append(y0).append(',')
                .append(z0).append("],\"to\":[").append(x1).append(',').append(y1)
                .append(',').append(z1).append("],\"id\":\"").append(id).append('"')
                .append(props).append('}');
    }

    private static JumpTarget jumpTarget(CatalogEntry entry) {
        double surface = entry.goalY + (entry.tags.contains("slab") ? 0.5 : 1.0);
        return JumpTarget.of(entry.goalX, entry.goalX + 1.0,
                entry.goalZ, entry.goalZ + 1.0, surface, EDGE_MARGIN);
    }

    private static JumpTarget ladderTarget(CatalogEntry entry) {
        return JumpTarget.of(entry.goalX, entry.goalX + 1.0,
                entry.goalZ, entry.goalZ + 1.0, entry.goalY + 1.0, EDGE_MARGIN);
    }

    private static MovementSim.State copy(MovementSim.State source) {
        MovementSim.State copy = new MovementSim.State();
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        copy.vx = source.vx;
        copy.vy = source.vy;
        copy.vz = source.vz;
        copy.onGround = source.onGround;
        copy.sprinting = source.sprinting;
        copy.colH = source.colH;
        copy.colV = source.colV;
        copy.minX = source.minX;
        copy.maxX = source.maxX;
        copy.minZ = source.minZ;
        copy.maxZ = source.maxZ;
        copy.maxY = source.maxY;
        copy.jumpTicks = source.jumpTicks;
        copy.sprintingTicksLeft = source.sprintingTicksLeft;
        copy.sprintToggleTimer = source.sprintToggleTimer;
        copy.moveForward = source.moveForward;
        copy.prevSneak = source.prevSneak;
        copy.jumpMovementFactor = source.jumpMovementFactor;
        return copy;
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return f;
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static BlockData loadBlocks() {
        try {
            return BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot load data/1.8.9/blocks.json", e);
        }
    }
}
