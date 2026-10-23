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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.4 integration check: JumpSolver sees the positive edge margin through
 * {@link JumpTarget#isReachedBy} and must choose a plan that lands more deeply inside the
 * target instead of accepting the default plan's shallow overlap.
 */
class JumpTargetMarginSolverTest {

    private static final int BUDGET = 80;

    private static final double FLOOR_TOP = 65.0;

    private static final double REST_VY = -0.0784000015258789;

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void jumpSolverEnforcesAPositiveMarginByChoosingADeeperLanding() {
        CollisionWorld world = gapWorld(1);
        MovementSim.State start = startState();

        JumpTarget defaultTarget = target(1, 0.0);
        SolveResult defaultResult = JumpSolver.solve(start, defaultTarget, world, 0.0f, BUDGET);
        assertTrue(defaultResult.isSolved(), "the default gap must solve: " + defaultResult);
        MovementSim.State defaultLanding = replay(start, defaultResult.plan, world);
        assertTrue(defaultTarget.isReachedBy(defaultLanding),
                "the default plan must satisfy the zero-margin target");

        JumpTarget marginTarget = target(1, 0.1);
        assertFalse(marginTarget.isReachedBy(defaultLanding),
                "the default landing must be too shallow for the positive margin");

        SolveResult marginResult = JumpSolver.solve(start, marginTarget, world, 0.0f, BUDGET);
        assertTrue(marginResult.isSolved(),
                "the solver must find a deeper landing: " + marginResult);
        MovementSim.State marginLanding = replay(start, marginResult.plan, world);

        assertTrue(marginTarget.isReachedBy(marginLanding),
                "the margin plan must satisfy the target criterion");
        assertTrue(overlapX(marginLanding, marginTarget) > marginTarget.edgeMargin,
                "the landing must clear the margin on X");
        assertTrue(overlapZ(marginLanding, marginTarget) > marginTarget.edgeMargin,
                "the landing must clear the margin on Z");
    }

    private static double overlapX(MovementSim.State state, JumpTarget target) {
        return Math.min(state.maxX, target.maxX) - Math.max(state.minX, target.minX);
    }

    private static double overlapZ(MovementSim.State state, JumpTarget target) {
        return Math.min(state.maxZ, target.maxZ) - Math.max(state.minZ, target.minZ);
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
        String json = "{\"name\": \"margin_gap" + gapBlocks + "\","
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

    private static JumpTarget target(int gapBlocks, double edgeMargin) {
        return JumpTarget.of(
                -2.0, 3.0,
                5.0 + gapBlocks, 26.0 + gapBlocks,
                FLOOR_TOP, edgeMargin);
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
}
