package dev.kairos.core.scenario;

/**
 * An inclusive block region to set, from {@code from} to {@code to} (PLAN.md 5.2).
 * Coordinates are integer block coordinates; {@code id} is a block name such as
 * {@code "stone"} or {@code "packed_ice"} (resolved to a block by the loader).
 *
 * <p>Pure Java: no {@code net.minecraft.*} imports (AGENTS.md rule 1).
 */
public final class ScenarioBlock {

    public final int fromX;
    public final int fromY;
    public final int fromZ;
    public final int toX;
    public final int toY;
    public final int toZ;
    public final String id;

    ScenarioBlock(int[] from, int[] to, String id) {
        this.fromX = from[0];
        this.fromY = from[1];
        this.fromZ = from[2];
        this.toX = to[0];
        this.toY = to[1];
        this.toZ = to[2];
        this.id = id;
    }

    /** Number of blocks this region covers; used to sanity-check a scenario. */
    public long volume() {
        long dx = Math.abs((long) this.toX - this.fromX) + 1L;
        long dy = Math.abs((long) this.toY - this.fromY) + 1L;
        long dz = Math.abs((long) this.toZ - this.fromZ) + 1L;
        return dx * dy * dz;
    }
}
