package dev.kairos.core.solver;

import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.util.List;

/**
 * Tries several settled start states with one single-jump solver (PLAN.md 2.5).
 *
 * <p>The list order is the search order. For every start the selected solver is called
 * with that exact state; a solved plan is replayed once more from a fresh copy of the
 * same start before it is returned. The wrapper never sorts, samples or randomises the
 * starts. If every start fails, the solver's no-plan reason (normally
 * {@link SolveResult.NoPlanReason#SEARCH_BUDGET_EXHAUSTED}) is preserved; no impossibility
 * claim is added.
 *
 * <p>The wrapper is additive: it does not change {@link JumpSolver}, {@link BeamSolver}
 * or any existing single-start result.
 */
public final class MultiStartSolver {

    private MultiStartSolver() {
    }

    /** Tries every start in order with the fixed-yaw baseline solver. */
    public static MultiStartResult solveJump(List<StartState> starts, JumpTarget target,
            CollisionWorld world, final float yaw, final int tickBudget) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (world == null) {
            throw new NullPointerException("world");
        }
        return search(starts, target, world, new Search() {
            @Override
            public SolveResult solve(StartState start) {
                return JumpSolver.solve(start.state, target, world, yaw, tickBudget);
            }
        });
    }

    /** Tries every start in order with the bounded beam solver. */
    public static MultiStartResult solveBeam(List<StartState> starts, JumpTarget target,
            CollisionWorld world, final BeamSearchConfig config) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (config == null) {
            throw new NullPointerException("config");
        }
        return search(starts, target, world, new Search() {
            @Override
            public SolveResult solve(StartState start) {
                return BeamSolver.solve(start.state, target, world, config);
            }
        });
    }

    private static MultiStartResult search(List<StartState> starts, JumpTarget target,
            CollisionWorld world, Search search) {
        if (starts == null) {
            throw new NullPointerException("starts");
        }
        SolveResult.NoPlanReason reason = null;
        for (int i = 0; i < starts.size(); i++) {
            StartState start = starts.get(i);
            if (start == null) {
                throw new NullPointerException("starts[" + i + "]");
            }
            SolveResult result = search.solve(start);
            if (result.isSolved()) {
                if (!replays(start.state, result.plan, target, world)) {
                    throw new IllegalStateException("solver returned a plan that does not"
                            + " replay onto the target from " + start);
                }
                return MultiStartResult.solved(start, result.plan);
            }
            if (reason == null) {
                reason = result.reason;
            }
        }
        if (reason == null) {
            reason = SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED;
        }
        return MultiStartResult.noPlan(reason);
    }

    private static boolean replays(MovementSim.State start, JumpPlan plan,
            JumpTarget target, CollisionWorld world) {
        MovementSim.State state = copy(start);
        for (TraceInput input : plan.inputs) {
            MovementSim.step(state, input, world);
        }
        return target.isReachedBy(state);
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

    private interface Search {
        SolveResult solve(StartState start);
    }
}
