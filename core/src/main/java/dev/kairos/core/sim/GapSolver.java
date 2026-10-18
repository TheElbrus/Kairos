package dev.kairos.core.sim;

import dev.kairos.core.trace.TraceInput;

import java.util.Optional;

/**
 * THROWAWAY Phase 0.5 brute-force planner (task 0.5.2; Phase 1 grows a real search):
 * finds the first jump-start tick whose sprint + single-tick jump clears one straight gap
 * and lands on the far floor.
 *
 * <p>The plan shape is fixed: sprint straight ahead (yaw 0, full forward, sprint key held)
 * for {@code jumpTick} ticks, press jump for exactly one tick, then keep sprinting. The
 * scan tests {@code jumpTick} in ascending order and returns the first plan whose replay
 * ends a tick on the ground at {@link GapWorld#FLOOR_TOP} with the box strictly overlapping
 * at least one far-floor column ({@link GapWorld#overlapsFarFloor}). A candidate whose feet
 * sink below the floor top has left the floor forever (the clamp of
 * AxisAlignedBB.java:142 needs the feet at/above the face) and is discarded immediately.
 *
 * <p>The start state is the exact post-settle state all three committed golden anchors
 * record: {@code pos [0.5, 65.0, 0.5]}, {@code vel [0.0, -0.0784000015258789, 0.0]},
 * {@code onGround}, not sprinting, {@code colV}. The {@code -0.0784000015258789} is the
 * standing gravity step left by the ground clamp (notes section 2.8 step 11); starting
 * from {@code velY = 0} is NOT the harness state and would shift the whole run.
 */
public final class GapSolver {

    /** One tick of the recorded sprint input: full forward, sprint held (yaw 0). */
    public static final TraceInput SPRINT =
            new TraceInput(1, 0, 0, 0, 0, 0, 1, 0.0f, 0.0f, 1.0f, 0.0f);

    /** The jump tick of the plan: SPRINT plus a single-tick jump press. */
    public static final TraceInput SPRINT_JUMP =
            new TraceInput(1, 0, 0, 0, 1, 0, 1, 0.0f, 0.0f, 1.0f, 0.0f);

    /**
     * Visible landing delay: the jump arc (notes sections 1.5/1.7) leaves the floor for
     * exactly 11 ticks, e.g. the sprintjump golden jumps at tick 5 and lands at tick 16.
     */
    private static final int LANDING_DELAY_TICKS = 11;

    /**
     * Ticks replayed after the jump tick before a candidate is given up on: the landing
     * (11 ticks) plus one more tick so the landing itself is always observed.
     */
    private static final int TAIL_TICKS = LANDING_DELAY_TICKS + 1;

    /** Replay cap per candidate; {@code maxJumpStartTick} is validated so it is never hit. */
    private static final int MAX_PLAN_TICKS = 40;

    private static final double ANCHOR_X = 0.5;
    private static final double ANCHOR_Y = 65.0;
    private static final double ANCHOR_Z = 0.5;
    private static final double ANCHOR_VY = -0.0784000015258789;

    private GapSolver() {
    }

    /** A plan: inputs and the landing the replay produces. */
    public static final class Plan {

        /** SPRINT ticks before the jump tick; equals {@code jumpTick}. */
        public final int sprintTicks;

        /** Input tick carrying the single jump press (0-based; tick 0 is the first input). */
        public final int jumpTick;

        /** SPRINT ticks after the jump tick, through the landing tick. */
        public final int tailTicks;

        /** Player z at the end of {@link #landTick} (the landing). */
        public final double landingZ;

        /** Input tick of the landing; always {@code jumpTick + 11} for this arc. */
        public final int landTick;

        private Plan(int jumpTick, int tailTicks, double landingZ, int landTick) {
            this.sprintTicks = jumpTick;
            this.jumpTick = jumpTick;
            this.tailTicks = tailTicks;
            this.landingZ = landingZ;
            this.landTick = landTick;
        }

        @Override
        public String toString() {
            return "Plan[sprintTicks=" + sprintTicks + ", jumpTick=" + jumpTick
                    + ", tailTicks=" + tailTicks + ", landingZ=" + landingZ
                    + ", landTick=" + landTick + "]";
        }
    }

    /**
     * Searches for the earliest plan that clears {@code world} and lands on its far floor.
     *
     * @param world            the floor + straight gap; must not be {@link GapWorld#NONE}
     * @param maxJumpStartTick highest jump tick to try; must leave room for the
     *                         {@code TAIL_TICKS} after it inside {@code MAX_PLAN_TICKS}
     * @return the first plan found, or empty when no candidate lands on the far floor
     */
    public static Optional<Plan> solve(GapWorld world, int maxJumpStartTick) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (!world.isGap()) {
            throw new IllegalArgumentException(
                    "GapSolver needs a world with a gap and a far floor, got " + world);
        }
        if (maxJumpStartTick < 0) {
            throw new IllegalArgumentException(
                    "maxJumpStartTick must be >= 0, got " + maxJumpStartTick);
        }
        int maxAllowed = MAX_PLAN_TICKS - 1 - TAIL_TICKS;
        if (maxJumpStartTick > maxAllowed) {
            throw new IllegalArgumentException(
                    "maxJumpStartTick must be <= " + maxAllowed
                            + " so the " + TAIL_TICKS + "-tick tail of the last candidate"
                            + " stays within MAX_PLAN_TICKS=" + MAX_PLAN_TICKS
                            + ", got " + maxJumpStartTick);
        }

        for (int jumpTick = 0; jumpTick <= maxJumpStartTick; jumpTick++) {
            Plan plan = tryJumpTick(world, jumpTick);
            if (plan != null) {
                return Optional.of(plan);
            }
        }
        return Optional.empty();
    }

    /**
     * Replays one candidate and returns its plan, or {@code null} when it does not land on
     * the far floor: it either leaves the floor (feet below the top, terminal) or its
     * replay window ends still airborne or still on the near floor.
     */
    private static Plan tryJumpTick(GapWorld world, int jumpTick) {
        FlatSim.State s = FlatSim.initialState(
                ANCHOR_X, ANCHOR_Y, ANCHOR_Z,
                0.0, ANCHOR_VY, 0.0,
                true, false, false, true);
        int windowEnd = jumpTick + TAIL_TICKS;
        for (int tick = 0; tick <= windowEnd; tick++) {
            FlatSim.step(s, tick == jumpTick ? SPRINT_JUMP : SPRINT, world);
            if (tick >= jumpTick
                    && s.onGround
                    && s.y == GapWorld.FLOOR_TOP
                    && world.overlapsFarFloor(s.minZ, s.maxZ)) {
                return new Plan(jumpTick, tick - jumpTick, s.z, tick);
            }
            if (s.y < GapWorld.FLOOR_TOP) {
                return null;                            // left the floor: can never land again
            }
        }
        return null;
    }
}
