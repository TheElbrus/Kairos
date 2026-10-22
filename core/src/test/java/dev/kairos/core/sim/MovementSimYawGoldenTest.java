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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 1.6 Part B: four live-recorded non-axis-yaw goldens replay bit-exact against
 * {@link MovementSim} on their own scenario collision world.
 *
 * <p>The pins keep a missing, extra or truncated trace from passing vacuously. The facts
 * test then reads the recorded numbers and asserts what the traces exist to prove: the
 * moveFlying direction (Entity.java:984-988) turns the velocity onto the heading, the
 * A-strafe axis is the heading's left (TraceInput.java:58-65), and the sprint jump boost
 * (EntityLivingBase.java:1227-1231) fires along that heading with the exact measured
 * vertical velocity.
 */
class MovementSimYawGoldenTest {

    /** Golden name -> pinned line count (tick:-1 anchor + ticks), recorded in Part B. */
    private static final Map<String, Integer> PINNED = new HashMap<>();

    static {
        PINNED.put("yaw_walk_33_3_01", 41);
        PINNED.put("yaw_walk_161_9_01", 41);
        PINNED.put("yaw_sprintjump_33_3_01", 37);
        PINNED.put("yaw_strafe_161_9_01", 41);
    }

    @Test
    void matchesEveryYawGolden() throws IOException {
        File[] files = yawGoldenDir().listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            files = new File[0];  // Part A: the directory appears with the first recording
        }
        Arrays.sort(files);
        assertEquals(PINNED.size(), files.length,
                "yaw golden count changed; add or remove the trace and extend PINNED deliberately");
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
     * The recorded facts: the first tick's direction, the walk/strafe axis relationship,
     * the sprint jump tick, and the landing tick.
     */
    @Test
    void yawFactsMatchTheRecordings() throws IOException {
        // Forward at 33.3 degrees: -sin on X, +cos on Z.
        assertTrue(velX("yaw_walk_33_3_01", 0) < 0.0);
        assertTrue(velZ("yaw_walk_33_3_01", 0) > 0.0);
        assertTrue(onGround("yaw_walk_33_3_01", 0));
        assertExact("walk 33.3 first-tick velX", -0.029376771248700306,
                velX("yaw_walk_33_3_01", 0));
        assertExact("walk 33.3 first-tick velZ", 0.04472260443280529,
                velZ("yaw_walk_33_3_01", 0));
        assertExact("walk 33.3 final posX", -4.097880840977982, posX("yaw_walk_33_3_01", 39));
        assertExact("walk 33.3 final posZ", 7.4997211177293694, posZ("yaw_walk_33_3_01", 39));
        assertTrue(onGround("yaw_walk_33_3_01", 39), "the whole walk stays on the floor");

        // Forward at 161.9 degrees: -sin and +cos are both negative.
        assertTrue(velX("yaw_walk_161_9_01", 0) < 0.0);
        assertTrue(velZ("yaw_walk_161_9_01", 0) < 0.0);
        assertExact("walk 161.9 first-tick velX", -0.016628528928268493,
                velX("yaw_walk_161_9_01", 0));
        assertExact("walk 161.9 first-tick velZ", -0.05085861080044962,
                velZ("yaw_walk_161_9_01", 0));
        assertExact("walk 161.9 final posX", -2.1026003309099686, posX("yaw_walk_161_9_01", 39));
        assertExact("walk 161.9 final posZ", -7.460093034679193, posZ("yaw_walk_161_9_01", 39));

        // A-strafe at 161.9 is the heading's left: the same acceleration magnitude rotated
        // so velX equals the forward walk's velZ and velZ is its negated velX. The player
        // moves -X/+Z.
        assertTrue(velX("yaw_strafe_161_9_01", 0) < 0.0);
        assertTrue(velZ("yaw_strafe_161_9_01", 0) > 0.0);
        assertExact("strafe 161.9 first-tick velX", -0.05085861080044962,
                velX("yaw_strafe_161_9_01", 0));
        assertExact("strafe 161.9 first-tick velZ", 0.016628528928268493,
                velZ("yaw_strafe_161_9_01", 0));
        assertExact("left is the forward heading rotated 90 degrees (X)",
                velZ("yaw_walk_161_9_01", 0), velX("yaw_strafe_161_9_01", 0));
        assertExact("left is the forward heading rotated 90 degrees (Z)",
                -velX("yaw_walk_161_9_01", 0), velZ("yaw_strafe_161_9_01", 0));
        assertExact("strafe 161.9 final posX", -7.460093034679193,
                posX("yaw_strafe_161_9_01", 39));
        assertExact("strafe 161.9 final posZ", 3.1026003309099686,
                posZ("yaw_strafe_161_9_01", 39));

        // Sprint jump at 33.3: the jump fires on tick 5 (after five sprint ticks), the
        // boost follows the heading (-X/+Z), and the player lands on tick 16.
        assertTrue(onGround("yaw_sprintjump_33_3_01", 4), "grounded before the jump");
        assertFalse(onGround("yaw_sprintjump_33_3_01", 5), "jump tick");
        assertTrue(sprinting("yaw_sprintjump_33_3_01", 5), "the boost requires sprinting");
        assertExact("jump tick velY", 0.33319999363422365,
                velY("yaw_sprintjump_33_3_01", 5));
        assertExact("jump tick posY", 65.41999998688698,
                posY("yaw_sprintjump_33_3_01", 5));
        assertTrue(velX("yaw_sprintjump_33_3_01", 5) < 0.0);
        assertTrue(velZ("yaw_sprintjump_33_3_01", 5) > 0.0);
        assertFalse(onGround("yaw_sprintjump_33_3_01", 15), "still airborne at tick 15");
        assertTrue(onGround("yaw_sprintjump_33_3_01", 16), "lands on tick 16");
        assertExact("landing posY", 65.0, posY("yaw_sprintjump_33_3_01", 16));
        assertTrue(sprinting("yaw_sprintjump_33_3_01", 16), "landing does not cancel sprint");
    }

    private static void assertExact(String what, double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            fail(what + ": pinned " + expected + " but recorded " + actual);
        }
    }

    private static double posX(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "pos", 0);
    }

    private static double posY(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "pos", 1);
    }

    private static double posZ(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "pos", 2);
    }

    private static double velX(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "vel", 0);
    }

    private static double velY(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "vel", 1);
    }

    private static double velZ(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "vel", 2);
    }

    private static boolean sprinting(String name, int tick) throws IOException {
        return boolField(recordedLine(name, tick), "sprinting");
    }

    private static boolean onGround(String name, int tick) throws IOException {
        return boolField(recordedLine(name, tick), "onGround");
    }

    /** Recorded line of {@code name} at {@code tick} (tick:-1 is line 0). */
    private static Map<String, Object> recordedLine(String name, int tick) throws IOException {
        List<String> lines = Files.readAllLines(
                new File(yawGoldenDir(), name + ".jsonl").toPath(), StandardCharsets.UTF_8);
        Map<String, Object> line = jsonObject(lines.get(tick + 1),
                name + " line for tick " + tick);
        assertEquals(tick, intField(line, "tick"), name + " line order");
        return line;
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

    private static CollisionWorld worldFor(String name) throws IOException {
        File scenarioFile = new File(repoRoot(), "data/scenarios/" + name + ".json");
        String json = new String(Files.readAllBytes(scenarioFile.toPath()), StandardCharsets.UTF_8);
        return CollisionWorld.of(
                BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json")),
                ScenarioParser.parse(json));
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

    private static File yawGoldenDir() {
        return new File(repoRoot(), "data/traces/yaw");
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
