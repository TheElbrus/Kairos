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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Replays every committed flat golden trace through {@link MovementSim} and demands
 * bit-exact equality (ADR 0007: max position error 0) for every recorded field.
 *
 * <p>Read-only reuse of {@link Json} (ADR 0010 keeps the trace format frozen; this test
 * only parses it). The trace set and the per-file line counts are pinned so a missing,
 * extra or truncated trace cannot pass vacuously: task 1.1 Part A pins the three 0.5.1
 * goldens, Part B extends the table to the 13 flat traces recorded for this task.
 * The comparator is field-by-field with {@code Double.compare == 0} (which also
 * distinguishes {@code 0.0} from {@code -0.0}) and fails on the FIRST divergence, naming
 * the tick, field, trace value and sim value.
 */
class MovementSimFlatGoldenTest {

    /** Golden name -> pinned line count (tick:-1 anchor + ticks). */
    private static final Map<String, Integer> PINNED = new HashMap<>();

    static {
        PINNED.put("walk_flat_01", 21);
        PINNED.put("sprint_flat_01", 26);
        PINNED.put("sprintjump_flat_01", 19);
        PINNED.put("walk_accel_flat_01", 46);
        PINNED.put("walk_stop_flat_01", 56);
        PINNED.put("sprint_accel_flat_01", 46);
        PINNED.put("sprint_stop_flat_01", 56);
        PINNED.put("jump_vertical_flat_01", 22);
        PINNED.put("walkjump_flat_01", 37);
        PINNED.put("sprintjump_run_flat_01", 37);
        PINNED.put("sprintjump_release_flat_01", 37);
        PINNED.put("sprintjump_airwap_flat_01", 31);
        PINNED.put("walk_stop_walk_flat_01", 51);
    }

    @Test
    void matchesEveryFlatGolden() throws IOException {
        File[] files = flatGoldenDir().listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            fail("cannot list flat golden directory");
        }
        Arrays.sort(files);
        assertEquals(PINNED.size(), files.length,
                "flat golden count changed; add or remove the trace and extend PINNED deliberately");
        CollisionWorld world = flatWorld();
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - ".jsonl".length());
            Integer expectedLines = PINNED.get(name);
            if (expectedLines == null) {
                fail("unpinned trace file " + file + " (extend PINNED when recording Part B)");
            }
            replay(file, name, expectedLines, world);
        }
    }

    private static void replay(File file, String name, int expectedLines, CollisionWorld world)
            throws IOException {
        assertLfOnly(file);
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(expectedLines, lines.size(), "pinned line count of " + file);

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

    /**
     * Repo root: the first ancestor of the test working dir with the repo marker
     * ({@link ScenarioLocator#ROOT_MARKER}, the root build file {@code settings.gradle}).
     */
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

    private static File flatGoldenDir() {
        return new File(repoRoot(), "data/traces/flat");
    }

    /**
     * The flat traces were recorded on one stone layer (top at y 65), the world task
     * 1.1's flat model hard-coded and task 1.2 replaces with a real sweep. The frozen
     * {@code *_flat_01} scenarios predate the per-region props selector, so instead of
     * letting the parser guess a state for a multi-state block they are stated here
     * explicitly: {@code variant=stone} is the state they were recorded with (the stone
     * block's default). One layer beyond every flat path (longest ends near z 15) keeps
     * the replay's collision query identical to the recorded ground.
     */
    private static CollisionWorld flatWorld() throws IOException {
        String json = "{\"name\": \"flat\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": [{\"from\": [-2, 64, -8], \"to\": [2, 64, 30],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}],"
                + " \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(
                BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json")),
                ScenarioParser.parse(json));
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
