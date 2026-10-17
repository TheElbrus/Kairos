package dev.kairos.core.scenario;

/**
 * One segment of scripted input: the same key state held for {@code ticks} ticks
 * (PLAN.md 5.2). Values are the raw key states the harness feeds to the fake
 * {@code MovementInput}; {@code w/a/s/d} are 0/1.
 *
 * <p>Pure Java: no {@code net.minecraft.*} imports (AGENTS.md rule 1).
 */
public final class ScenarioInput {

    public final int ticks;
    public final int w;
    public final int a;
    public final int s;
    public final int d;
    public final int jump;
    public final int sneak;
    public final int sprint;

    ScenarioInput(int ticks, int w, int a, int s, int d, int jump, int sneak, int sprint) {
        this.ticks = ticks;
        this.w = w;
        this.a = a;
        this.s = s;
        this.d = d;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
    }

    /** {@code moveForward} this segment produces, before the sneak/item scaling. */
    public float moveForward() {
        return (w != 0 ? 1.0f : 0.0f) - (s != 0 ? 1.0f : 0.0f);
    }

    /** {@code moveStrafe} this segment produces; left is POSITIVE (ADR 0010). */
    public float moveStrafe() {
        return (a != 0 ? 1.0f : 0.0f) - (d != 0 ? 1.0f : 0.0f);
    }
}
