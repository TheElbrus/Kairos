package dev.kairos.core.sim;

/**
 * The game's sine/cosine lookup table (MathHelper.java:17, 29-38, 493-499), mirrored
 * exactly: 65536 entries built once at class load from {@link Math#sin} (not
 * {@code StrictMath}), indexed by the truncated scaled angle masked to 16 bits.
 *
 * <p>There is no normalization or wrapping of the input: like the game, a yaw in
 * degrees is converted by the caller with its own exact expression shape and then
 * multiplied by {@code 10430.378f} ({@code 65536 / 2PI}). The table is immutable after
 * class load, so {@link #sin}/{@link #cos} allocate nothing and the tick path stays
 * allocation-free (AGENTS.md rule 7).
 */
public final class MathHelper {

    private static final float[] SIN_TABLE = new float[65536];

    static {
        for (int i = 0; i < 65536; ++i) {
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
        }
    }

    private MathHelper() {
    }

    /** MathHelper.sin (MathHelper.java:29-31). */
    public static float sin(float f) {
        return SIN_TABLE[(int) (f * 10430.378f) & 0xFFFF];
    }

    /** MathHelper.cos (MathHelper.java:36-38): the sine table offset by a quarter turn. */
    public static float cos(float f) {
        return SIN_TABLE[(int) (f * 10430.378f + 16384.0f) & 0xFFFF];
    }
}
