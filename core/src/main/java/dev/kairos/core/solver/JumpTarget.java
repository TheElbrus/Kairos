package dev.kairos.core.solver;

import dev.kairos.core.sim.MovementSim;

/**
 * The top surface of a target landing platform for the solvers (PLAN.md 2.2, 2.4, 8.1).
 * This is a solver landing criterion, not a physics rule.
 *
 * <p>The target is a horizontal rectangle at one exact surface height. A state has reached
 * it when {@link MovementSim.State#onGround} is true, the feet are exactly on
 * {@link #surfaceY}, and the player's X/Z box overlaps the rectangle deeply enough on both
 * axes. The overlap depth on one axis is the length of the intersection between the player
 * box and the target rectangle on that axis.
 *
 * <p>The default {@link #of(double, double, double, double, double)} factory uses
 * {@code edgeMargin = 0}: any overlap strictly greater than zero counts, and touching edges
 * do not. A positive {@link #edgeMargin} requires the overlap depth on both X and Z to be
 * strictly greater than the margin. Exact equality with the margin does not count. There is
 * no vertical margin; {@code onGround} and the exact {@link #surfaceY} comparison still
 * apply.
 *
 * <p>The exact height comparison is deliberate: the simulator's ground clamp leaves a
 * settled player on a block top as a fixed double, and the solver must not accept a landing
 * on a different block that happens to share the same floor height.
 */
public final class JumpTarget {

    public final double minX;
    public final double maxX;
    public final double minZ;
    public final double maxZ;
    public final double surfaceY;
    public final double edgeMargin;

    private JumpTarget(double minX, double maxX, double minZ, double maxZ, double surfaceY,
            double edgeMargin) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.surfaceY = surfaceY;
        this.edgeMargin = edgeMargin;
    }

    /**
     * Builds a target rectangle with the default zero edge margin.
     *
     * @param minX     west edge (inclusive in the overlap test, but touching is not overlap)
     * @param maxX     east edge
     * @param minZ     north edge
     * @param maxZ     south edge
     * @param surfaceY exact Y of the platform top
     */
    public static JumpTarget of(double minX, double maxX, double minZ, double maxZ,
            double surfaceY) {
        return of(minX, maxX, minZ, maxZ, surfaceY, 0.0);
    }

    /**
     * Builds a target rectangle with a horizontal edge margin.
     *
     * <p>On each horizontal axis, the player box must overlap the target rectangle by more
     * than {@code edgeMargin} blocks. A fully contained player box has overlap equal to its
     * own width/depth on that axis, not its distance from the target edge. Overlap equal to
     * the margin is rejected.
     *
     * @param minX       west edge
     * @param maxX       east edge
     * @param minZ       north edge
     * @param maxZ       south edge
     * @param surfaceY   exact Y of the platform top
     * @param edgeMargin non-negative finite minimum overlap depth on X and Z
     * @throws IllegalArgumentException if {@code edgeMargin} is negative, NaN or infinite
     */
    public static JumpTarget of(double minX, double maxX, double minZ, double maxZ,
            double surfaceY, double edgeMargin) {
        requireFinite("minX", minX);
        requireFinite("maxX", maxX);
        requireFinite("minZ", minZ);
        requireFinite("maxZ", maxZ);
        requireFinite("surfaceY", surfaceY);
        requireFinite("edgeMargin", edgeMargin);
        if (edgeMargin < 0.0) {
            throw new IllegalArgumentException("edgeMargin must be >= 0, got " + edgeMargin);
        }
        if (minX >= maxX) {
            throw new IllegalArgumentException("minX must be less than maxX, got "
                    + minX + " >= " + maxX);
        }
        if (minZ >= maxZ) {
            throw new IllegalArgumentException("minZ must be less than maxZ, got "
                    + minZ + " >= " + maxZ);
        }
        return new JumpTarget(minX, maxX, minZ, maxZ, surfaceY, edgeMargin);
    }

    /**
     * True when the state is standing on this exact surface and the player box's X and Z
     * overlap depths are strictly greater than {@link #edgeMargin}. Touching edges or an
     * overlap exactly equal to a positive margin do not count.
     */
    public boolean isReachedBy(MovementSim.State state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (!state.onGround || Double.compare(state.y, this.surfaceY) != 0) {
            return false;
        }
        double overlapX = Math.min(state.maxX, this.maxX) - Math.max(state.minX, this.minX);
        double overlapZ = Math.min(state.maxZ, this.maxZ) - Math.max(state.minZ, this.minZ);
        return overlapX > this.edgeMargin && overlapZ > this.edgeMargin;
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
                + ", surfaceY=" + this.surfaceY + ", edgeMargin=" + this.edgeMargin + "]";
    }
}
