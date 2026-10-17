package dev.kairos.core.scenario;

import java.util.Collections;
import java.util.List;

/**
 * A scenario (PLAN.md 5.2): blocks to build, an exact start state, and a scripted input
 * sequence. Pure Java: no {@code net.minecraft.*} imports (AGENTS.md rule 1).
 *
 * <p>The loader builds the blocks, places the player at {@code start}, runs
 * {@code settleTicks} unrecorded ticks, then feeds {@code inputs} and records.
 */
public final class Scenario {

    public final String name;
    public final double startX;
    public final double startY;
    public final double startZ;
    public final float yaw;
    public final float pitch;
    public final List<ScenarioBlock> blocks;
    public final int settleTicks;
    public final List<ScenarioInput> inputs;

    Scenario(String name, double startX, double startY, double startZ, float yaw, float pitch,
             List<ScenarioBlock> blocks, int settleTicks, List<ScenarioInput> inputs) {
        this.name = name;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.blocks = Collections.unmodifiableList(blocks);
        this.settleTicks = settleTicks;
        this.inputs = Collections.unmodifiableList(inputs);
    }

    /** Total number of recorded ticks (the sum of every segment's {@code ticks}). */
    public int totalInputTicks() {
        int total = 0;
        for (ScenarioInput in : this.inputs) {
            total += in.ticks;
        }
        return total;
    }

    /** The input segment covering recorded tick {@code tick}, or {@code null} past the end. */
    public ScenarioInput inputAt(int tick) {
        if (tick < 0) {
            return null;
        }
        int remaining = tick;
        for (ScenarioInput in : this.inputs) {
            if (remaining < in.ticks) {
                return in;
            }
            remaining -= in.ticks;
        }
        return null;
    }
}
