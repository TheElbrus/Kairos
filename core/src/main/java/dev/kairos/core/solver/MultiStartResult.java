package dev.kairos.core.solver;

/**
 * The outcome of trying several start states (PLAN.md 2.5).
 *
 * <p>A solved result identifies the exact {@link StartState} whose plan was accepted and
 * carries the replay-checked {@link SolveResult}. A no-plan result carries the solver's
 * existing budget reason and no start; it never claims that every start is impossible.
 */
public final class MultiStartResult {

    /** The accepted start when solved; null for a no-plan result. */
    public final StartState start;

    /** The underlying single-start solver result; never null. */
    public final SolveResult solveResult;

    private MultiStartResult(StartState start, SolveResult solveResult) {
        if (solveResult == null) {
            throw new NullPointerException("solveResult");
        }
        this.start = start;
        this.solveResult = solveResult;
    }

    static MultiStartResult solved(StartState start, JumpPlan plan) {
        if (start == null) {
            throw new NullPointerException("start");
        }
        return new MultiStartResult(start, SolveResult.solved(plan));
    }

    static MultiStartResult noPlan(SolveResult.NoPlanReason reason) {
        return new MultiStartResult(null, SolveResult.noPlan(reason));
    }

    public boolean isSolved() {
        return this.solveResult.isSolved();
    }

    public boolean isNoPlan() {
        return this.solveResult.isNoPlan();
    }

    /** The replay-checked plan when solved; otherwise null. */
    public JumpPlan plan() {
        return this.solveResult.plan;
    }

    /** The no-plan reason when no start solved; otherwise null. */
    public SolveResult.NoPlanReason reason() {
        return this.solveResult.reason;
    }

    @Override
    public String toString() {
        return this.isSolved()
                ? "MultiStartResult[solved, start=" + this.start + ", "
                        + this.solveResult + "]"
                : "MultiStartResult[no plan, " + this.solveResult.reason + "]";
    }
}
