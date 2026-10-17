package dev.kairos.core.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the JSON of PLAN.md 5.2 into a {@link Scenario}.
 *
 * <p>Pure Java 8, no dependencies: it uses {@link Json}, which is in this package. Every
 * mistake is reported as an {@link IllegalArgumentException} naming the offending field.
 */
public final class ScenarioParser {

    /** Default settle ticks when a scenario omits {@code settleTicks}. */
    public static final int DEFAULT_SETTLE_TICKS = 10;

    private ScenarioParser() {
    }

    public static Scenario parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("scenario must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;

        String name = requireString(map, "name");
        if (name.length() > 64) {
            throw new IllegalArgumentException("name must be at most 64 characters");
        }

        Object startObj = map.get("start");
        if (!(startObj instanceof Map)) {
            throw new IllegalArgumentException("scenario needs a \"start\" object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> start = (Map<String, Object>) startObj;
        double[] pos = requireNumberTriple(start, "pos");
        float yaw = optionalFloat(start, "yaw", 0.0f);
        float pitch = optionalFloat(start, "pitch", 0.0f);

        List<ScenarioBlock> blocks = parseBlocks(map.get("blocks"));
        int settleTicks = optionalInt(map, "settleTicks", DEFAULT_SETTLE_TICKS);
        if (settleTicks < 0) {
            throw new IllegalArgumentException("settleTicks must be >= 0");
        }
        List<ScenarioInput> inputs = parseInputs(map.get("inputs"));
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("scenario needs at least one entry in \"inputs\"");
        }

        return new Scenario(name, pos[0], pos[1], pos[2], yaw, pitch, blocks, settleTicks, inputs);
    }

    private static List<ScenarioBlock> parseBlocks(Object value) {
        List<ScenarioBlock> blocks = new ArrayList<ScenarioBlock>();
        if (value == null) {
            return blocks;
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("blocks must be an array");
        }
        for (Object element : (List<?>) value) {
            if (!(element instanceof Map)) {
                throw new IllegalArgumentException("each block entry must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) element;
            int[] from = requireIntTriple(block, "from");
            int[] to = requireIntTriple(block, "to");
            String id = requireString(block, "id");
            blocks.add(new ScenarioBlock(from, to, id));
        }
        return blocks;
    }

    private static List<ScenarioInput> parseInputs(Object value) {
        List<ScenarioInput> inputs = new ArrayList<ScenarioInput>();
        if (value == null) {
            return inputs;
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("inputs must be an array");
        }
        for (Object element : (List<?>) value) {
            if (!(element instanceof Map)) {
                throw new IllegalArgumentException("each input entry must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> in = (Map<String, Object>) element;
            int ticks = optionalInt(in, "ticks", -1);
            if (ticks < 1) {
                throw new IllegalArgumentException("each input entry needs ticks >= 1");
            }
            inputs.add(new ScenarioInput(
                    ticks,
                    flag(in, "w"), flag(in, "a"), flag(in, "s"), flag(in, "d"),
                    flag(in, "jump"), flag(in, "sneak"), flag(in, "sprint")));
        }
        return inputs;
    }

    // --- field helpers --------------------------------------------------------------

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a non-empty string");
        }
        return (String) value;
    }

    private static double[] requireNumberTriple(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List) || ((List<?>) value).size() != 3) {
            throw new IllegalArgumentException("\"" + key + "\" must be an array of 3 numbers");
        }
        double[] out = new double[3];
        List<?> list = (List<?>) value;
        for (int i = 0; i < 3; i++) {
            Object n = list.get(i);
            if (!(n instanceof Double)) {
                throw new IllegalArgumentException("\"" + key + "\"[" + i + "] must be a number");
            }
            out[i] = (Double) n;
        }
        return out;
    }

    private static int[] requireIntTriple(Map<String, Object> map, String key) {
        double[] numbers = requireNumberTriple(map, key);
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            double d = numbers[i];
            if (d != Math.rint(d)) {
                throw new IllegalArgumentException("\"" + key + "\"[" + i + "] must be a whole number");
            }
            out[i] = (int) d;
        }
        return out;
    }

    private static float optionalFloat(Map<String, Object> map, String key, float fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException("\"" + key + "\" must be a number");
        }
        return ((Double) value).floatValue();
    }

    private static int optionalInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException("\"" + key + "\" must be a number");
        }
        double d = (Double) value;
        if (d != Math.rint(d)) {
            throw new IllegalArgumentException("\"" + key + "\" must be a whole number");
        }
        return (int) d;
    }

    private static int flag(Map<String, Object> map, String key) {
        int value = optionalInt(map, key, 0);
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("\"" + key + "\" must be 0 or 1, got " + value);
        }
        return value;
    }
}
