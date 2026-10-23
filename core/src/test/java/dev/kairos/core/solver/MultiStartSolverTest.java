package dev.kairos.core.solver;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.catalog.CatalogEntry;
import dev.kairos.core.catalog.CatalogReader;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.5 multi-start tests: starts are tried in list order, a longer non-centre run-up
 * can be the chosen start, the accepted plan replays from that exact start, and an
 * all-start failure keeps the single-start solver's budget reason.
 */
class MultiStartSolverTest {

    private static final int BUDGET = 80;

    private static final double FLOOR_TOP = 65.0;

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void triesStartsInOrderAndCanChooseALongerNonCentreRunup() {
        CatalogEntry entry = entry("gap4_flat_momentum");
        CollisionWorld world = gapWorld(entry);
        JumpTarget target = target(entry);
        List<StartState> variants = RunupStartBuilder.build(entry, world);
        StartState standingCentre = find(variants, 0, 0.0, 0.0);
        StartState longNonCentre = find(variants, 3, -0.2, 0.2);
        List<StartState> starts = Arrays.asList(standingCentre, longNonCentre);

        MultiStartResult result = MultiStartSolver.solveJump(
                starts, target, world, 0.0f, BUDGET);

        assertTrue(result.isSolved(), "the long non-centre start must solve: " + result);
        assertSame(longNonCentre, result.start,
                "the wrapper must return the start that solved, in list order");
        assertEquals(3, result.start.runupDistance, "the chosen start must use run-up 3");
        assertTrue(result.start.offsetX != 0.0 || result.start.offsetZ != 0.0,
                "the chosen start must be non-centre");
        assertPlanReplays(longNonCentre.state, target, world, result.plan());
    }

    @Test
    void beamSolverUsesTheSameStartOrderAndReplayCheck() {
        CatalogEntry entry = entry("gap4_flat_momentum");
        CollisionWorld world = gapWorld(entry);
        JumpTarget target = target(entry);
        List<StartState> variants = RunupStartBuilder.build(entry, world);
        StartState longNonCentre = find(variants, 3, 0.2, -0.2);

        MultiStartResult result = MultiStartSolver.solveBeam(
                Arrays.asList(longNonCentre), target, world,
                BeamSearchConfig.of(0.0f, 15.0f, 1024, BUDGET));

        assertTrue(result.isSolved(), "the beam must solve from the chosen start: " + result);
        assertSame(longNonCentre, result.start, "the beam must return the chosen start");
        assertPlanReplays(longNonCentre.state, target, world, result.plan());
    }

    @Test
    void allStartsFailingPreservesTheBudgetReason() {
        CatalogEntry entry = entry("gap6_flat_infeasible");
        CollisionWorld world = gapWorld(entry);
        JumpTarget target = target(entry);
        List<StartState> variants = RunupStartBuilder.build(entry, world);

        MultiStartResult result = MultiStartSolver.solveJump(
                variants, target, world, 0.0f, BUDGET);

        assertTrue(result.isNoPlan(), "a 6-block gap must not produce a plan: " + result);
        assertNull(result.start, "a no-plan result must not name a start");
        assertNull(result.plan(), "a no-plan result must not carry a plan");
        assertEquals(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED, result.reason(),
                "the wrapper must preserve the solver's budget reason");
        assertFalse(result.isSolved(), "a no-plan result must not be solved");
    }

    @Test
    void repeatedSolvesAreDeterministicAndDoNotMutateTheStarts() {
        CatalogEntry entry = entry("gap4_flat_momentum");
        CollisionWorld world = gapWorld(entry);
        JumpTarget target = target(entry);
        List<StartState> variants = RunupStartBuilder.build(entry, world);
        StartState standingCentre = find(variants, 0, 0.0, 0.0);
        StartState longNonCentre = find(variants, 3, -0.2, 0.2);
        List<StartState> starts = Arrays.asList(standingCentre, longNonCentre);
        MovementSim.State centreSnapshot = copy(standingCentre.state);
        MovementSim.State longSnapshot = copy(longNonCentre.state);

        MultiStartResult first = MultiStartSolver.solveJump(
                starts, target, world, 0.0f, BUDGET);
        MultiStartResult second = MultiStartSolver.solveJump(
                starts, target, world, 0.0f, BUDGET);

        assertTrue(first.isSolved(), "first solve must succeed: " + first);
        assertTrue(second.isSolved(), "second solve must succeed: " + second);
        assertSame(first.start, second.start, "repeated solves must choose the same start");
        assertEquals(first.plan(), second.plan(), "repeated solves must return the same plan");
        assertStateEquals(centreSnapshot, standingCentre.state,
                "the standing start must not change");
        assertStateEquals(longSnapshot, longNonCentre.state,
                "the long start must not change");
    }

    private static void assertPlanReplays(MovementSim.State start, JumpTarget target,
            CollisionWorld world, JumpPlan plan) {
        assertTrue(plan != null, "a solved result must carry a plan");
        assertEquals(plan.inputs.size() - 1, plan.landingTick,
                "landing must be the last input");
        int jumps = 0;
        for (TraceInput input : plan.inputs) {
            if (input.jump == 1) {
                jumps++;
            }
        }
        assertEquals(1, jumps, "the plan must contain exactly one jump");
        MovementSim.State replayed = copy(start);
        for (TraceInput input : plan.inputs) {
            MovementSim.step(replayed, input, world);
        }
        assertTrue(target.isReachedBy(replayed),
                "the plan must replay onto the target; landed at "
                        + replayed.x + "," + replayed.y + "," + replayed.z);
        assertEquals(FLOOR_TOP, replayed.y, 0.0,
                "the landing must be on the target surface");
    }

    private static StartState find(List<StartState> states, int runupDistance,
            double offsetX, double offsetZ) {
        for (StartState state : states) {
            if (state.runupDistance == runupDistance
                    && Double.compare(state.offsetX, offsetX) == 0
                    && Double.compare(state.offsetZ, offsetZ) == 0) {
                return state;
            }
        }
        throw new AssertionError("no start with runup=" + runupDistance
                + " offset=" + offsetX + "," + offsetZ);
    }

    private static CatalogEntry entry(String id) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                try {
                    return CatalogReader.read(new File(f, "data/catalog/" + id + ".json"));
                } catch (IOException e) {
                    throw new IllegalStateException("cannot read catalog entry " + id, e);
                }
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static CollisionWorld gapWorld(CatalogEntry entry) {
        int maxRunup = entry.runup.get(entry.runup.size() - 1);
        String json = "{\"name\": \"multi_" + entry.id + "\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "{\"from\": [-2, 64, " + (entry.startZ - maxRunup - 2) + "],"
                + " \"to\": [2, 64, " + entry.startZ + "],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}},"
                + "{\"from\": [-2, 64, " + entry.goalZ + "],"
                + " \"to\": [2, 64, " + (entry.goalZ + 10) + "],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}"
                + "], \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(BLOCKS, ScenarioParser.parse(json));
    }

    private static JumpTarget target(CatalogEntry entry) {
        return JumpTarget.of(
                entry.goalX, entry.goalX + 1.0,
                entry.goalZ, entry.goalZ + 1.0,
                entry.startY + 1.0);
    }

    private static BlockData loadBlocks() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                try {
                    return BlockData.load(new File(f, "data/1.8.9/blocks.json"));
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "cannot load data/1.8.9/blocks.json", e);
                }
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
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

    private static void assertStateEquals(MovementSim.State expected,
            MovementSim.State actual, String message) {
        assertEquals(expected.x, actual.x, 0.0, message + " (x)");
        assertEquals(expected.y, actual.y, 0.0, message + " (y)");
        assertEquals(expected.z, actual.z, 0.0, message + " (z)");
        assertEquals(expected.vx, actual.vx, 0.0, message + " (vx)");
        assertEquals(expected.vy, actual.vy, 0.0, message + " (vy)");
        assertEquals(expected.vz, actual.vz, 0.0, message + " (vz)");
        assertEquals(expected.onGround, actual.onGround, message + " (onGround)");
        assertEquals(expected.sprinting, actual.sprinting, message + " (sprinting)");
        assertEquals(expected.colH, actual.colH, message + " (colH)");
        assertEquals(expected.colV, actual.colV, message + " (colV)");
        assertEquals(expected.minX, actual.minX, 0.0, message + " (minX)");
        assertEquals(expected.maxX, actual.maxX, 0.0, message + " (maxX)");
        assertEquals(expected.minZ, actual.minZ, 0.0, message + " (minZ)");
        assertEquals(expected.maxZ, actual.maxZ, 0.0, message + " (maxZ)");
        assertEquals(expected.maxY, actual.maxY, 0.0, message + " (maxY)");
        assertEquals(expected.jumpTicks, actual.jumpTicks, message + " (jumpTicks)");
        assertEquals(expected.sprintingTicksLeft, actual.sprintingTicksLeft,
                message + " (sprintingTicksLeft)");
        assertEquals(expected.sprintToggleTimer, actual.sprintToggleTimer,
                message + " (sprintToggleTimer)");
        assertEquals(Float.floatToIntBits(expected.moveForward),
                Float.floatToIntBits(actual.moveForward), message + " (moveForward)");
        assertEquals(expected.prevSneak, actual.prevSneak, message + " (prevSneak)");
        assertEquals(Float.floatToIntBits(expected.jumpMovementFactor),
                Float.floatToIntBits(actual.jumpMovementFactor),
                message + " (jumpMovementFactor)");
    }
}
