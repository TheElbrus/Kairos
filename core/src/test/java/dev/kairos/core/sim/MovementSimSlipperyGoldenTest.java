package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.Json;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 1.3 Part B: three live-recorded slipperiness goldens replay bit-exact against
 * {@link MovementSim} with the per-block {@code Block.slipperiness} read from the dump
 * (EntityLivingBase.java:1254-1264). Ice and packed ice are both 0.98f, so the two
 * per-type traces must agree dynamically and differ only in the scenario label; the
 * transition trace crosses stone -> ice -> stone during one sprint, so a constant or
 * cached factor cannot pass.
 *
 * <p>The pin table keeps a missing, extra or truncated trace from passing vacuously.
 */
class MovementSimSlipperyGoldenTest {

    /** Golden name -> pinned line count (tick:-1 anchor + ticks), recorded in Part B. */
    private static final Map<String, Integer> PINNED = new HashMap<>();

    static {
        PINNED.put("ice_slide_01", 71);
        PINNED.put("packed_ice_slide_01", 71);
        PINNED.put("ice_transition_01", 81);
    }

    @Test
    void matchesEverySlipperyGolden() throws IOException {
        File[] files = slipperyGoldenDir().listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            files = new File[0];  // Part A: the directory appears with the first recording
        }
        Arrays.sort(files);
        assertEquals(PINNED.size(), files.length,
                "slippery golden count changed; add or remove the trace and extend PINNED deliberately");
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - ".jsonl".length());
            Integer expectedLines = PINNED.get(name);
            if (expectedLines == null) {
                fail("unpinned trace file " + file + " (extend PINNED when recording Part B)");
            }
            replay(file, name, expectedLines);
        }
    }

    /**
     * The glide facts of the recording, independent of the replay: the post-release
     * decay on ice is 0.98f*0.91f per tick (0.89180005f), and the sprint advance on ice
     * (0.2757) is slightly BELOW stone's 0.2806 because f = 0.16277136f/(f4*f4*f4)
     * accelerates less, while the post-move velocity locks higher (~0.2459) and the
     * 0.005 momentum threshold is what finally freezes the slide.
     */
    @Test
    void glideFactsMatchTheRecordings() throws IOException {
        assertVelocityZ("ice_slide_01", 20, 0.19315096756454203);
        assertVelocityZ("ice_slide_01", 21, 0.17225204175264883);
        assertVelocityZ("ice_slide_01", 22, 0.1536143787529394);
        assertVelocityZ("ice_slide_01", 23, 0.13699331003307919);

        // Ticks 44-49: full sprint on the ice stretch of the transition scenario.
        for (int tick = 44; tick <= 49; tick++) {
            double vz = velocityZ("ice_transition_01", tick);
            assertTrue(vz >= 0.2369 && vz < 0.2388,
                    "tick " + tick + " should be ice-regime (~0.2369-0.2388), was " + vz);
        }
        // Tick 59: sprinting on the stone after the ice boundary - lower, stone regime.
        assertVelocityZ("ice_transition_01", 59, 0.15550438551379644);
    }

    private static void replay(File file, String name, int expectedLines) throws IOException {
        assertLfOnly(file);
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(expectedLines, lines.size(), "pinned line count of " + file);

        CollisionWorld world = worldFor(name);
        Map<String, Object> anchor = jsonObject(lines.get(0), name + " line 1");
        assertEquals(-1, intField(anchor, "tick"), name + " line 1 must be the tick:-1 anchor");
        MovementSim.State state = MovementSim.initialState(
                listField(anchor, "pos", 0), listField(anchor, "pos", 1), listField(anchor, "pos", 2),
                listField(anchor, "vel", 0), listField(anchor, "vel", 1), listField(anchor, "vel", 2),
                boolField(anchor, "onGround"), boolField(anchor, "sprinting"),
                boolField(anchor, "colH"), boolField(anchor, "colV"));

        for (int i = 1; i < lines.size(); i++) {
            Map<String, Object> line = jsonObject(lines.get(i), name + " line " + (i + 1));
            int tick = intField(line, "tick");
            assertEquals(i - 1, tick, name + " line " + (i + 1) + " must be tick " + (i - 1));
            MovementSim.step(state, input(line), world);

            checkDouble(tick, "posX", listField(line, "pos", 0), state.x);
            checkDouble(tick, "posY", listField(line, "pos", 1), state.y);
            checkDouble(tick, "posZ", listField(line, "pos", 2), state.z);
            checkDouble(tick, "velX", listField(line, "vel", 0), state.vx);
            checkDouble(tick, "velY", listField(line, "vel", 1), state.vy);
            checkDouble(tick, "velZ", listField(line, "vel", 2), state.vz);
            checkBoolean(tick, "onGround", boolField(line, "onGround"), state.onGround);
            checkBoolean(tick, "sprinting", boolField(line, "sprinting"), state.sprinting);
            checkBoolean(tick, "colH", boolField(line, "colH"), state.colH);
            checkBoolean(tick, "colV", boolField(line, "colV"), state.colV);
        }
    }

    /**
     * The scenario's own collision world: {@code data/scenarios/<name>.json} parsed with
     * the frozen parser, every region resolved against the frozen block dump - the same
     * world the recording ran on, so the per-block slipperiness lookup is exercised.
     */
    private static CollisionWorld worldFor(String name) throws IOException {
        File scenarioFile = new File(repoRoot(), "data/scenarios/" + name + ".json");
        String json = new String(Files.readAllBytes(scenarioFile.toPath()), StandardCharsets.UTF_8);
        return CollisionWorld.of(
                BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json")),
                ScenarioParser.parse(json));
    }

    private static void assertVelocityZ(String name, int tick, double expected)
            throws IOException {
        if (Double.compare(expected, velocityZ(name, tick)) != 0) {
            fail(name + " tick " + tick + ": recorded velZ is " + velocityZ(name, tick)
                    + ", pinned " + expected);
        }
    }

    /** Recorded velZ of {@code name} at {@code tick} (tick:-1 is line 0). */
    private static double velocityZ(String name, int tick) throws IOException {
        File file = new File(slipperyGoldenDir(), name + ".jsonl");
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        Map<String, Object> line = jsonObject(lines.get(tick + 1),
                name + " line for tick " + tick);
        assertEquals(tick, intField(line, "tick"), name + " line order");
        return listField(line, "vel", 2);
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return f;
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static File slipperyGoldenDir() {
        return new File(repoRoot(), "data/traces/slippery");
    }

    /**
     * Regression test for the autocrlf CRLF issue (task 0.5.1-hygiene): the goldens must be
     * LF-only on disk (ADR 0010), and {@code readAllLines} hides CRLF, so check raw bytes.
     */
    private static void assertLfOnly(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0x0D) {
                fail("golden traces must be LF-only, ADR 0010: " + file
                        + " has a 0x0D byte at offset " + i);
            }
        }
    }

    private static void checkDouble(int tick, String field, double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            fail("first divergence at tick " + tick + ", field " + field
                    + ": expected (trace) " + expected + " but sim produced " + actual);
        }
    }

    private static void checkBoolean(int tick, String field, boolean expected, boolean actual) {
        if (expected != actual) {
            fail("first divergence at tick " + tick + ", field " + field
                    + ": expected (trace) " + expected + " but sim produced " + actual);
        }
    }

    private static TraceInput input(Map<String, Object> line) {
        Map<String, Object> in = mapField(line, "in");
        return new TraceInput(
                intField(in, "w"), intField(in, "a"), intField(in, "s"), intField(in, "d"),
                intField(in, "jump"), intField(in, "sneak"), intField(in, "sprint"),
                floatField(in, "yaw"), floatField(in, "pitch"),
                floatField(in, "mf"), floatField(in, "ms"));
    }

    private static Map<String, Object> jsonObject(String line, String where) {
        Object parsed = Json.parse(line);
        if (!(parsed instanceof Map)) {
            fail(where + " must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    private static Map<String, Object> mapField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map)) {
            fail("field \"" + key + "\" must be an object, got " + value);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) value;
        return nested;
    }

    private static double listField(Map<String, Object> map, String key, int index) {
        Object value = map.get(key);
        if (!(value instanceof List) || ((List<?>) value).size() <= index) {
            fail("field \"" + key + "\" must be an array with index " + index + ", got " + value);
        }
        Object number = ((List<?>) value).get(index);
        if (!(number instanceof Double)) {
            fail("field \"" + key + "\"[" + index + "] must be a number, got " + number);
        }
        return (Double) number;
    }

    private static double doubleField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Double)) {
            fail("field \"" + key + "\" must be a number, got " + value);
        }
        return (Double) value;
    }

    private static int intField(Map<String, Object> map, String key) {
        return (int) doubleField(map, key);
    }

    private static float floatField(Map<String, Object> map, String key) {
        return (float) doubleField(map, key);
    }

    private static boolean boolField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean)) {
            fail("field \"" + key + "\" must be a boolean, got " + value);
        }
        return (Boolean) value;
    }
}
