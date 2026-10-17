package dev.kairos.core.trace;

/**
 * The input applied during one tick, in plain values.
 *
 * <p>Pure Java: this package must never import {@code net.minecraft.*} (AGENTS.md rule 1).
 *
 * <p>Two representations of the same movement axes are kept on purpose (ADR 0010):
 * <ul>
 *   <li>{@code w/a/s/d} -- the SIGN of the game's movement axes, i.e. which keys the
 *       planner should press.</li>
 *   <li>{@code mf/ms} -- the axes as floats, exactly as the physics consumed them. These
 *       already include the game's {@code 0.3} sneak and {@code 0.2} item-use scaling,
 *       which the raw keys cannot express.</li>
 * </ul>
 */
public final class TraceInput {

    /** All-zero input, used for the {@code tick:-1} start-state line. */
    private static final TraceInput ZERO =
            new TraceInput(0, 0, 0, 0, 0, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f);

    public final int w;
    public final int a;
    public final int s;
    public final int d;
    public final int jump;
    public final int sneak;
    public final int sprint;
    public final float yaw;
    public final float pitch;
    public final float mf;
    public final float ms;

    public TraceInput(int w, int a, int s, int d, int jump, int sneak, int sprint,
                      float yaw, float pitch, float mf, float ms) {
        this.w = flag(w, "w");
        this.a = flag(a, "a");
        this.s = flag(s, "s");
        this.d = flag(d, "d");
        this.jump = flag(jump, "jump");
        this.sneak = flag(sneak, "sneak");
        this.sprint = flag(sprint, "sprint");
        this.yaw = yaw;
        this.pitch = pitch;
        this.mf = mf;
        this.ms = ms;
    }

    /** @return the all-zero input for the start-state line. */
    public static TraceInput zero() {
        return ZERO;
    }

    /**
     * Derives the key signs from the movement axes.
     *
     * <p>Left is POSITIVE strafe: {@code MovementInputFromOptions.updatePlayerMoveState()}
     * does {@code keyBindLeft.isKeyDown() -> moveStrafe += 1.0f} (line 31) and
     * {@code keyBindRight -> moveStrafe -= 1.0f} (line 34), and {@code forward -> +1} /
     * {@code back -> -1} for {@code moveForward} (lines 25, 28). Holding both opposite
     * keys cancels out to {@code 0.0f}, which yields {@code 0} for both signs.
     *
     * @param mf {@code movementInput.moveForward} read at END of tick
     * @param ms {@code movementInput.moveStrafe} read at END of tick
     */
    public static TraceInput fromAxes(int jump, int sneak, int sprint,
                                      float yaw, float pitch, float mf, float ms) {
        return new TraceInput(
                mf > 0.0f ? 1 : 0,
                ms > 0.0f ? 1 : 0,
                mf < 0.0f ? 1 : 0,
                ms < 0.0f ? 1 : 0,
                jump, sneak, sprint, yaw, pitch, mf, ms);
    }

    private static int flag(int value, String name) {
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException(
                    "in." + name + " must be 0 or 1, got " + value);
        }
        return value;
    }
}
