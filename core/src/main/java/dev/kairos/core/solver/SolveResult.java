package dev.kairos.core.solver;

/**
 * The two honest outcomes of the baseline solver (PLAN.md 2.2).
 *
 * <p>A solved result contains the replay-checked plan. A no-plan result contains only a
 * reason; it never claims that the jump is impossible. The baseline search is deliberately
 * incomplete, and a tick budget can cut off schedules that might land later.
 */
public final class SolveResult {

    public enum Status {
        SOLVED,
        NO_PLAN
    }

    public enum NoPlanReason {
        /**
         * The deterministic schedule family was exhausted or reached the caller's tick
         * budget without a landing. This is not an impossibility proof.
         */
        SEARCH_BUDGET_EXHAUSTED
    }

    public final Status status;

    /** The replay-checked plan when {@link #status} is {@link Status#SOLVED}; else null. */
    public final JumpPlan plan;

    /** Why no plan was returned when {@link #status} is {@link Status#NO_PLAN}; else null. */
    public final NoPlanReason reason;

    private SolveResult(Status status, JumpPlan plan, NoPlanReason reason) {
        this.status = status;
        this.plan = plan;
        this.reason = reason;
    }

    public static SolveResult solved(JumpPlan plan) {
        if (plan == null) {
            throw new NullPointerException("plan");
        }
        return new SolveResult(Status.SOLVED, plan, null);
    }

    public static SolveResult noPlan(NoPlanReason reason) {
        if (reason == null) {
            throw new NullPointerException("reason");
        }
        return new SolveResult(Status.NO_PLAN, null, reason);
    }

    public boolean isSolved() {
        return this.status == Status.SOLVED;
    }

    public boolean isNoPlan() {
        return this.status == Status.NO_PLAN;
    }

    @Override
    public String toString() {
        return this.status == Status.SOLVED
                ? "SolveResult[solved, " + this.plan + "]"
                : "SolveResult[no plan, " + this.reason + "]";
    }
}
