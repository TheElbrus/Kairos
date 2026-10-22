package dev.kairos.core.solver;

import dev.kairos.core.sim.MovementSim;

/**
 * The top surface of a target landing platform for the baseline solver (PLAN.md 2.2).
 *
 * <p>The target is a horizontal rectangle at one exact surface height. A state has reached
 * it when {@link MovementSim.State#onGround} is true, the feet are exactly on
 * {@link #surfaceY}, and the player's X/Z box strictly overlaps the rectangle. The exact
 * height comparison is deliberate: the simulator's ground clamp leaves a settled player on
 * a block top as a fixed double, and the solver must not accept a landing on a different
 * block that happens to share the same floor height.
 */
public final class JumpTarget {

    public final double minX;
    public final double maxX;
    public final double minZ;
    public final double maxZ;
    public final double surfaceY;

    private JumpTarget(double minX, double maxX, double minZ, double maxZ, double surfaceY) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.surfaceY = surfaceY;
    }

    /**
     * Builds a target rectangle.
     *
     * @param minX     west edge (inclusive in the overlap test, but touching is not overlap)
     * @param maxX     east edge
     * @param minZ     north edge
     * @param maxZ     south edge
     * @param surfaceY exact Y of the platform top
     */
    public static JumpTarget of(double minX, double maxX, double minZ, double maxZ,
            double surfaceY) {
        requireFinite("minX", minX);
        requireFinite("maxX", maxX);
        requireFinite("minZ", minZ);
        requireFinite("maxZ", maxZ);
        requireFinite("surfaceY", surfaceY);
        if (minX >= maxX) {
            throw new IllegalArgumentException("minX must be less than maxX, got "
                    + minX + " >= " + maxX);
        }
        if (minZ >= maxZ) {
            throw new IllegalArgumentException("minZ must be less than maxZ, got "
                    + minZ + " >= " + maxZ);
        }
        return new JumpTarget(minX, maxX, minZ, maxZ, surfaceY);
    }

    /**
     * True when the state is standing on this exact surface and its player box overlaps the
     * target rectangle on both horizontal axes. Touching edges do not count, matching the
     * game's strict AABB overlap.
     */
    public boolean isReachedBy(MovementSim.State state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        return state.onGround
                && Double.compare(state.y, this.surfaceY) == 0
                && state.maxX > this.minX
                && state.minX < this.maxX
                && state.maxZ > this.minZ
                && state.minZ < this.maxZ;
    }

    private static void requireFinite(String name, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }

    @Override
    public String toString() {
        return "JumpTarget[minX=" + this.minX + ", maxX=" + this.maxX
                + ", minZ=" + this.minZ + ", maxZ=" + this.maxZ
                + ", surfaceY=" + this.surfaceY + "]";
    }
}
