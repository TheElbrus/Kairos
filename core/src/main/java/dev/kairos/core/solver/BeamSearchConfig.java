package dev.kairos.core.solver;

/**
 * Deterministic search parameters for {@link BeamSolver} (PLAN.md 2.3).
 *
 * <p>The yaw grid is the coarse set {@code {0, -yawDeltaDegrees, +yawDeltaDegrees}} around
 * the current tick's yaw. It is intentionally not a continuous yaw optimiser; that is
 * task 2.6. The beam width and tick budget bound the search, and {@code deduplicate}
 * controls exact-state pruning.
 */
public final class BeamSearchConfig {

    public final float startYaw;
    public final float yawDeltaDegrees;
    public final int beamWidth;
    public final int tickBudget;
    public final boolean deduplicate;

    private BeamSearchConfig(float startYaw, float yawDeltaDegrees, int beamWidth,
            int tickBudget, boolean deduplicate) {
        this.startYaw = startYaw;
        this.yawDeltaDegrees = yawDeltaDegrees;
        this.beamWidth = beamWidth;
        this.tickBudget = tickBudget;
        this.deduplicate = deduplicate;
    }

    /** A config with exact-state deduplication enabled. */
    public static BeamSearchConfig of(float startYaw, float yawDeltaDegrees,
            int beamWidth, int tickBudget) {
        return of(startYaw, yawDeltaDegrees, beamWidth, tickBudget, true);
    }

    public static BeamSearchConfig of(float startYaw, float yawDeltaDegrees,
            int beamWidth, int tickBudget, boolean deduplicate) {
        if (Float.isNaN(startYaw) || Float.isInfinite(startYaw)) {
            throw new IllegalArgumentException("startYaw must be finite, got " + startYaw);
        }
        if (Float.isNaN(yawDeltaDegrees) || Float.isInfinite(yawDeltaDegrees)
                || yawDeltaDegrees < 0.0f) {
            throw new IllegalArgumentException(
                    "yawDeltaDegrees must be finite and >= 0, got " + yawDeltaDegrees);
        }
        if (beamWidth < 1) {
            throw new IllegalArgumentException("beamWidth must be >= 1, got " + beamWidth);
        }
        if (tickBudget < 1) {
            throw new IllegalArgumentException("tickBudget must be >= 1, got " + tickBudget);
        }
        return new BeamSearchConfig(startYaw, yawDeltaDegrees, beamWidth, tickBudget,
                deduplicate);
    }

    @Override
    public String toString() {
        return "BeamSearchConfig[startYaw=" + this.startYaw
                + ", yawDeltaDegrees=" + this.yawDeltaDegrees
                + ", beamWidth=" + this.beamWidth
                + ", tickBudget=" + this.tickBudget
                + ", deduplicate=" + this.deduplicate + "]";
    }
}
