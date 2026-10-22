package dev.kairos.core.solver;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.2 solver tests: flat gaps 1-3 must solve, every returned plan must replay from a
 * fresh start copy onto the target, the search must be deterministic and must not mutate
 * the caller's state, and a target outside the baseline reach envelope must return the
 * explicit no-plan result (never an impossibility claim).
 *
 * <p>The worlds are built in memory from the frozen block dump through
 * {@link ScenarioParser} and {@link CollisionWorld}; no scenario or trace data is added or
 * edited. All starts use the recorded flat-rest anchor (feet at y 65, the settled
 * {@code -0.0784000015258789} vertical motion), and yaw is always 0.
 */
class JumpSolverTest {

    /** Enough ticks for a 4.5-block run-up, jump arc and landing observation. */
    private static final int BUDGET = 80;

    /** The flat floor top of the committed scenarios. */
    private static final double FLOOR_TOP = 65.0;

    /** The recorded settled start anchor (MovementSimL0Test / flat goldens). */
    private static final double REST_VY = -0.0784000015258789;

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void solvesFlatGapsAndEveryPlanReplaysOntoTheTarget() {
        for (int gapBlocks = 1; gapBlocks <= 3; gapBlocks++) {
            CollisionWorld world = gapWorld(gapBlocks);
            JumpTarget target = target(gapBlocks);
            MovementSim.State start = startState();

            SolveResult result = JumpSolver.solve(start, target, world, 0.0f, BUDGET);
            assertTrue(result.isSolved(), "gap " + gapBlocks + " must solve: " + result);
            JumpPlan plan = result.plan;
            assertEquals(plan.inputs.size() - 1, plan.landingTick,
                    "landing must be the last input");
            assertTrue(plan.inputs.size() <= BUDGET,
                    "plan must stay within the tick budget");

            MovementSim.State replayed = replay(start, plan, world);
            assertTrue(target.isReachedBy(replayed),
                    "gap " + gapBlocks + " plan must replay onto the target; landed at "
                            + replayed.x + "," + replayed.y + "," + replayed.z);
            assertTrue(replayed.onGround, "landing must be on the ground");
            assertEquals(FLOOR_TOP, replayed.y, 0.0,
                    "landing must be on the target surface");
        }
    }

    @Test
    void repeatedSolvesAreDeterministicAndDoNotMutateStart() {
        CollisionWorld world = gapWorld(3);
        JumpTarget target = target(3);
        MovementSim.State start = startState();
        MovementSim.State snapshot = copy(start);

        SolveResult first = JumpSolver.solve(start, target, world, 0.0f, BUDGET);
        SolveResult second = JumpSolver.solve(start, target, world, 0.0f, BUDGET);

        assertTrue(first.isSolved(), "first solve must succeed: " + first);
        assertTrue(second.isSolved(), "second solve must succeed: " + second);
        assertEquals(first.plan, second.plan, "repeated solves must return the same plan");
        assertStateEquals(snapshot, start, "the caller's start state must not change");
    }

    @Test
    void gapOutsideBaselineEnvelopeReturnsNoPlanWithoutImpossibilityClaim() {
        CollisionWorld world = gapWorld(6);
        JumpTarget target = target(6);
        MovementSim.State start = startState();

        SolveResult result = JumpSolver.solve(start, target, world, 0.0f, BUDGET);

        assertTrue(result.isNoPlan(), "a 6-block gap must not produce a plan: " + result);
        assertEquals(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED, result.reason,
                "a failed budgeted search must report the search limit, not infeasibility");
        assertNull(result.plan, "a no-plan result must not carry a plan");
        assertFalse(result.isSolved(), "a no-plan result must not be solved");
    }

    @Test
    void planHoldsOneFixedYawAndOneJump() {
        CollisionWorld world = gapWorld(2);
        JumpTarget target = target(2);
        SolveResult result = JumpSolver.solve(startState(), target, world, 0.0f, BUDGET);
        assertTrue(result.isSolved(), "gap 2 must solve: " + result);

        int jumps = 0;
        for (TraceInput input : result.plan.inputs) {
            assertEquals(0.0f, input.yaw, 0.0f, "baseline plans must keep one fixed yaw");
            assertEquals(0, input.sneak, "baseline plans must not toggle sneak");
            assertEquals(0, input.a, "baseline plans must not strafe left");
            assertEquals(0, input.s, "baseline plans must not move backward");
            assertEquals(0, input.d, "baseline plans must not strafe right");
            if (input.jump == 1) {
                jumps++;
            }
        }
        assertEquals(1, jumps, "the baseline plan must contain exactly one jump tick");
    }

    private static MovementSim.State replay(MovementSim.State start, JumpPlan plan,
            CollisionWorld world) {
        MovementSim.State state = copy(start);
        for (TraceInput input : plan.inputs) {
            MovementSim.step(state, input, world);
        }
        return state;
    }

    private static MovementSim.State startState() {
        return MovementSim.initialState(
                0.5, FLOOR_TOP, 0.5,
                0.0, REST_VY, 0.0,
                true, false, false, true);
    }

    /**
     * A straight +Z gap. The near floor ends at z 4, the gap is
     * {@code z 5 .. 4 + gapBlocks}, and the far floor starts at
     * {@code z 5 + gapBlocks}. The start has 4.5 blocks of run-up before the gap.
     */
    private static CollisionWorld gapWorld(int gapBlocks) {
        String json = "{\"name\": \"solver_gap" + gapBlocks + "\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "{\"from\": [-2, 64, -20], \"to\": [2, 64, 4],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}},"
                + "{\"from\": [-2, 64, " + (5 + gapBlocks) + "],"
                + " \"to\": [2, 64, " + (25 + gapBlocks) + "],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}"
                + "], \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(BLOCKS, ScenarioParser.parse(json));
    }

    /** The far-floor top rectangle at the same height as the start floor. */
    private static JumpTarget target(int gapBlocks) {
        return JumpTarget.of(
                -2.0, 3.0,
                5.0 + gapBlocks, 26.0 + gapBlocks,
                FLOOR_TOP);
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
