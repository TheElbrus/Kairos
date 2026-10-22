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
 * Task 1.4 Part B: nine live-recorded shape goldens replay bit-exact against
 * {@link MovementSim} on the scenario collision world, whose fence/wall/pane shapes are
 * rebuilt from the neighbours and whose ladders/vines expose the climb branch
 * (EntityLivingBase.java:1265-1282).
 *
 * <p>The pin table keeps a missing, extra or truncated trace from passing vacuously.
 */
class MovementSimShapesGoldenTest {

    /** Golden name -> pinned line count (tick:-1 anchor + ticks), recorded in Part B. */
    private static final Map<String, Integer> PINNED = new HashMap<>();

    static {
        PINNED.put("ladder_climb_01", 66);
        PINNED.put("vine_climb_01", 66);
        PINNED.put("trapdoor_01", 51);
        PINNED.put("fence_rail_01", 31);
        PINNED.put("wall_rail_01", 31);
        PINNED.put("pane_line_01", 21);
        PINNED.put("carpet_01", 41);
        PINNED.put("snow_layer_01", 41);
        PINNED.put("anvil_01", 41);
    }

    @Test
    void matchesEveryShapeGolden() throws IOException {
        File[] files = shapesGoldenDir().listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            files = new File[0];  // Part A: the directory appears with the first recording
        }
        Arrays.sort(files);
        assertEquals(PINNED.size(), files.length,
                "shape golden count changed; add or remove the trace and extend PINNED deliberately");
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
     * The recorded facts that prove each shape, independent of the replay: the three
     * rails are the connected shapes (a lone post would miss or stop late), the steps are
     * the dumped lip heights, the ladder/vine climb uses the 0.2 impulse and the -0.15
     * clamp, and the anvil is a 1.0-high platform with a real fall and landing.
     */
    @Test
    void shapeFactsMatchTheRecordings() throws IOException {
        // Fence: the east-west rail at z 5.375 stops the player at 5.075; the lone post
        // (x 2.375..2.625) never touches the player's box (x 1.7..2.3).
        assertPosZ("fence_rail_01", 29, 5.074999988079071);
        // Wall: the straight west-east run narrows to z 5.3125 -> stop 5.0125; a lone post
        // would stop at 4.95.
        assertPosZ("wall_rail_01", 29, 5.012499988079071);
        // Pane: only the south half plate exists (z 4.5..5.0) -> stop 4.2; the full plate
        // would stop at 4.1375.
        assertPosZ("pane_line_01", 19, 4.199999988079071);

        // The three steps are exactly the dumped boxes: 3/16 trapdoor, 1/16 carpet,
        // (layers-1)/8 snow (layers 2 and 4).
        assertPeakY("trapdoor_01", 65.1875);
        assertPeakY("carpet_01", 65.0625);
        assertPeakY("snow_layer_01", 65.375);

        // Ladder/vine: pushing into the block climbs (end-of-tick vy = (0.2-0.08)*0.98f),
        // and after release the -0.15 pre-move clamp slides the player back down.
        assertVelocityY("ladder_climb_01", 20, 0.11760000228881837);
        assertVelocityY("ladder_climb_01", 50, -0.22540000438690183);
        assertPeakY("ladder_climb_01", 67.85924805789199);
        assertPosZ("ladder_climb_01", 64, 5.574999988079071);
        assertPeakY("vine_climb_01", 67.74164805560316);
        assertPosZ("vine_climb_01", 64, 5.699999988079071);

        // Anvil: standing on the 1.0-high top at y=66.0, walking off at tick 15, landing
        // on the floor at y=65.0 on tick 19.
        assertPosY("anvil_01", 14, 66.0);
        assertPosY("anvil_01", 19, 65.0);
        assertVelocityY("anvil_01", 19, -0.0784000015258789);
    }

    private static void assertPosZ(String name, int tick, double expected) throws IOException {
        if (Double.compare(expected, posZ(name, tick)) != 0) {
            fail(name + " tick " + tick + ": recorded posZ is " + posZ(name, tick)
                    + ", pinned " + expected);
        }
    }

    private static void assertPosY(String name, int tick, double expected) throws IOException {
        if (Double.compare(expected, posY(name, tick)) != 0) {
            fail(name + " tick " + tick + ": recorded posY is " + posY(name, tick)
                    + ", pinned " + expected);
        }
    }

    private static void assertPeakY(String name, double expected) throws IOException {
        double peak = -Double.MAX_VALUE;
        for (int tick = 0; tick < tickCount(name); tick++) {
            peak = Math.max(peak, posY(name, tick));
        }
        if (Double.compare(expected, peak) != 0) {
            fail(name + ": recorded peak posY is " + peak + ", pinned " + expected);
        }
    }

    private static void assertVelocityY(String name, int tick, double expected)
            throws IOException {
        if (Double.compare(expected, velocityY(name, tick)) != 0) {
            fail(name + " tick " + tick + ": recorded velY is " + velocityY(name, tick)
                    + ", pinned " + expected);
        }
    }

    private static double posY(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "pos", 1);
    }

    private static double posZ(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "pos", 2);
    }

    private static double velocityY(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "vel", 1);
    }

    private static int tickCount(String name) throws IOException {
        File file = new File(shapesGoldenDir(), name + ".jsonl");
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).size() - 1;
    }

    /** Recorded line of {@code name} at {@code tick} (tick:-1 is line 0). */
    private static Map<String, Object> recordedLine(String name, int tick) throws IOException {
        File file = new File(shapesGoldenDir(), name + ".jsonl");
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
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

    private static File shapesGoldenDir() {
        return new File(repoRoot(), "data/traces/shapes");
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
