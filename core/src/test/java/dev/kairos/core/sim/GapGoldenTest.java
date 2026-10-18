package dev.kairos.core.sim;

import dev.kairos.core.scenario.Json;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M1 cross-check (task 0.5.3): replays the committed in-game recording of the plan that
 * {@link GapSolver} produced for the 4-block gap through {@link FlatSim} + {@link GapWorld}
 * and demands bit-exact equality (ADR 0007: max position error 0) for every recorded field,
 * then asserts the recording ends exactly where the solver said it would.
 *
 * <p>The recording is a byte-for-byte copy of the game's
 * {@code loader-1.8.9/run/kairos/traces/sprintjump_gap4_01.jsonl} (SHA256 match, LF-only).
 *
 * <p>The private JSON/trace helpers below are intentionally duplicated from
 * {@link FlatSimGoldenTest}, which is frozen and must not be modified; the JOURNAL notes
 * the duplication.
 */
class GapGoldenTest {

    /** Anchor + 31 ticks (19 sprint, 1 jump, 11 sprint), scenario {@code sprintjump_gap4_01}. */
    private static final int LINES = 32;

    /** The world every tick here resolves against; the recorded plan lands well inside its far floor. */
    private static final GapWorld WORLD = GapWorld.of(5, 4, 12);

    /** The solver bound used in task 0.5.2, reproduced here for the M1 cross-check. */
    private static final int MAX_JUMP_START_TICK = 25;

    @Test
    void matchesGap4PlanRecordedInGame() throws IOException {
        File file = golden("sprintjump_gap4_01");
        assertLfOnly(file);
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(LINES, lines.size(), "pinned line count of " + file);

        Map<String, Object> anchor = jsonObject(lines.get(0), "sprintjump_gap4_01 line 1");
        assertEquals(-1, intField(anchor, "tick"), "line 1 must be the tick:-1 anchor");
        // The anchor must equal the solver's start state (GapSolver, task 0.5.2).
        checkDouble(-1, "anchorPosX", 0.5, listField(anchor, "pos", 0));
        checkDouble(-1, "anchorPosY", 65.0, listField(anchor, "pos", 1));
        checkDouble(-1, "anchorPosZ", 0.5, listField(anchor, "pos", 2));
        checkDouble(-1, "anchorVelX", 0.0, listField(anchor, "vel", 0));
        checkDouble(-1, "anchorVelY", -0.0784000015258789, listField(anchor, "vel", 1));
        checkDouble(-1, "anchorVelZ", 0.0, listField(anchor, "vel", 2));
        checkBoolean(-1, "anchorOnGround", true, boolField(anchor, "onGround"));
        checkBoolean(-1, "anchorSprinting", false, boolField(anchor, "sprinting"));
        checkBoolean(-1, "anchorColH", false, boolField(anchor, "colH"));
        checkBoolean(-1, "anchorColV", true, boolField(anchor, "colV"));

        FlatSim.State state = FlatSim.initialState(
                listField(anchor, "pos", 0), listField(anchor, "pos", 1), listField(anchor, "pos", 2),
                listField(anchor, "vel", 0), listField(anchor, "vel", 1), listField(anchor, "vel", 2),
                boolField(anchor, "onGround"), boolField(anchor, "sprinting"),
                boolField(anchor, "colH"), boolField(anchor, "colV"));

        for (int i = 1; i < lines.size(); i++) {
            Map<String, Object> line = jsonObject(lines.get(i), "sprintjump_gap4_01 line " + (i + 1));
            int tick = intField(line, "tick");
            FlatSim.step(state, input(line), WORLD);

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

        // M1 cross-check: the recording IS the solver's plan, and it ended on the far floor.
        Optional<GapSolver.Plan> solved = GapSolver.solve(WORLD, MAX_JUMP_START_TICK);
        assertTrue(solved.isPresent(), "GapSolver must still solve the 4-block gap");
        GapSolver.Plan plan = solved.get();
        assertEquals(19, plan.sprintTicks, "planned sprint ticks: " + plan);
        assertEquals(19, plan.jumpTick, "planned jump tick: " + plan);
        assertEquals(11, plan.tailTicks, "planned tail ticks: " + plan);
        assertEquals(30, plan.landTick, "planned landing tick: " + plan);

        Map<String, Object> last = jsonObject(lines.get(lines.size() - 1), "sprintjump_gap4_01 final line");
        assertEquals(30, intField(last, "tick"), "final recorded tick must be the planned landing tick");
        checkDouble(30, "finalPosY", 65.0, listField(last, "pos", 1));
        checkDouble(30, "finalPosZ", plan.landingZ, listField(last, "pos", 2));
    }

    /**
     * Regression guard for the autocrlf CRLF issue (task 0.5.1-hygiene): committed traces
     * must be LF-only on disk (ADR 0010), and {@code readAllLines} hides CRLF, so check raw
     * bytes.
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
    private static File golden(String name) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return new File(f, "data/traces/gap/" + name + ".jsonl");
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
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
