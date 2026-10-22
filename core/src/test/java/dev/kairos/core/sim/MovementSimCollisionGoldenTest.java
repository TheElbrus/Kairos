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
 * Replays every committed collision golden trace through {@link MovementSim} and demands
 * bit-exact equality (ADR 0007) for every recorded field - the task 1.2 counterpart of
 * {@code MovementSimFlatGoldenTest}, with a real collision world built from each
 * scenario's own regions through {@code data/1.8.9/blocks.json}.
 *
 * <p>Read-only reuse of {@link Json} (ADR 0010). The pin table keeps a missing, extra or
 * truncated trace from passing vacuously: Part A (the sim rewrite) ships this test with
 * an empty table and an absent directory, then Part B records the six collision
 * scenarios and pins their line counts.
 */
class MovementSimCollisionGoldenTest {

    /** Golden name -> pinned line count (tick:-1 anchor + ticks), recorded in Part B. */
    private static final Map<String, Integer> PINNED = new HashMap<>();

    static {
        PINNED.put("wall_hit_01", 41);
        PINNED.put("head_bump_01", 47);
        PINNED.put("slab_step_01", 51);
        PINNED.put("sneak_edge_01", 71);
        PINNED.put("fall_off_01", 71);
        PINNED.put("stairs_step_01", 61);
    }

    @Test
    void matchesEveryCollisionGolden() throws IOException {
        File[] files = collisionGoldenDir().listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            files = new File[0];  // Part A: the directory appears with the first recording
        }
        Arrays.sort(files);
        assertEquals(PINNED.size(), files.length,
                "collision golden count changed; add or remove the trace and extend PINNED deliberately");
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - ".jsonl".length());
            Integer expectedLines = PINNED.get(name);
            if (expectedLines == null) {
                fail("unpinned trace file " + file + " (extend PINNED when recording Part B)");
            }
            replay(file, name, expectedLines);
        }
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
     * L0 geometry check with no recorded trace: walking into a bottom slab must ride the
     * game's step-up onto its exactly-0.5-high top (dumped boxes, no classifier).
     */
    @Test
    void stepsUpOntoSlabFromDumpedBoxes() throws IOException {
        String json = "{\"name\": \"step_up_l0\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + " {\"from\": [-1, 64, -8], \"to\": [1, 64, 30], \"id\": \"stone\","
                + "  \"props\": {\"variant\": \"stone\"}},"
                + " {\"from\": [-1, 65, 4], \"to\": [1, 65, 7], \"id\": \"stone_slab\","
                + "  \"props\": {\"half\": \"bottom\", \"variant\": \"stone\"}}],"
                + " \"inputs\": [{\"ticks\": 1}]}";
        CollisionWorld world = CollisionWorld.of(
                BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json")),
                ScenarioParser.parse(json));
        MovementSim.State s = MovementSim.initialState(
                0.5, 65.0, 0.5, 0.0, -0.0784000015258789, 0.0, true, false, false, true);

        for (int i = 0; i < 30; i++) {
            MovementSim.step(s, new TraceInput(1, 0, 0, 0, 0, 0, 0, 0.0f, 0.0f, 1.0f, 0.0f), world);
        }

        assertEquals(65.5, s.y, 0.0, "the step-up must settle exactly on the slab's 0.5-high top");
        assertTrue(s.onGround, "standing on the slab top must count as onGround");
        assertTrue(s.z > 4.7, "walking must continue across the slab, not stop at its face");
    }

    /**
     * The scenario's own collision world: {@code data/scenarios/<name>.json} parsed with
     * the frozen parser, every region resolved against the frozen block dump. Any state
     * selector the recording needed is therefore replayed exactly.
     */
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

    private static File collisionGoldenDir() {
        return new File(repoRoot(), "data/traces/collision");
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
