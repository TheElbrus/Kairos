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
 * Task 1.5 Part B: four live-recorded sprint/jump-cooldown goldens replay bit-exact
 * against {@link MovementSim} on their own scenario collision world.
 *
 * <p>The pins keep a missing, extra or truncated trace from passing vacuously. The facts
 * test then reads the recorded numbers and asserts the transitions the traces exist to
 * prove: the sprint stop reads the PREVIOUS tick's horizontal collision
 * (EntityPlayerSP.java:616-618; notes 1.4 step 19), the held sprint key restarts it
 * (:613-614), the double-tap window starts it without the key (:606-611), and the
 * 10-tick {@code jumpTicks} cooldown blocks the held jump (EntityLivingBase.java:
 * 1474-1476, 1513-1525).
 */
class MovementSimStateGoldenTest {

    /** Golden name -> pinned line count (tick:-1 anchor + ticks), recorded in Part B. */
    private static final Map<String, Integer> PINNED = new HashMap<>();

    static {
        PINNED.put("sprint_wall_stop_01", 41);
        PINNED.put("sprint_release_w_01", 54);
        PINNED.put("sprint_doubletap_01", 35);
        PINNED.put("jump_repeat_ceiling_01", 41);
    }

    @Test
    void matchesEveryStateGolden() throws IOException {
        File[] files = stateGoldenDir().listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            files = new File[0];  // Part A: the directory appears with the first recording
        }
        Arrays.sort(files);
        assertEquals(PINNED.size(), files.length,
                "state golden count changed; add or remove the trace and extend PINNED deliberately");
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
     * The recorded facts, read from the traces: the collision tick and the tick after it,
     * the release/re-press ticks, the double-tap press, and the jump cooldown pattern.
     */
    @Test
    void stateFactsMatchTheRecordings() throws IOException {
        // Wall: tick 16 is the collision tick (colH=true from this tick's moveEntity) and
        // the sprint is still on; tick 17 reads that PREVIOUS colH and stops the sprint
        // even though W and the sprint key are still held. The player never moves again.
        assertTrue(colH("sprint_wall_stop_01", 16), "collision tick");
        assertTrue(sprinting("sprint_wall_stop_01", 16), "sprint survives the collision tick");
        assertFalse(colH("sprint_wall_stop_01", 15), "colH is not set before the collision");
        assertTrue(colH("sprint_wall_stop_01", 17));
        assertFalse(sprinting("sprint_wall_stop_01", 17), "the next tick reads prev colH");
        assertFalse(sprinting("sprint_wall_stop_01", 39), "the held keys cannot restart it");
        assertExact("wall stop posZ", 4.699999988079071, posZ("sprint_wall_stop_01", 39));

        // Release: W is released on tick 15; the sprint stops on that same tick and the
        // velocity falls (no 1.3 boost). Re-pressing on tick 23 restarts it through the
        // still-held sprint key (:613-614), and the velocity grows again.
        assertTrue(sprinting("sprint_release_w_01", 14));
        assertEquals(0.15319925007109483, velZ("sprint_release_w_01", 14), 0.0);
        assertFalse(sprinting("sprint_release_w_01", 15), "release stops the sprint same tick");
        assertEquals(0.08364680025461342, velZ("sprint_release_w_01", 15), 0.0);
        assertFalse(sprinting("sprint_release_w_01", 22));
        assertEquals(0.0, velZ("sprint_release_w_01", 22), 0.0);
        assertTrue(sprinting("sprint_release_w_01", 23), "re-press starts through the key");
        assertEquals(0.06956039771757005, velZ("sprint_release_w_01", 23), 0.0);
        assertTrue(velZ("sprint_release_w_01", 23) > velZ("sprint_release_w_01", 22),
                "re-accelerating on the re-press tick");

        // Double-tap: the first W press only arms the 7-tick window; the press on tick 4
        // (inside it) starts the sprint with no sprint key held, and it stays on.
        assertFalse(sprinting("sprint_doubletap_01", 0), "first press only arms the window");
        assertFalse(sprinting("sprint_doubletap_01", 3), "the release tick");
        assertTrue(sprinting("sprint_doubletap_01", 4), "the re-press inside the window");
        assertTrue(sprinting("sprint_doubletap_01", 33), "W held keeps sprinting");

        // Held jump against the ceiling: the head bump makes the air time 2 ticks, so the
        // 10-tick cooldown is visible. Jumps start on ticks 0/10/20/30 (peak y on the
        // jump tick), the player is grounded through ticks 2..9, 12..19, 22..29, 32..39.
        for (int tick : new int[] {0, 10, 20, 30}) {
            assertExact("jump peak posY at tick " + tick, 65.20000004768372,
                    posY("jump_repeat_ceiling_01", tick));
            assertFalse(onGround("jump_repeat_ceiling_01", tick), "airborne on the jump tick");
            assertTrue(colV("jump_repeat_ceiling_01", tick), "the head bump clamps the rise");
        }
        for (int tick : new int[] {1, 11, 21, 31}) {
            assertFalse(onGround("jump_repeat_ceiling_01", tick));
        }
        for (int tick = 2; tick <= 9; tick++) {
            assertTrue(onGround("jump_repeat_ceiling_01", tick),
                    "the held jump is blocked through the cooldown, tick " + tick);
            assertExact("floor posY at tick " + tick, 65.0,
                    posY("jump_repeat_ceiling_01", tick));
        }
    }

    private static void assertExact(String what, double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            fail(what + ": pinned " + expected + " but recorded " + actual);
        }
    }

    private static double posY(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "pos", 1);
    }

    private static double posZ(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "pos", 2);
    }

    private static double velZ(String name, int tick) throws IOException {
        return listField(recordedLine(name, tick), "vel", 2);
    }

    private static boolean sprinting(String name, int tick) throws IOException {
        return boolField(recordedLine(name, tick), "sprinting");
    }

    private static boolean colH(String name, int tick) throws IOException {
        return boolField(recordedLine(name, tick), "colH");
    }

    private static boolean colV(String name, int tick) throws IOException {
        return boolField(recordedLine(name, tick), "colV");
    }

    private static boolean onGround(String name, int tick) throws IOException {
        return boolField(recordedLine(name, tick), "onGround");
    }

    /** Recorded line of {@code name} at {@code tick} (tick:-1 is line 0). */
    private static Map<String, Object> recordedLine(String name, int tick) throws IOException {
        List<String> lines = Files.readAllLines(
                new File(stateGoldenDir(), name + ".jsonl").toPath(), StandardCharsets.UTF_8);
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

    private static File stateGoldenDir() {
        return new File(repoRoot(), "data/traces/state");
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
