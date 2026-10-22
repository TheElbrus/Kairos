package dev.kairos.core.solver;

import dev.kairos.core.trace.TraceInput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A replayable baseline plan (PLAN.md 2.2): the exact input for every simulated tick,
 * ending on the landing tick.
 *
 * <p>{@link #landingTick} is a zero-based tick index into {@link #inputs}; it is always
 * the last input. Replaying every input through
 * {@link dev.kairos.core.sim.MovementSim#step} from a copy of the solver's start state
 * must produce the accepted landing. The plan stores no predicted result, so callers
 * cannot mistake an internal prediction for the replay check.
 */
public final class JumpPlan {

    /** Exact inputs, one per tick, up to and including the landing tick. Unmodifiable. */
    public final List<TraceInput> inputs;

    /** Zero-based landing tick; equals {@code inputs.size() - 1}. */
    public final int landingTick;

    JumpPlan(List<TraceInput> inputs, int landingTick) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("a plan needs at least one input");
        }
        if (landingTick != inputs.size() - 1) {
            throw new IllegalArgumentException("landingTick must be the last input, got "
                    + landingTick + " for " + inputs.size() + " inputs");
        }
        this.inputs = Collections.unmodifiableList(new ArrayList<TraceInput>(inputs));
        this.landingTick = landingTick;
    }

    /** Number of ticks in the plan; {@code landingTick + 1}. */
    public int tickCount() {
        return this.inputs.size();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JumpPlan)) {
            return false;
        }
        JumpPlan that = (JumpPlan) other;
        if (this.landingTick != that.landingTick
                || this.inputs.size() != that.inputs.size()) {
            return false;
        }
        for (int i = 0; i < this.inputs.size(); i++) {
            if (!sameInput(this.inputs.get(i), that.inputs.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = this.landingTick;
        for (TraceInput input : this.inputs) {
            result = 31 * result + inputHash(input);
        }
        return result;
    }

    @Override
    public String toString() {
        return "JumpPlan[ticks=" + this.inputs.size() + ", landingTick=" + this.landingTick
                + "]";
    }

    private static boolean sameInput(TraceInput a, TraceInput b) {
        return a.w == b.w
                && a.a == b.a
                && a.s == b.s
                && a.d == b.d
                && a.jump == b.jump
                && a.sneak == b.sneak
                && a.sprint == b.sprint
                && Float.compare(a.yaw, b.yaw) == 0
                && Float.compare(a.pitch, b.pitch) == 0
                && Float.compare(a.mf, b.mf) == 0
                && Float.compare(a.ms, b.ms) == 0;
    }

    private static int inputHash(TraceInput input) {
        int result = input.w;
        result = 31 * result + input.a;
        result = 31 * result + input.s;
        result = 31 * result + input.d;
        result = 31 * result + input.jump;
        result = 31 * result + input.sneak;
        result = 31 * result + input.sprint;
        result = 31 * result + Float.floatToIntBits(input.yaw);
        result = 31 * result + Float.floatToIntBits(input.pitch);
        result = 31 * result + Float.floatToIntBits(input.mf);
        result = 31 * result + Float.floatToIntBits(input.ms);
        return result;
    }
}
