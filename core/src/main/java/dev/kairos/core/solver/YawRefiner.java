package dev.kairos.core.solver;

import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic per-tick yaw refinement for one rough single-jump plan (PLAN.md 2.6,
 * section 8.2).
 *
 * <p>The caller supplies a complete {@link JumpPlan} skeleton. Every non-yaw field of every
 * {@link TraceInput} is copied unchanged, as are the tick count, landing tick and single
 * jump tick. Only the yaw values are searched. The skeleton is allowed to be a plan for a
 * looser target; it does not have to reach the requested target.
 *
 * <p>The search is coarse to fine and deterministic. It first sweeps one shared yaw offset,
 * then runs coordinate passes over the individual ticks: the first pass uses the configured
 * coarse step, later passes use the fine step. Candidates are tried centre first, then
 * alternating negative and positive offsets. There is no RNG and no hashing order. Every
 * candidate is replayed from a fresh copy of the caller's start through
 * {@link MovementSim#step}; only {@link JumpTarget#isReachedBy(MovementSim.State)} decides
 * success. A successful plan is replayed once more before it is returned.
 *
 * <p>Non-landing candidates are ranked from the exact final state. The score prefers the
 * target's horizontal rectangle and exact surface, penalises an overlap deficit against the
 * target's {@code edgeMargin}, penalises horizontal block collisions strongly and a
 * vertical collision on a non-target surface mildly. The score is only a search guide; it
 * cannot accept a plan.
 */
public final class YawRefiner {

    private static final double VERTICAL_WEIGHT = 4.0;
    private static final double BELOW_TARGET_WEIGHT = 16.0;
    private static final double MARGIN_WEIGHT = 100.0;
    private static final double HORIZONTAL_COLLISION_WEIGHT = 100.0;
    private static final double VERTICAL_COLLISION_WEIGHT = 5.0;

    private YawRefiner() {
    }

    /**
     * Refines the per-tick yaw of {@code skeleton} for {@code target}.
     *
     * @param start    caller's start state; it is never mutated
     * @param skeleton rough plan whose non-yaw inputs are preserved
     * @param target   exact target surface and horizontal extent
     * @param world    exact collision world; it is queried, never edited
     * @param config   deterministic search bounds
     * @return a replay-checked refined plan, or {@code SEARCH_BUDGET_EXHAUSTED}
     */
    public static SolveResult refine(MovementSim.State start, JumpPlan skeleton,
            JumpTarget target, CollisionWorld world, YawRefinerConfig config) {
        requireArguments(start, skeleton, target, world, config);
        float[] originalYaws = new float[skeleton.inputs.size()];
        for (int i = 0; i < originalYaws.length; i++) {
            originalYaws[i] = skeleton.inputs.get(i).yaw;
        }
        return new Search(start, skeleton, target, world, config, originalYaws).run();
    }

    private static void requireArguments(MovementSim.State start, JumpPlan skeleton,
            JumpTarget target, CollisionWorld world, YawRefinerConfig config) {
        if (start == null) {
            throw new NullPointerException("start");
        }
        if (skeleton == null) {
            throw new NullPointerException("skeleton");
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
        requireFiniteState(start);
        int jumps = 0;
        for (int i = 0; i < skeleton.inputs.size(); i++) {
            TraceInput input = skeleton.inputs.get(i);
            if (input == null) {
                throw new IllegalArgumentException("skeleton input " + i + " is null");
            }
            requireFinite("skeleton input " + i + " yaw", input.yaw);
            requireFinite("skeleton input " + i + " pitch", input.pitch);
            requireFinite("skeleton input " + i + " mf", input.mf);
            requireFinite("skeleton input " + i + " ms", input.ms);
            if (input.jump == 1) {
                jumps++;
            }
        }
        if (jumps != 1) {
            throw new IllegalArgumentException(
                    "skeleton must contain exactly one jump input, got " + jumps);
        }
    }

    private static void requireFiniteState(MovementSim.State state) {
        requireFinite("start x", state.x);
        requireFinite("start y", state.y);
        requireFinite("start z", state.z);
        requireFinite("start vx", state.vx);
        requireFinite("start vy", state.vy);
        requireFinite("start vz", state.vz);
        requireFinite("start minX", state.minX);
        requireFinite("start maxX", state.maxX);
        requireFinite("start minZ", state.minZ);
        requireFinite("start maxZ", state.maxZ);
        requireFinite("start maxY", state.maxY);
        requireFinite("start moveForward", state.moveForward);
        requireFinite("start jumpMovementFactor", state.jumpMovementFactor);
    }

    private static void requireFinite(String name, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }

    private static void requireFinite(String name, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }

    private static final class Search {

        private final MovementSim.State start;
        private final JumpPlan skeleton;
        private final JumpTarget target;
        private final CollisionWorld world;
        private final YawRefinerConfig config;
        private final float[] originalYaws;
        private final float[] bestYaws;
        private int remainingCandidates;
        private double bestScore;
        private SolveResult solution;

        Search(MovementSim.State start, JumpPlan skeleton, JumpTarget target,
                CollisionWorld world, YawRefinerConfig config, float[] originalYaws) {
            this.start = start;
            this.skeleton = skeleton;
            this.target = target;
            this.world = world;
            this.config = config;
            this.originalYaws = originalYaws;
            this.bestYaws = originalYaws.clone();
            this.remainingCandidates = config.maxCandidates;
        }

        SolveResult run() {
            Evaluation initial = evaluate(this.originalYaws);
            if (initial == null) {
                return noPlan();
            }
            if (initial.reached) {
                return solved(this.originalYaws);
            }
            this.bestScore = initial.score;

            if (!sweepSharedOffset()) {
                return this.solution != null ? this.solution : noPlan();
            }
            if (!coordinatePasses()) {
                return this.solution != null ? this.solution : noPlan();
            }
            return this.solution != null ? this.solution : noPlan();
        }

        /**
         * Tries the same yaw offset on every tick. The centre is already the initial
         * candidate, so only non-zero offsets are replayed.
         *
         * @return false when the candidate budget ran out
         */
        private boolean sweepSharedOffset() {
            int steps = stepCount(this.config.coarseRangeDegrees,
                    this.config.coarseStepDegrees);
            for (int k = 1; k <= steps; k++) {
                float delta = (float) k * this.config.coarseStepDegrees;
                if (!trySharedOffset(-delta) || !trySharedOffset(delta)) {
                    return false;
                }
            }
            if ((double) steps * (double) this.config.coarseStepDegrees
                    < (double) this.config.coarseRangeDegrees) {
                if (!trySharedOffset(-this.config.coarseRangeDegrees)
                        || !trySharedOffset(this.config.coarseRangeDegrees)) {
                    return false;
                }
            }
            return true;
        }

        private boolean trySharedOffset(float offset) {
            float[] candidate = new float[this.originalYaws.length];
            for (int i = 0; i < candidate.length; i++) {
                candidate[i] = this.originalYaws[i] + offset;
            }
            Evaluation evaluation = evaluate(candidate);
            if (evaluation == null) {
                return false;
            }
            if (evaluation.reached) {
                this.solution = solved(candidate);
                return false;
            }
            if (evaluation.score < this.bestScore) {
                this.bestScore = evaluation.score;
                System.arraycopy(candidate, 0, this.bestYaws, 0, candidate.length);
            }
            return true;
        }

        /**
         * Runs coordinate passes. The first pass uses the coarse step; later passes use the
         * fine step. A pass with no improvement stops the fine stage.
         *
         * @return false when the candidate budget ran out
         */
        private boolean coordinatePasses() {
            for (int pass = 0; pass < this.config.maxPasses; pass++) {
                float radius = pass == 0
                        ? this.config.coarseRangeDegrees
                        : Math.min(this.config.coarseStepDegrees,
                                this.config.coarseRangeDegrees);
                float step = pass == 0
                        ? this.config.coarseStepDegrees
                        : this.config.fineStepDegrees;
                boolean improved = false;
                for (int tick = 0; tick < this.bestYaws.length; tick++) {
                    float centre = this.bestYaws[tick];
                    float chosen = centre;
                    double chosenScore = this.bestScore;
                    int steps = stepCount(radius, step);
                    for (int k = 1; k <= steps; k++) {
                        float delta = (float) k * step;
                        TickResult minus = tryTick(tick, centre, -delta);
                        if (minus == null) {
                            return false;
                        }
                        if (minus.reached) {
                            return false;
                        }
                        if (minus.score < chosenScore) {
                            chosenScore = minus.score;
                            chosen = centre - delta;
                        }
                        TickResult plus = tryTick(tick, centre, delta);
                        if (plus == null) {
                            return false;
                        }
                        if (plus.reached) {
                            return false;
                        }
                        if (plus.score < chosenScore) {
                            chosenScore = plus.score;
                            chosen = centre + delta;
                        }
                    }
                    if ((double) steps * (double) step < (double) radius) {
                        TickResult minus = tryTick(tick, centre, -radius);
                        if (minus == null) {
                            return false;
                        }
                        if (minus.reached) {
                            return false;
                        }
                        if (minus.score < chosenScore) {
                            chosenScore = minus.score;
                            chosen = centre - radius;
                        }
                        TickResult plus = tryTick(tick, centre, radius);
                        if (plus == null) {
                            return false;
                        }
                        if (plus.reached) {
                            return false;
                        }
                        if (plus.score < chosenScore) {
                            chosenScore = plus.score;
                            chosen = centre + radius;
                        }
                    }
                    if (Float.floatToIntBits(chosen) != Float.floatToIntBits(centre)) {
                        this.bestYaws[tick] = chosen;
                        this.bestScore = chosenScore;
                        improved = true;
                    }
                }
                if (!improved && pass > 0) {
                    return true;
                }
            }
            return true;
        }

        private TickResult tryTick(int tick, float centre, float delta) {
            float[] candidate = this.bestYaws.clone();
            candidate[tick] = centre + delta;
            Evaluation evaluation = evaluate(candidate);
            if (evaluation == null) {
                return null;
            }
            if (evaluation.reached) {
                this.solution = solved(candidate);
                return new TickResult(Double.NEGATIVE_INFINITY, true);
            }
            return new TickResult(evaluation.score, false);
        }

        private Evaluation evaluate(float[] yaws) {
            if (this.remainingCandidates <= 0) {
                return null;
            }
            this.remainingCandidates--;
            List<TraceInput> inputs = inputsFor(yaws);
            MovementSim.State state = replay(inputs);
            if (this.target.isReachedBy(state)) {
                return new Evaluation(0.0, true);
            }
            return new Evaluation(score(state), false);
        }

        private SolveResult solved(float[] yaws) {
            List<TraceInput> inputs = inputsFor(yaws);
            JumpPlan plan = new JumpPlan(inputs, this.skeleton.landingTick);
            MovementSim.State replayed = replay(inputs);
            if (!this.target.isReachedBy(replayed)) {
                return noPlan();
            }
            return SolveResult.solved(plan);
        }

        private List<TraceInput> inputsFor(float[] yaws) {
            List<TraceInput> inputs =
                    new ArrayList<TraceInput>(this.skeleton.inputs.size());
            for (int i = 0; i < this.skeleton.inputs.size(); i++) {
                TraceInput source = this.skeleton.inputs.get(i);
                inputs.add(new TraceInput(
                        source.w, source.a, source.s, source.d,
                        source.jump, source.sneak, source.sprint,
                        yaws[i], source.pitch, source.mf, source.ms));
            }
            return inputs;
        }

        private MovementSim.State replay(List<TraceInput> inputs) {
            MovementSim.State state = copy(this.start);
            for (TraceInput input : inputs) {
                MovementSim.step(state, input, this.world);
            }
            return state;
        }

        private double score(MovementSim.State state) {
            double gapX = horizontalGap(state.minX, state.maxX,
                    this.target.minX, this.target.maxX);
            double gapZ = horizontalGap(state.minZ, state.maxZ,
                    this.target.minZ, this.target.maxZ);
            double dy = state.y - this.target.surfaceY;
            double score = gapX * gapX + gapZ * gapZ
                    + VERTICAL_WEIGHT * dy * dy;
            if (state.y < this.target.surfaceY) {
                double below = this.target.surfaceY - state.y;
                score += BELOW_TARGET_WEIGHT * below * below;
            }
            if (state.onGround
                    && Double.compare(state.y, this.target.surfaceY) == 0) {
                double overlapX = overlap(state.minX, state.maxX,
                        this.target.minX, this.target.maxX);
                double overlapZ = overlap(state.minZ, state.maxZ,
                        this.target.minZ, this.target.maxZ);
                double deficit = Math.max(0.0, this.target.edgeMargin - overlapX)
                        + Math.max(0.0, this.target.edgeMargin - overlapZ);
                score += MARGIN_WEIGHT * deficit;
            }
            if (state.colH) {
                score += HORIZONTAL_COLLISION_WEIGHT;
            }
            if (state.colV && !(state.onGround
                    && Double.compare(state.y, this.target.surfaceY) == 0)) {
                score += VERTICAL_COLLISION_WEIGHT;
            }
            return score;
        }
    }

    private static int stepCount(float range, float step) {
        double raw = Math.floor((double) range / (double) step);
        if (raw <= 0.0) {
            return 0;
        }
        if (raw > (double) Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) raw;
    }

    private static double horizontalGap(double min, double max,
            double targetMin, double targetMax) {
        if (max <= targetMin) {
            return targetMin - max;
        }
        if (min >= targetMax) {
            return min - targetMax;
        }
        return 0.0;
    }

    private static double overlap(double min, double max,
            double targetMin, double targetMax) {
        return Math.min(max, targetMax) - Math.max(min, targetMin);
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

    private static SolveResult noPlan() {
        return SolveResult.noPlan(SolveResult.NoPlanReason.SEARCH_BUDGET_EXHAUSTED);
    }

    private static final class Evaluation {
        final double score;
        final boolean reached;

        Evaluation(double score, boolean reached) {
            this.score = score;
            this.reached = reached;
        }
    }

    private static final class TickResult {
        final double score;
        final boolean reached;

        TickResult(double score, boolean reached) {
            this.score = score;
            this.reached = reached;
        }
    }
}
