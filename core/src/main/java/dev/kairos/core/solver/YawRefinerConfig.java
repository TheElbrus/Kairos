package dev.kairos.core.solver;

/**
 * Deterministic search bounds for {@link YawRefiner} (PLAN.md 2.6, section 8.2).
 *
 * <p>The refiner first sweeps a shared yaw offset and then performs coordinate passes over
 * the per-tick yaw values. The coarse pass uses {@link #coarseRangeDegrees} on each side of
 * the current value with {@link #coarseStepDegrees}; later passes use the same radius with
 * {@link #fineStepDegrees}. {@link #maxPasses} bounds the coordinate passes and
 * {@link #maxCandidates} bounds the number of complete plan replays. The configuration is
 * deliberately finite and deterministic: there is no RNG and no time-based stop.
 */
public final class YawRefinerConfig {

    public final float coarseRangeDegrees;
    public final float coarseStepDegrees;
    public final float fineStepDegrees;
    public final int maxPasses;
    public final int maxCandidates;

    private YawRefinerConfig(float coarseRangeDegrees, float coarseStepDegrees,
            float fineStepDegrees, int maxPasses, int maxCandidates) {
        this.coarseRangeDegrees = coarseRangeDegrees;
        this.coarseStepDegrees = coarseStepDegrees;
        this.fineStepDegrees = fineStepDegrees;
        this.maxPasses = maxPasses;
        this.maxCandidates = maxCandidates;
    }

    public static YawRefinerConfig of(float coarseRangeDegrees, float coarseStepDegrees,
            float fineStepDegrees, int maxPasses, int maxCandidates) {
        if (Float.isNaN(coarseRangeDegrees) || Float.isInfinite(coarseRangeDegrees)
                || coarseRangeDegrees < 0.0f) {
            throw new IllegalArgumentException(
                    "coarseRangeDegrees must be finite and >= 0, got "
                            + coarseRangeDegrees);
        }
        if (Float.isNaN(coarseStepDegrees) || Float.isInfinite(coarseStepDegrees)
                || coarseStepDegrees <= 0.0f) {
            throw new IllegalArgumentException(
                    "coarseStepDegrees must be finite and > 0, got " + coarseStepDegrees);
        }
        if (Float.isNaN(fineStepDegrees) || Float.isInfinite(fineStepDegrees)
                || fineStepDegrees <= 0.0f) {
            throw new IllegalArgumentException(
                    "fineStepDegrees must be finite and > 0, got " + fineStepDegrees);
        }
        if (maxPasses < 1) {
            throw new IllegalArgumentException("maxPasses must be >= 1, got " + maxPasses);
        }
        if (maxCandidates < 1) {
            throw new IllegalArgumentException(
                    "maxCandidates must be >= 1, got " + maxCandidates);
        }
        return new YawRefinerConfig(coarseRangeDegrees, coarseStepDegrees, fineStepDegrees,
                maxPasses, maxCandidates);
    }

    @Override
    public String toString() {
        return "YawRefinerConfig[coarseRangeDegrees=" + this.coarseRangeDegrees
                + ", coarseStepDegrees=" + this.coarseStepDegrees
                + ", fineStepDegrees=" + this.fineStepDegrees
                + ", maxPasses=" + this.maxPasses
                + ", maxCandidates=" + this.maxCandidates + "]";
    }
}
