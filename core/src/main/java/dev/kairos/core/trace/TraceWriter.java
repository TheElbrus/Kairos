package dev.kairos.core.trace;

/**
 * Turns a {@link TraceLine} into one JSON line. Hand-written on purpose: the format is
 * tiny and adding a JSON library would be a new dependency (AGENTS.md, ADR 0002).
 *
 * <p>Numbers are written with {@code Double.toString} / {@code Float.toString}, i.e. full
 * round-trip precision and never rounded. A float is never widened to a double first --
 * {@code (double) 33.3f} is {@code 33.29999923706055}, which is not the float the game
 * used (ADR 0010).
 *
 * <p>{@code NaN} and {@code Infinity} are not valid JSON, so they are rejected rather
 * than emitted.
 */
public final class TraceWriter {

    private TraceWriter() {
    }

    /**
     * @return the JSON line WITHOUT a trailing newline. Callers write their own
     *         {@code '\n'} so line endings are identical on every OS.
     * @throws IllegalArgumentException if any number is {@code NaN} or infinite, or a
     *                                  required field is null.
     */
    public static String toJsonLine(TraceLine l) {
        checkFinite(l.posX, "pos[0]");
        checkFinite(l.posY, "pos[1]");
        checkFinite(l.posZ, "pos[2]");
        checkFinite(l.velX, "vel[0]");
        checkFinite(l.velY, "vel[1]");
        checkFinite(l.velZ, "vel[2]");
        checkFinite(l.in.yaw, "in.yaw");
        checkFinite(l.in.pitch, "in.pitch");
        checkFinite(l.in.mf, "in.mf");
        checkFinite(l.in.ms, "in.ms");

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"v\":").append(TraceLine.FORMAT_VERSION);
        sb.append(",\"mc\":").append(jsonString(l.mc));
        sb.append(",\"scenario\":").append(jsonString(l.scenario));
        sb.append(",\"tick\":").append(l.tick);
        sb.append(",\"in\":{");
        sb.append("\"w\":").append(l.in.w);
        sb.append(",\"a\":").append(l.in.a);
        sb.append(",\"s\":").append(l.in.s);
        sb.append(",\"d\":").append(l.in.d);
        sb.append(",\"jump\":").append(l.in.jump);
        sb.append(",\"sneak\":").append(l.in.sneak);
        sb.append(",\"sprint\":").append(l.in.sprint);
        sb.append(",\"yaw\":").append(Float.toString(l.in.yaw));
        sb.append(",\"pitch\":").append(Float.toString(l.in.pitch));
        sb.append(",\"mf\":").append(Float.toString(l.in.mf));
        sb.append(",\"ms\":").append(Float.toString(l.in.ms));
        sb.append("},\"pos\":[")
                .append(Double.toString(l.posX)).append(',')
                .append(Double.toString(l.posY)).append(',')
                .append(Double.toString(l.posZ)).append(']');
        sb.append(",\"vel\":[")
                .append(Double.toString(l.velX)).append(',')
                .append(Double.toString(l.velY)).append(',')
                .append(Double.toString(l.velZ)).append(']');
        sb.append(",\"onGround\":").append(l.onGround);
        sb.append(",\"sprinting\":").append(l.sprinting);
        sb.append(",\"using\":").append(l.using);
        sb.append(",\"colH\":").append(l.colH);
        sb.append(",\"colV\":").append(l.colV);
        sb.append(",\"te\":").append(l.te);
        sb.append('}');
        return sb.toString();
    }

    private static void checkFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " is not valid JSON: " + value);
        }
    }

    private static void checkFinite(float value, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " is not valid JSON: " + value);
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
