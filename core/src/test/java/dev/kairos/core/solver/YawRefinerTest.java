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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YawRefinerTest {

    private static final int BEAM_BUDGET = 80;
    private static final int COARSE_BEAM_WIDTH = 512;
    private static final int MAX_CANDIDATES = 2_000_000;
    private static final double FLOOR_TOP = 65.0;
    private static final double REST_VY = -0.0784000015258789;
    private static final double TIGHT_CENTER_X = 0.984915;
    private static final double TIGHT_CENTER_Z = 5.811064;
    private static final double TIGHT_SIZE = 0.6;
    private static final double TIGHT_MARGIN = 0.59;

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void refinesTightWallTargetWhereCoarseBeamFails() {
        CollisionWorld world = gapWorld();
        MovementSim.State start = startState();
        JumpPlan skeleton = looseSkeleton(start, world);
        JumpTarget tight = tightTarget();

        assertFalse(tight.isReachedBy(replay(start, skeleton, world)),
                "the loose skeleton must not already reach the tight target");

        SolveResult coarse = BeamSolver.solve(start, tight, world, coarseConfig());
        assertTrue(coarse.isNoPlan(),
                "the coarse beam must miss the tight target: " + coarse);
        assertEquals(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED, coarse.reason,
                "the coarse search must report budget exhaustion");

        SolveResult refined = YawRefiner.refine(start, skeleton, tight, world,
                refinerConfig());
        assertTrue(refined.isSolved(), "the refiner must recover the tight target: "
                + refined);
        assertPlanPreserves(start, skeleton, tight, world, refined.plan);
    }

    @Test
    void preservesEveryNonYawInputAndPlanShape() {
        CollisionWorld world = gapWorld();
        MovementSim.State start = startState();
        JumpPlan skeleton = looseSkeleton(start, world);
        SolveResult refined = YawRefiner.refine(start, skeleton, tightTarget(), world,
                refinerConfig());

        assertTrue(refined.isSolved(), "the case must solve: " + refined);
        assertPlanPreserves(start, skeleton, tightTarget(), world, refined.plan);
    }

    @Test
    void isDeterministicAndDoesNotMutateStartOrSkeleton() {
        CollisionWorld world = gapWorld();
        MovementSim.State start = startState();
        MovementSim.State startSnapshot = copy(start);
        JumpPlan skeleton = looseSkeleton(start, world);
        float[] yawsBefore = yaws(skeleton);
        YawRefinerConfig config = refinerConfig();

        SolveResult first = YawRefiner.refine(start, skeleton, tightTarget(), world,
                config);
        SolveResult second = YawRefiner.refine(start, skeleton, tightTarget(), world,
                config);

        assertTrue(first.isSolved(), "first refine must solve: " + first);
        assertTrue(second.isSolved(), "second refine must solve: " + second);
        assertEquals(first.plan, second.plan,
                "repeated refinement must return the same plan");
        assertStateEquals(startSnapshot, start, "the caller's start state must not change");
        assertArrayEquals(yawsBefore, yaws(skeleton),
                "the caller's skeleton must not change");
    }

    @Test
    void candidateBudgetExhaustionReturnsNoPlan() {
        CollisionWorld world = gapWorld();
        MovementSim.State start = startState();
        JumpPlan skeleton = looseSkeleton(start, world);

        SolveResult result = YawRefiner.refine(start, skeleton, tightTarget(), world,
                YawRefinerConfig.of(30.0f, 1.0f, 0.01f, 20, 1));

        assertTrue(result.isNoPlan(), "a one-candidate budget must not produce a plan: "
                + result);
        assertEquals(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED, result.reason,
                "the refiner must report its search budget, not impossibility");
        assertNull(result.plan, "a no-plan result must not carry a plan");
        assertFalse(result.isSolved(), "a no-plan result must not be solved");
    }

    @Test
    void rejectsInvalidConfigValues() {
        assertThrows(IllegalArgumentException.class,
                () -> YawRefinerConfig.of(-1.0f, 1.0f, 0.1f, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> YawRefinerConfig.of(Float.NaN, 1.0f, 0.1f, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> YawRefinerConfig.of(Float.POSITIVE_INFINITY, 1.0f, 0.1f, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> YawRefinerConfig.of(10.0f, 0.0f, 0.1f, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> YawRefinerConfig.of(10.0f, 1.0f, -0.1f, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> YawRefinerConfig.of(10.0f, 1.0f, 0.1f, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> YawRefinerConfig.of(10.0f, 1.0f, 0.1f, 1, 0));
    }

    @Test
    void rejectsInvalidArguments() {
        CollisionWorld world = gapWorld();
        MovementSim.State start = startState();
        JumpPlan skeleton = looseSkeleton(start, world);
        JumpTarget target = tightTarget();
        YawRefinerConfig config = refinerConfig();

        assertThrows(NullPointerException.class,
                () -> YawRefiner.refine(null, skeleton, target, world, config));
        assertThrows(NullPointerException.class,
                () -> YawRefiner.refine(start, null, target, world, config));
        assertThrows(NullPointerException.class,
                () -> YawRefiner.refine(start, skeleton, null, world, config));
        assertThrows(NullPointerException.class,
                () -> YawRefiner.refine(start, skeleton, target, null, config));
        assertThrows(NullPointerException.class,
                () -> YawRefiner.refine(start, skeleton, target, world, null));

        JumpPlan noJump = planWithJumps(0);
        assertThrows(IllegalArgumentException.class,
                () -> YawRefiner.refine(start, noJump, target, world, config));

        JumpPlan twoJumps = planWithJumps(2);
        assertThrows(IllegalArgumentException.class,
                () -> YawRefiner.refine(start, twoJumps, target, world, config));

        List<TraceInput> inputs = new ArrayList<TraceInput>();
        inputs.add(new TraceInput(1, 0, 0, 0, 1, 0, 1, Float.NaN, 0.0f, 1.0f, 0.0f));
        JumpPlan nonFiniteYaw = new JumpPlan(inputs, 0);
        assertThrows(IllegalArgumentException.class,
                () -> YawRefiner.refine(start, nonFiniteYaw, target, world, config));

        MovementSim.State nonFiniteStart = copy(start);
        nonFiniteStart.x = Double.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> YawRefiner.refine(nonFiniteStart, skeleton, target, world, config));
    }

    private static JumpPlan looseSkeleton(MovementSim.State start, CollisionWorld world) {
        SolveResult result = BeamSolver.solve(start, looseTarget(), world, coarseConfig());
        assertTrue(result.isSolved(), "the loose case must solve: " + result);
        return result.plan;
    }

    private static void assertPlanPreserves(MovementSim.State start, JumpPlan skeleton,
            JumpTarget target, CollisionWorld world, JumpPlan plan) {
        assertEquals(skeleton.inputs.size(), plan.inputs.size(),
                "the tick count must not change");
        assertEquals(skeleton.landingTick, plan.landingTick,
                "the landing tick must not change");
        assertEquals(plan.inputs.size() - 1, plan.landingTick,
                "landingTick must remain the last input");

        int jumps = 0;
        boolean yawChanged = false;
        for (int i = 0; i < plan.inputs.size(); i++) {
            TraceInput source = skeleton.inputs.get(i);
            TraceInput input = plan.inputs.get(i);
            assertEquals(source.w, input.w, "w at tick " + i);
            assertEquals(source.a, input.a, "a at tick " + i);
            assertEquals(source.s, input.s, "s at tick " + i);
            assertEquals(source.d, input.d, "d at tick " + i);
            assertEquals(source.jump, input.jump, "jump at tick " + i);
            assertEquals(source.sneak, input.sneak, "sneak at tick " + i);
            assertEquals(source.sprint, input.sprint, "sprint at tick " + i);
            assertEquals(Float.floatToIntBits(source.pitch),
                    Float.floatToIntBits(input.pitch), "pitch at tick " + i);
            assertEquals(Float.floatToIntBits(source.mf),
                    Float.floatToIntBits(input.mf), "mf at tick " + i);
            assertEquals(Float.floatToIntBits(source.ms),
                    Float.floatToIntBits(input.ms), "ms at tick " + i);
            if (input.jump == 1) {
                jumps++;
            }
            if (Float.compare(source.yaw, input.yaw) != 0) {
                yawChanged = true;
            }
        }
        assertEquals(1, jumps, "the refined plan must contain exactly one jump input");
        assertTrue(yawChanged, "the refiner must change at least one yaw");

        MovementSim.State replayed = replay(start, plan, world);
        assertTrue(target.isReachedBy(replayed),
                "the refined plan must replay onto the target; landed at "
                        + replayed.x + "," + replayed.y + "," + replayed.z);
    }

    private static JumpPlan planWithJumps(int jumps) {
        List<TraceInput> inputs = new ArrayList<TraceInput>();
        for (int i = 0; i < 2; i++) {
            inputs.add(new TraceInput(1, 0, 0, 0, i < jumps ? 1 : 0,
                    0, 1, 0.0f, 0.0f, 1.0f, 0.0f));
        }
        return new JumpPlan(inputs, inputs.size() - 1);
    }

    private static float[] yaws(JumpPlan plan) {
        float[] yaws = new float[plan.inputs.size()];
        for (int i = 0; i < yaws.length; i++) {
            yaws[i] = plan.inputs.get(i).yaw;
        }
        return yaws;
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
                0.9, FLOOR_TOP, 0.5,
                0.0, REST_VY, 0.0,
                true, false, false, true);
    }

    private static JumpTarget looseTarget() {
        return JumpTarget.of(1.0, 2.0, 6.0, 7.0, FLOOR_TOP);
    }

    private static JumpTarget tightTarget() {
        double half = TIGHT_SIZE / 2.0;
        return JumpTarget.of(
                TIGHT_CENTER_X - half, TIGHT_CENTER_X + half,
                TIGHT_CENTER_Z - half, TIGHT_CENTER_Z + half,
                FLOOR_TOP, TIGHT_MARGIN);
    }

    private static BeamSearchConfig coarseConfig() {
        return BeamSearchConfig.of(0.0f, 15.0f, COARSE_BEAM_WIDTH, BEAM_BUDGET);
    }

    private static YawRefinerConfig refinerConfig() {
        return YawRefinerConfig.of(30.0f, 1.0f, 0.01f, 20, MAX_CANDIDATES);
    }

    private static CollisionWorld gapWorld() {
        String json = "{\"name\": \"yaw_refiner_gap\","
                + " \"start\": {\"pos\": [0.9, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "{\"from\": [-3, 64, -20], \"to\": [3, 64, 0],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}},"
                + "{\"from\": [-3, 64, 5], \"to\": [3, 64, 25],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}},"
                + "{\"from\": [0, 65, 2], \"to\": [0, 66, 2],"
                + " \"id\": \"fence\","
                + " \"props\": {\"east\": \"false\", \"north\": \"false\","
                + " \"south\": \"false\", \"west\": \"false\"}},"
                + "{\"from\": [1, 65, 2], \"to\": [1, 66, 2],"
                + " \"id\": \"nether_brick_fence\","
                + " \"props\": {\"east\": \"false\", \"north\": \"false\","
                + " \"south\": \"false\", \"west\": \"false\"}}"
                + "], \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(BLOCKS, ScenarioParser.parse(json));
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
