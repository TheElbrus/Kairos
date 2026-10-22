package dev.kairos.core.solver;

import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Baseline single-jump solver for task 2.2: a fixed yaw and a deterministic
 * input-schedule search over the exact Phase 1 simulator.
 *
 * <p>The schedule family is deliberately small and explainable. The caller supplies the
 * start state, target platform, collision world, one yaw for every tick, and a tick budget.
 * The search tries every jump-start tick from {@code 0} to {@code tickBudget - 1}. Before
 * the jump it holds one W/sprint combination; from the jump tick onward it holds another.
 * Each combination has the four possibilities {@code (W+sprint, W, sprint, neither)}.
 * Every candidate presses jump for exactly one tick, never sneaks, never strafes and never
 * changes yaw. There is no RNG, no beam search, no per-tick yaw and no time-based stop.
 *
 * <p>Each candidate is replayed from a fresh copy of the caller's state through
 * {@link MovementSim#step}. A plan is accepted only when the <em>replayed</em> state is on
 * the ground at the target surface with its player box overlapping the target rectangle.
 * The solver stores no predicted landing in the plan; {@link JumpPlan} contains only the
 * exact {@link TraceInput} sequence and the landing tick. A failed search returns
 * {@link SolveResult.NoPlanReason#SEARCH_BUDGET_EXHAUSTED}; that is explicitly not a claim
 * that the jump is impossible.
 *
 * <p>No physics is added here. The target test is the task's landing criterion, and every
 * movement/collision rule remains in {@link MovementSim} and {@link CollisionWorld}.
 */
public final class JumpSolver {

    /**
     * Held-key combinations in deterministic preference order: W+sprint, W, sprint, none.
     * Element 0 is the W key, element 1 is the sprint key.
     */
    private static final int[][] HELD_KEYS = {
            {1, 1},
            {1, 0},
            {0, 1},
            {0, 0}
    };

    private JumpSolver() {
    }

    /**
     * Searches for one landing on {@code target}.
     *
     * @param start      caller's start state; it is never mutated
     * @param target     target platform surface and horizontal extent
     * @param world      exact collision world; it is queried, never edited
     * @param yaw        fixed yaw for every tick
     * @param tickBudget maximum number of ticks in one candidate replay (must be >= 1)
     * @return a replay-checked plan, or an explicit no-plan result
     */
    public static SolveResult solve(MovementSim.State start, JumpTarget target,
            CollisionWorld world, float yaw, int tickBudget) {
        if (start == null) {
            throw new NullPointerException("start");
        }
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (Float.isNaN(yaw) || Float.isInfinite(yaw)) {
            throw new IllegalArgumentException("yaw must be finite, got " + yaw);
        }
        if (tickBudget < 1) {
            throw new IllegalArgumentException("tickBudget must be >= 1, got " + tickBudget);
        }

        for (int jumpTick = 0; jumpTick < tickBudget; jumpTick++) {
            for (int[] runKeys : HELD_KEYS) {
                for (int[] airKeys : HELD_KEYS) {
                    JumpPlan plan = replayCandidate(start, target, world, yaw, tickBudget,
                            jumpTick, runKeys, airKeys);
                    if (plan != null) {
                        return SolveResult.solved(plan);
                    }
                }
            }
        }
        return SolveResult.noPlan(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED);
    }

    /**
     * Replays one deterministic candidate. The jump tick is the first tick of the
     * jump-and-air phase, so the air keys also control the jump tick itself.
     */
    private static JumpPlan replayCandidate(MovementSim.State start, JumpTarget target,
            CollisionWorld world, float yaw, int tickBudget, int jumpTick,
            int[] runKeys, int[] airKeys) {
        MovementSim.State state = copy(start);
        List<TraceInput> inputs = new ArrayList<TraceInput>(Math.min(tickBudget, 64));

        for (int tick = 0; tick < tickBudget; tick++) {
            int[] keys = tick < jumpTick ? runKeys : airKeys;
            int w = keys[0];
            int sprint = keys[1];
            TraceInput input = new TraceInput(
                    w, 0, 0, 0,
                    tick == jumpTick ? 1 : 0,
                    0,
                    sprint,
                    yaw, 0.0f,
                    w == 1 ? 1.0f : 0.0f,
                    0.0f);
            inputs.add(input);
            MovementSim.step(state, input, world);

            if (tick == jumpTick) {
                // A start with jumpTicks on cooldown, or an airborne start, consumes the
                // jump key without jumping. That is not a single-jump candidate.
                if (state.jumpTicks == 0) {
                    return null;
                }
            }
            if (tick >= jumpTick && target.isReachedBy(state)) {
                return new JumpPlan(inputs, tick);
            }
        }
        return null;
    }

    /** Copies every cross-tick field so a candidate cannot mutate the caller's state. */
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
