package dev.kairos.core.solver;

import dev.kairos.core.sim.MovementSim;

/**
 * One settled start state offered to a single-jump solver (PLAN.md 2.5).
 *
 * <p>The state is always on the exact top of the 1x1 start block described by
 * {@link #blockX}, {@link #blockY} and {@link #blockZ}. Its player box is fully inside
 * that block's footprint, and it has been advanced through
 * {@link MovementSim#step} with zero input until the ground clamp settled it. The
 * horizontal position is therefore the candidate position, not a guessed one.
 *
 * <p>{@link #runupDistance} is the catalog run-up distance used to build this variant.
 * Distance 0 is a standing start; a positive distance carries the exact horizontal
 * velocity and timers produced by the deterministic W+sprint run-up in
 * {@link RunupStartBuilder}. The state is mutable, like every other
 * {@link MovementSim.State} in the solver package; callers must not change it.
 */
public final class StartState {

    /** The settled state; never mutated by the builders or solvers. */
    public final MovementSim.State state;

    /** Catalog run-up distance behind the start block, with 0 at the start block. */
    public final int runupDistance;

    /** The 1x1 start block whose top this state stands on. */
    public final int blockX;
    public final int blockY;
    public final int blockZ;

    /** Candidate offset from the block centre on each horizontal axis. */
    public final double offsetX;
    public final double offsetZ;

    StartState(MovementSim.State state, int runupDistance, int blockX, int blockY,
            int blockZ, double offsetX, double offsetZ) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (runupDistance < 0) {
            throw new IllegalArgumentException("runupDistance must be >= 0, got "
                    + runupDistance);
        }
        this.state = state;
        this.runupDistance = runupDistance;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    @Override
    public String toString() {
        return "StartState[runup=" + this.runupDistance
                + ", block=" + this.blockX + "," + this.blockY + "," + this.blockZ
                + ", offset=" + this.offsetX + "," + this.offsetZ
                + ", pos=" + this.state.x + "," + this.state.y + "," + this.state.z
                + ", vel=" + this.state.vx + "," + this.state.vy + "," + this.state.vz
                + "]";
    }
}
