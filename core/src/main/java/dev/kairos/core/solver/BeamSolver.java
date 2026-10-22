package dev.kairos.core.solver;

import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded deterministic beam search for one jump (PLAN.md 2.3).
 *
 * <p>The search applies a coarse per-tick yaw grid around the current yaw and may press or
 * release the sneak and sprint keys. It never treats a beam cut-off or tick-budget cut-off
 * as proof that the target is impossible. The beam keeps separate quotas for pre-jump and
 * airborne post-jump states, so a fast aerial branch cannot crowd out the run-up. States
 * that reach the target before jumping are discarded because the plan must contain exactly
 * one jump. A candidate is returned only after its complete input list has been replayed
 * from a fresh copy of the caller's state through {@link MovementSim#step}.
 */
public final class BeamSolver {

    private static final Move[] MOVES = {
            new Move(1, 0, 0, 0, 1.0f, 0.0f),
            new Move(1, 1, 0, 0, 1.0f, 1.0f),
            new Move(1, 0, 0, 1, 1.0f, -1.0f),
            new Move(0, 1, 0, 0, 0.0f, 1.0f),
            new Move(0, 0, 0, 1, 0.0f, -1.0f),
            new Move(0, 0, 0, 0, 0.0f, 0.0f)
    };

    private static final int[] SNEAK_VALUES = {0, 1};

    private static final int[] SPRINT_VALUES = {1, 0};

    private static final Comparator<Node> NODE_ORDER = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            int byScore = Double.compare(a.score, b.score);
            if (byScore != 0) {
                return byScore;
            }
            return Long.compare(a.order, b.order);
        }
    };

    private BeamSolver() {
    }

    /**
     * Searches for a single jump landing on {@code target}.
     *
     * @param start  caller's start state; it is never mutated
     * @param target target platform surface and horizontal extent
     * @param world  exact collision world; it is queried, never edited
     * @param config deterministic beam width, tick budget and coarse yaw grid
     * @return a replay-checked plan, or the explicit budget-exhausted result
     */
    public static SolveResult solve(MovementSim.State start, JumpTarget target,
            CollisionWorld world, BeamSearchConfig config) {
        if (start == null) {
            throw new NullPointerException("start");
        }
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (config == null) {
            throw new NullPointerException("config");
        }

        List<Node> beam = new ArrayList<Node>();
        beam.add(new Node(copy(start), null, null, config.startYaw, false, 0,
                0L, score(start, target)));
        long[] nextOrder = {1L};

        for (int depth = 1; depth <= config.tickBudget; depth++) {
            List<Node> next = new ArrayList<Node>();
            Set<StateKey> seen = config.deduplicate
                    ? new HashSet<StateKey>() : null;

            for (Node parent : beam) {
                JumpPlan plan = expand(parent, target, world, config, start, next, seen,
                        nextOrder, depth);
                if (plan != null) {
                    return SolveResult.solved(plan);
                }
            }

            if (next.isEmpty()) {
                return SolveResult.noPlan(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED);
            }
            Collections.sort(next, NODE_ORDER);
            if (next.size() > config.beamWidth) {
                next = selectBeam(next, config.beamWidth);
            }
            beam = next;
        }

        return SolveResult.noPlan(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED);
    }

    private static JumpPlan expand(Node parent, JumpTarget target, CollisionWorld world,
            BeamSearchConfig config, MovementSim.State start, List<Node> out,
            Set<StateKey> seen, long[] nextOrder, int depth) {
        if (!parent.jumped && target.isReachedBy(parent.state)) {
            return null;
        }
        float[] yaws = yawChoices(parent.yaw, config.yawDeltaDegrees);
        for (int moveIndex = 0; moveIndex < MOVES.length; moveIndex++) {
            Move move = MOVES[moveIndex];
            for (int yawIndex = 0; yawIndex < yaws.length; yawIndex++) {
                float yaw = yaws[yawIndex];
                for (int sneak : SNEAK_VALUES) {
                    for (int sprint : SPRINT_VALUES) {
                        JumpPlan withoutJump = tryAction(parent, target, world, start,
                                move, yaw, sneak, sprint, 0, out, seen, nextOrder, depth);
                        if (withoutJump != null) {
                            return withoutJump;
                        }
                        if (!parent.jumped && parent.state.onGround
                                && parent.state.jumpTicks == 0) {
                            JumpPlan withJump = tryAction(parent, target, world, start,
                                    move, yaw, sneak, sprint, 1, out, seen, nextOrder,
                                    depth);
                            if (withJump != null) {
                                return withJump;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static JumpPlan tryAction(Node parent, JumpTarget target, CollisionWorld world,
            MovementSim.State start, Move move, float yaw, int sneak, int sprint, int jump,
            List<Node> out, Set<StateKey> seen, long[] nextOrder, int depth) {
        MovementSim.State state = copy(parent.state);
        TraceInput input = new TraceInput(
                move.w, move.a, move.s, move.d,
                jump, sneak, sprint,
                yaw, 0.0f, move.mf, move.ms);
        MovementSim.step(state, input, world);

        boolean jumped = parent.jumped || jump == 1;
        Node child = new Node(state, parent, input, yaw, jumped, depth,
                nextOrder[0]++, score(state, target));

        if (jumped && target.isReachedBy(state)) {
            JumpPlan plan = planFor(child);
            if (replays(start, plan, world, target)) {
                return plan;
            }
        }
        if (!jumped && target.isReachedBy(state)) {
            return null;
        }

        if (seen != null) {
            StateKey key = new StateKey(state, yaw, jumped);
            if (!seen.add(key)) {
                return null;
            }
        }
        out.add(child);
        return null;
    }

    private static float[] yawChoices(float currentYaw, float delta) {
        if (delta == 0.0f) {
            return new float[] {currentYaw};
        }
        float minus = currentYaw - delta;
        float plus = currentYaw + delta;
        if (Float.floatToIntBits(minus) == Float.floatToIntBits(currentYaw)) {
            if (Float.floatToIntBits(plus) == Float.floatToIntBits(currentYaw)) {
                return new float[] {currentYaw};
            }
            return new float[] {currentYaw, plus};
        }
        if (Float.floatToIntBits(plus) == Float.floatToIntBits(currentYaw)) {
            return new float[] {currentYaw, minus};
        }
        return new float[] {currentYaw, minus, plus};
    }

    private static List<Node> selectBeam(List<Node> sorted, int width) {
        List<Node> selected = new ArrayList<Node>(width);
        boolean[] taken = new boolean[sorted.size()];
        int notJumpedQuota = (width + 1) / 2;
        int airborneQuota = width / 2;
        int notJumped = 0;
        for (int i = 0; i < sorted.size() && notJumped < notJumpedQuota; i++) {
            Node node = sorted.get(i);
            if (!node.jumped) {
                selected.add(node);
                taken[i] = true;
                notJumped++;
            }
        }
        int airborne = 0;
        for (int i = 0; i < sorted.size() && airborne < airborneQuota; i++) {
            Node node = sorted.get(i);
            if (!taken[i] && node.jumped && !node.state.onGround) {
                selected.add(node);
                taken[i] = true;
                airborne++;
            }
        }
        for (int i = 0; i < sorted.size() && selected.size() < width; i++) {
            if (!taken[i]) {
                selected.add(sorted.get(i));
            }
        }
        Collections.sort(selected, NODE_ORDER);
        return selected;
    }

    private static double score(MovementSim.State state, JumpTarget target) {
        double nearestX = Math.max(target.minX, Math.min(state.x, target.maxX));
        double nearestZ = Math.max(target.minZ, Math.min(state.z, target.maxZ));
        double dx = state.x - nearestX;
        double dz = state.z - nearestZ;
        double dy = state.y - target.surfaceY;
        double result = dx * dx + dz * dz + dy * dy;
        if (state.y < target.surfaceY) {
            double drop = target.surfaceY - state.y;
            result += drop * drop * 64.0;
        }
        double dirX = nearestX - state.x;
        double dirZ = nearestZ - state.z;
        double length = StrictMath.sqrt(dirX * dirX + dirZ * dirZ);
        if (length > 0.0) {
            result -= 0.5 * (state.vx * dirX + state.vz * dirZ) / length;
        }
        return result;
    }

    private static JumpPlan planFor(Node node) {
        List<TraceInput> inputs = new ArrayList<TraceInput>(node.depth);
        for (Node current = node; current.parent != null; current = current.parent) {
            inputs.add(current.input);
        }
        Collections.reverse(inputs);
        return new JumpPlan(inputs, node.depth - 1);
    }

    private static boolean replays(MovementSim.State start, JumpPlan plan,
            CollisionWorld world, JumpTarget target) {
        MovementSim.State state = copy(start);
        int jumps = 0;
        for (TraceInput input : plan.inputs) {
            MovementSim.step(state, input, world);
            if (input.jump == 1 && state.jumpTicks == 10) {
                jumps++;
            }
        }
        return jumps == 1 && target.isReachedBy(state);
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

    private static final class Move {
        final int w;
        final int a;
        final int s;
        final int d;
        final float mf;
        final float ms;

        Move(int w, int a, int s, int d, float mf, float ms) {
            this.w = w;
            this.a = a;
            this.s = s;
            this.d = d;
            this.mf = mf;
            this.ms = ms;
        }
    }

    private static final class Node {
        final MovementSim.State state;
        final Node parent;
        final TraceInput input;
        final float yaw;
        final boolean jumped;
        final int depth;
        final long order;
        final double score;

        Node(MovementSim.State state, Node parent, TraceInput input, float yaw,
                boolean jumped, int depth, long order, double score) {
            this.state = state;
            this.parent = parent;
            this.input = input;
            this.yaw = yaw;
            this.jumped = jumped;
            this.depth = depth;
            this.order = order;
            this.score = score;
        }
    }

    private static final class StateKey {
        private final long[] values;
        private final int hash;

        StateKey(MovementSim.State state, float yaw, boolean jumped) {
            this.values = new long[] {
                    Double.doubleToLongBits(state.x),
                    Double.doubleToLongBits(state.y),
                    Double.doubleToLongBits(state.z),
                    Double.doubleToLongBits(state.vx),
                    Double.doubleToLongBits(state.vy),
                    Double.doubleToLongBits(state.vz),
                    Double.doubleToLongBits(state.minX),
                    Double.doubleToLongBits(state.maxX),
                    Double.doubleToLongBits(state.minZ),
                    Double.doubleToLongBits(state.maxZ),
                    Double.doubleToLongBits(state.maxY),
                    flags(state, jumped),
                    state.jumpTicks,
                    state.sprintingTicksLeft,
                    state.sprintToggleTimer,
                    Float.floatToIntBits(state.moveForward),
                    Float.floatToIntBits(state.jumpMovementFactor),
                    Float.floatToIntBits(yaw)
            };
            int h = 1;
            for (long value : this.values) {
                h = 31 * h + (int) (value ^ (value >>> 32));
            }
            this.hash = h;
        }

        private static long flags(MovementSim.State state, boolean jumped) {
            long result = 0L;
            if (state.onGround) {
                result |= 1L;
            }
            if (state.sprinting) {
                result |= 1L << 1;
            }
            if (state.colH) {
                result |= 1L << 2;
            }
            if (state.colV) {
                result |= 1L << 3;
            }
            if (state.prevSneak) {
                result |= 1L << 4;
            }
            if (jumped) {
                result |= 1L << 5;
            }
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StateKey)) {
                return false;
            }
            StateKey that = (StateKey) other;
            if (this.hash != that.hash || this.values.length != that.values.length) {
                return false;
            }
            for (int i = 0; i < this.values.length; i++) {
                if (this.values[i] != that.values[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
