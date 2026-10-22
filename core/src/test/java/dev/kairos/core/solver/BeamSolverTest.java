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
 * Task 2.3 beam-search tests. Every accepted plan is replayed from a fresh start copy, and
 * every plan must contain exactly one jump. The 4-block case is the PLAN.md 2.3 acceptance
 * target; the 1-3 and step cases ensure the broader single-jump behavior still works.
 */
class BeamSolverTest {

    private static final int BUDGET = 80;

    private static final double FLOOR_TOP = 65.0;

    private static final double REST_VY = -0.0784000015258789;

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void solvesFourBlockMomentumGapAndEveryPlanReplays() {
        CollisionWorld world = gapWorld(4);
        JumpTarget target = target(4);
        MovementSim.State start = startState();

        SolveResult result = BeamSolver.solve(start, target, world,
                BeamSearchConfig.of(0.0f, 15.0f, 1024, BUDGET));

        assertTrue(result.isSolved(), "4-block momentum gap must solve: " + result);
        assertPlanReplays(start, target, world, result.plan);
        assertTrue(result.plan.inputs.size() <= BUDGET,
                "plan must stay within the tick budget");
    }

    @Test
    void solvesFlatGapsOneToThreeAndAStep() {
        for (int gapBlocks = 1; gapBlocks <= 3; gapBlocks++) {
            CollisionWorld world = gapWorld(gapBlocks);
            JumpTarget target = target(gapBlocks);
            MovementSim.State start = startState();

            SolveResult result = BeamSolver.solve(start, target, world,
                    BeamSearchConfig.of(0.0f, 0.0f, 256, BUDGET));

            assertTrue(result.isSolved(), "gap " + gapBlocks + " must solve: " + result);
            assertPlanReplays(start, target, world, result.plan);
        }

        CollisionWorld stepWorld = stepWorld();
        JumpTarget stepTarget = stepTarget();
        MovementSim.State start = startState();
        SolveResult stepResult = BeamSolver.solve(start, stepTarget, stepWorld,
                BeamSearchConfig.of(0.0f, 0.0f, 256, BUDGET));

        assertTrue(stepResult.isSolved(), "a one-block step must solve: " + stepResult);
        assertPlanReplays(start, stepTarget, stepWorld, stepResult.plan);
    }

    @Test
    void usesPerTickYawAndSneakOrSprintToggles() {
        CollisionWorld world = gapWorld(2);
        JumpTarget target = target(2);
        MovementSim.State start = startState();

        SolveResult result = BeamSolver.solve(start, target, world,
                BeamSearchConfig.of(30.0f, 15.0f, 512, BUDGET));

        assertTrue(result.isSolved(), "the turned gap must solve: " + result);
        assertPlanReplays(start, target, world, result.plan);
        assertTrue(changesYaw(result.plan, 30.0f),
                "the plan must contain a nonzero per-tick yaw delta");
        assertTrue(togglesSneakOrSprint(result.plan),
                "the plan must use a sneak/sprint toggle action");
    }

    @Test
    void repeatedSolvesAreDeterministicAndDoNotMutateStart() {
        CollisionWorld world = gapWorld(2);
        JumpTarget target = target(2);
        MovementSim.State start = startState();
        MovementSim.State snapshot = copy(start);
        BeamSearchConfig config = BeamSearchConfig.of(0.0f, 15.0f, 512, BUDGET);

        SolveResult first = BeamSolver.solve(start, target, world, config);
        SolveResult second = BeamSolver.solve(start, target, world, config);

        assertTrue(first.isSolved(), "first solve must succeed: " + first);
        assertTrue(second.isSolved(), "second solve must succeed: " + second);
        assertEquals(first.plan, second.plan, "repeated solves must return the same plan");
        assertStateEquals(snapshot, start, "the caller's start state must not change");
    }

    @Test
    void sixBlockGapReturnsBudgetExhaustionNotImpossibility() {
        CollisionWorld world = gapWorld(6);
        JumpTarget target = target(6);
        MovementSim.State start = startState();

        SolveResult result = BeamSolver.solve(start, target, world,
                BeamSearchConfig.of(0.0f, 15.0f, 256, BUDGET));

        assertTrue(result.isNoPlan(), "6-block gap must not produce a plan: " + result);
        assertEquals(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED, result.reason,
                "a bounded search must report its budget, not impossibility");
        assertNull(result.plan, "a no-plan result must not carry a plan");
        assertFalse(result.isSolved(), "a no-plan result must not be solved");
    }

    @Test
    void exactStateDeduplicationDoesNotChangeTheResult() {
        CollisionWorld world = gapWorld(2);
        JumpTarget target = target(2);
        MovementSim.State start = startState();

        SolveResult withDedup = BeamSolver.solve(start, target, world,
                BeamSearchConfig.of(0.0f, 0.0f, 512, BUDGET, true));
        SolveResult withoutDedup = BeamSolver.solve(start, target, world,
                BeamSearchConfig.of(0.0f, 0.0f, 512, BUDGET, false));

        assertTrue(withDedup.isSolved(), "deduplicated solve must succeed: " + withDedup);
        assertTrue(withoutDedup.isSolved(), "plain solve must succeed: " + withoutDedup);
        assertEquals(withDedup.plan, withoutDedup.plan,
                "exact-state pruning must not change the chosen plan");
    }

    private static void assertPlanReplays(MovementSim.State start, JumpTarget target,
            CollisionWorld world, JumpPlan plan) {
        assertEquals(plan.inputs.size() - 1, plan.landingTick,
                "landing must be the last input");
        int jumps = 0;
        for (TraceInput input : plan.inputs) {
            if (input.jump == 1) {
                jumps++;
            }
        }
        assertEquals(1, jumps, "beam plans must contain exactly one jump");
        MovementSim.State replayed = replay(start, plan, world);
        assertTrue(target.isReachedBy(replayed),
                "plan must replay onto the target; landed at "
                        + replayed.x + "," + replayed.y + "," + replayed.z);
        assertTrue(replayed.onGround, "landing must be on the ground");
        assertEquals(target.surfaceY, replayed.y, 0.0,
                "landing must be on the target surface");
    }

    private static boolean changesYaw(JumpPlan plan, float startYaw) {
        float previous = startYaw;
        for (TraceInput input : plan.inputs) {
            if (Float.compare(input.yaw, previous) != 0) {
                return true;
            }
            previous = input.yaw;
        }
        return false;
    }

    private static boolean togglesSneakOrSprint(JumpPlan plan) {
        int previousSneak = 0;
        int previousSprint = 0;
        for (TraceInput input : plan.inputs) {
            if (input.sneak != previousSneak || input.sprint != previousSprint) {
                return true;
            }
            previousSneak = input.sneak;
            previousSprint = input.sprint;
        }
        return false;
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

    private static CollisionWorld gapWorld(int gapBlocks) {
        String json = "{\"name\": \"beam_gap" + gapBlocks + "\","
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

    private static JumpTarget target(int gapBlocks) {
        return JumpTarget.of(
                -2.0, 3.0,
                5.0 + gapBlocks, 26.0 + gapBlocks,
                FLOOR_TOP);
    }

    private static CollisionWorld stepWorld() {
        String json = "{\"name\": \"beam_step\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "{\"from\": [-2, 64, -20], \"to\": [2, 64, 4],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}},"
                + "{\"from\": [-2, 65, 5], \"to\": [2, 65, 25],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}"
                + "], \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(BLOCKS, ScenarioParser.parse(json));
    }

    private static JumpTarget stepTarget() {
        return JumpTarget.of(-2.0, 3.0, 5.0, 26.0, 66.0);
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
