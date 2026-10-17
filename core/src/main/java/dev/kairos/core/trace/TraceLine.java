package dev.kairos.core.trace;

/**
 * One recorded tick: the input applied during tick {@code tick} and the player state at
 * the END of that tick (PLAN.md 3.1, ADR 0001).
 *
 * <p>Pure Java: this package must never import {@code net.minecraft.*} (AGENTS.md rule 1).
 */
public final class TraceLine {

    /** Trace format version written as {@code "v"}. */
    public static final int FORMAT_VERSION = 1;

    public final String mc;
    public final String scenario;
    public final int tick;
    public final TraceInput in;

    public final double posX;
    public final double posY;
    public final double posZ;
    public final double velX;
    public final double velY;
    public final double velZ;

    public final boolean onGround;
    public final boolean sprinting;
    public final boolean using;
    public final boolean colH;
    public final boolean colV;

    /**
     * Ticks elapsed since the anchor, NOT the raw {@code ticksExisted}. The {@code tick:-1}
     * line is {@code 0}, then {@code 1}, {@code 2}, ... The loader subtracts the absolute
     * {@code ticksExisted} it saw at the anchor, so two runs of one scenario are byte
     * identical. The recorder's gap guard still compares the absolute values.
     */
    public final int te;

    public TraceLine(String mc, String scenario, int tick, TraceInput in,
                     double posX, double posY, double posZ,
                     double velX, double velY, double velZ,
                     boolean onGround, boolean sprinting, boolean using,
                     boolean colH, boolean colV, int te) {
        if (mc == null) {
            throw new IllegalArgumentException("mc must not be null");
        }
        if (scenario == null) {
            throw new IllegalArgumentException("scenario must not be null");
        }
        if (in == null) {
            throw new IllegalArgumentException("in must not be null");
        }
        this.mc = mc;
        this.scenario = scenario;
        this.tick = tick;
        this.in = in;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
        this.onGround = onGround;
        this.sprinting = sprinting;
        this.using = using;
        this.colH = colH;
        this.colV = colV;
        this.te = te;
    }
}
