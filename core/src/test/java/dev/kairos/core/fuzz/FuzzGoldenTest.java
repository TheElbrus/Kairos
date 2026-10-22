package dev.kairos.core.fuzz;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.Json;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 1.7.2 Part A: the 1000 live-recorded fuzz traces replay bit-exact against
 * {@link MovementSim} on their own scenario collision world.
 *
 * <p>Everything is checked on every trace: the sealed corpus mapping (same index and
 * name), raw LF-only bytes, 101 lines (the tick:-1 anchor plus ticks 0..99), the anchor
 * and {@code te} sequences, the recorded label and scripted yaw/pitch/jump/sneak/sprint,
 * and then all ten simulated fields per tick with {@code Double.compare} or exact
 * booleans. No sample, filter, skip or tolerance: the first divergence fails with the
 * scenario name, tick, field, trace value and sim value.
 */
class FuzzGoldenTest {

    private static final int TICKS = 100;
    private static final int LINES = TICKS + 1;

    @Test
    void matchesEveryFuzzTrace() throws IOException {
        File root = repoRoot();
        List<String> corpus = FuzzScenarios.lines(workDir());
        assertEquals(FuzzScenarios.EXPECTED_COUNT, corpus.size(),
                "sealed fuzz corpus line count");
        BlockData blocks = BlockData.load(new File(root, "data/1.8.9/blocks.json"));

        File dir = new File(root, "data/traces/fuzz");
        File[] files = dir.listFiles();
        if (files == null) {
            fail("missing fuzz trace directory: " + dir);
        }
        Arrays.sort(files);
        if (files.length != FuzzScenarios.EXPECTED_COUNT) {
            fail("fuzz trace directory must contain exactly " + FuzzScenarios.EXPECTED_COUNT
                    + " files, found " + files.length + ": " + names(files));
        }

        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            String expectedName = "fuzz_" + pad4(i);
            assertEquals(expectedName + ".jsonl", file.getName(),
                    "fuzz trace file " + (i + 1) + " must be " + expectedName + ".jsonl");
            Scenario scenario = ScenarioParser.parse(corpus.get(i));
            assertEquals(expectedName, scenario.name,
                    "corpus line " + (i + 1) + " must be " + expectedName);
            assertEquals(TICKS, scenario.totalInputTicks(),
                    scenario.name + " must record exactly " + TICKS + " ticks");
            replay(file, scenario, blocks);
        }
    }

    private static void replay(File file, Scenario scenario, BlockData blocks)
            throws IOException {
        String name = scenario.name;
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertLfOnly(name, file, bytes);
        if (bytes.length == 0 || bytes[bytes.length - 1] != 0x0A) {
            fail(name + ": trace must end with LF: " + file);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        assertEquals(LINES, lines.length,
                name + ": trace must have " + LINES + " lines (tick:-1 anchor + " + TICKS
                        + " ticks)");

        Map<String, Object> anchor = jsonObject(lines[0], name + " anchor");
        assertEquals(-1, intField(anchor, "tick"), name + " line 1 must be the tick:-1 anchor");
        assertEquals(0, intField(anchor, "te"), name + " anchor te");
        assertEquals(name, stringField(anchor, "scenario"), name + " anchor scenario label");
        MovementSim.State state = MovementSim.initialState(
                listField(anchor, "pos", 0), listField(anchor, "pos", 1),
                listField(anchor, "pos", 2),
                listField(anchor, "vel", 0), listField(anchor, "vel", 1),
                listField(anchor, "vel", 2),
                boolField(anchor, "onGround"), boolField(anchor, "sprinting"),
                boolField(anchor, "colH"), boolField(anchor, "colV"));
        CollisionWorld world = CollisionWorld.of(blocks, scenario);

        for (int i = 1; i < lines.length; i++) {
            int tick = i - 1;
            Map<String, Object> line = jsonObject(lines[i], name + " line " + (i + 1));
            assertEquals(tick, intField(line, "tick"), name + " tick order");
            assertEquals(i, intField(line, "te"), name + " tick " + tick + " te");
            assertEquals(name, stringField(line, "scenario"),
                    name + " tick " + tick + " scenario label");

            Map<String, Object> in = mapField(line, "in");
            ScenarioInput scripted = scenario.inputAt(tick);
            if (scripted == null) {
                fail(name + " tick " + tick + " has no scenario input segment");
            }
            checkInput(name, tick, "jump", intField(in, "jump"), scripted.jump);
            checkInput(name, tick, "sneak", intField(in, "sneak"), scripted.sneak);
            checkInput(name, tick, "sprint", intField(in, "sprint"), scripted.sprint);
            checkFloat(name, tick, "in.yaw", floatField(in, "yaw"), scenario.yaw);
            checkFloat(name, tick, "in.pitch", floatField(in, "pitch"), scenario.pitch);

            MovementSim.step(state, traceInput(in), world);
            checkDouble(name, tick, "posX", listField(line, "pos", 0), state.x);
            checkDouble(name, tick, "posY", listField(line, "pos", 1), state.y);
            checkDouble(name, tick, "posZ", listField(line, "pos", 2), state.z);
            checkDouble(name, tick, "velX", listField(line, "vel", 0), state.vx);
            checkDouble(name, tick, "velY", listField(line, "vel", 1), state.vy);
            checkDouble(name, tick, "velZ", listField(line, "vel", 2), state.vz);
            checkBoolean(name, tick, "onGround", boolField(line, "onGround"), state.onGround);
            checkBoolean(name, tick, "sprinting", boolField(line, "sprinting"), state.sprinting);
            checkBoolean(name, tick, "colH", boolField(line, "colH"), state.colH);
            checkBoolean(name, tick, "colV", boolField(line, "colV"), state.colV);
        }
    }

    private static void checkInput(String name, int tick, String field, int recorded,
            int scripted) {
        if (recorded != scripted) {
            fail(name + " tick " + tick + ", field in." + field + ": expected (trace) "
                    + recorded + " but scenario says " + scripted);
        }
    }

    private static void checkFloat(String name, int tick, String field, float recorded,
            float scripted) {
        if (Float.compare(recorded, scripted) != 0) {
            fail(name + " tick " + tick + ", field " + field + ": expected (trace) "
                    + recorded + " but scenario says " + scripted);
        }
    }

    private static void checkDouble(String name, int tick, String field, double recorded,
            double simulated) {
        if (Double.compare(recorded, simulated) != 0) {
            fail(name + " tick " + tick + ", field " + field + ": expected (trace) "
                    + recorded + " but sim produced " + simulated);
        }
    }

    private static void checkBoolean(String name, int tick, String field, boolean recorded,
            boolean simulated) {
        if (recorded != simulated) {
            fail(name + " tick " + tick + ", field " + field + ": expected (trace) "
                    + recorded + " but sim produced " + simulated);
        }
    }

    private static void assertLfOnly(String name, File file, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0x0D) {
                fail(name + ": fuzz trace must be LF-only (ADR 0010): " + file
                        + " has a 0x0D byte at offset " + i);
            }
        }
    }

    private static String names(File[] files) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < files.length && i < 10; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(files[i].getName());
        }
        if (files.length > 10) {
            out.append(", ...");
        }
        return out.toString();
    }

    private static String pad4(int value) {
        StringBuilder out = new StringBuilder(4);
        if (value < 10) {
            out.append('0');
        }
        if (value < 100) {
            out.append('0');
        }
        if (value < 1000) {
            out.append('0');
        }
        return out.append(value).toString();
    }

    private static File repoRoot() {
        File dir = workDir().getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return f;
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static File workDir() {
        return new File(System.getProperty("user.dir"));
    }

    private static TraceInput traceInput(Map<String, Object> in) {
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

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            fail("field \"" + key + "\" must be a string, got " + value);
        }
        return (String) value;
    }
}
