package dev.kairos.core.solver;

import dev.kairos.core.scenario.Json;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Verifies a real in-game trace for the task 2.7 scenario. It never writes or repairs a
 * trace: a mismatch is reported and the calling test stops. The only writer is
 * {@link #main(String[])}, which writes the derived result JSON after the trace verifies.
 */
final class YawRefinerReplayVerifier {

    private YawRefinerReplayVerifier() {
    }

    static final class Replay {
        final MovementSim.State finalState;
        final int lineCount;
        final String traceSha256;

        Replay(MovementSim.State finalState, int lineCount, String traceSha256) {
            this.finalState = finalState;
            this.lineCount = lineCount;
            this.traceSha256 = traceSha256;
        }
    }

    static Replay verify(byte[] traceBytes, Scenario scenario) {
        check(traceBytes.length >= 3, "trace is too short");
        check(!(traceBytes[0] == (byte) 0xEF && traceBytes[1] == (byte) 0xBB
                && traceBytes[2] == (byte) 0xBF), "trace must not have a UTF-8 BOM");
        for (int i = 0; i < traceBytes.length; i++) {
            check(traceBytes[i] != (byte) 0x0D,
                    "trace must be LF-only, found CR at byte " + i);
        }
        check(traceBytes[traceBytes.length - 1] == (byte) 0x0A,
                "trace must end with LF");

        String text = new String(traceBytes, StandardCharsets.UTF_8);
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        int expectedLines = scenario.totalInputTicks() + 1;
        check(lines.length == expectedLines, "trace has " + lines.length
                + " lines; expected " + expectedLines + " (anchor + "
                + scenario.totalInputTicks() + " ticks)");

        Map<String, Object> anchor = object(lines[0], "anchor");
        check(intField(anchor, "tick") == -1, "first line must be tick:-1");
        check(intField(anchor, "te") == 0, "anchor te must be 0");
        check(YawRefinerScenarioGenerator.SCENARIO_NAME.equals(
                stringField(anchor, "scenario")), "anchor scenario label");
        MovementSim.State state = MovementSim.initialState(
                listField(anchor, "pos", 0), listField(anchor, "pos", 1),
                listField(anchor, "pos", 2),
                listField(anchor, "vel", 0), listField(anchor, "vel", 1),
                listField(anchor, "vel", 2),
                boolField(anchor, "onGround"), boolField(anchor, "sprinting"),
                boolField(anchor, "colH"), boolField(anchor, "colV"));
        checkState("anchor", state);
        checkStart(anchor);
        rebuildTeleportBox(state);

        CollisionWorld world = CollisionWorld.of(
                YawRefinerScenarioGenerator.blockData(), scenario);
        for (int i = 1; i < lines.length; i++) {
            int tick = i - 1;
            Map<String, Object> line = object(lines[i], "tick " + tick);
            check(intField(line, "tick") == tick, "tick " + tick + " order");
            check(intField(line, "te") == i, "tick " + tick + " te");
            check(YawRefinerScenarioGenerator.SCENARIO_NAME.equals(
                    stringField(line, "scenario")), "tick " + tick + " scenario label");

            ScenarioInput scripted = scenario.inputAt(tick);
            check(scripted != null, "no scenario input at tick " + tick);
            Map<String, Object> in = mapField(line, "in");
            checkInput(tick, in, scripted, scenario);
            MovementSim.step(state, traceInput(in), world);
            checkState(tick, state, line);
        }

        check(YawRefinerScenarioGenerator.tightTarget().isReachedBy(state),
                "final trace state is not on the tight target: (" + state.x + ", "
                        + state.y + ", " + state.z + ")");
        return new Replay(state, lines.length,
                YawRefinerScenarioGenerator.sha256(traceBytes));
    }

    /** Builds the result JSON only from a verified trace and the generated plan data. */
    static String resultJson(byte[] traceBytes, Scenario scenario) {
        Replay replay = verify(traceBytes, scenario);
        JumpTarget target = YawRefinerScenarioGenerator.tightTarget();
        return "{\n"
                + "  \"scenario\": \"" + YawRefinerScenarioGenerator.SCENARIO_NAME + "\",\n"
                + "  \"target\": {\"minX\": " + Double.toString(target.minX)
                + ", \"maxX\": " + Double.toString(target.maxX)
                + ", \"minZ\": " + Double.toString(target.minZ)
                + ", \"maxZ\": " + Double.toString(target.maxZ)
                + ", \"surfaceY\": " + Double.toString(target.surfaceY)
                + ", \"edgeMargin\": " + Double.toString(target.edgeMargin) + "},\n"
                + "  \"inputSha256\": \""
                + YawRefinerScenarioGenerator.inputSha256(scenario) + "\",\n"
                + "  \"trace\": \"" + YawRefinerScenarioGenerator.TRACE_PATH + "\",\n"
                + "  \"traceSha256\": \"" + replay.traceSha256 + "\",\n"
                + "  \"lineCount\": " + replay.lineCount + ",\n"
                + "  \"outcome\": \"landed\"\n"
                + "}\n";
    }

    /** Reads the real trace and writes its derived result file. */
    public static void main(String[] args) throws IOException {
        File root = YawRefinerScenarioGenerator.repoRoot();
        File scenarioFile = new File(root, YawRefinerScenarioGenerator.SCENARIO_PATH);
        File traceFile = new File(root, YawRefinerScenarioGenerator.TRACE_PATH);
        if (!traceFile.isFile()) {
            throw new IOException("in-game trace is still pending: " + traceFile);
        }
        byte[] traceBytes = Files.readAllBytes(traceFile.toPath());
        byte[] scenarioBytes = Files.readAllBytes(scenarioFile.toPath());
        String generated = YawRefinerScenarioGenerator.generate();
        check(java.util.Arrays.equals(generated.getBytes(StandardCharsets.UTF_8),
                scenarioBytes), "scenario file is not the generator output");
        Scenario scenario = ScenarioParser.parse(
                new String(scenarioBytes, StandardCharsets.UTF_8));
        String result = resultJson(traceBytes, scenario);
        File resultFile = new File(root, YawRefinerScenarioGenerator.RESULT_PATH);
        File parent = resultFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        Files.write(resultFile.toPath(), result.getBytes(StandardCharsets.UTF_8));
        System.out.println("verified " + traceFile.getPath());
        System.out.println("wrote " + resultFile.getPath());
        System.out.println("trace sha256 " + YawRefinerScenarioGenerator.sha256(traceBytes));
    }

    private static void checkInput(int tick, Map<String, Object> in,
            ScenarioInput scripted, Scenario scenario) {
        check(intField(in, "w") == scripted.w, "tick " + tick + " in.w");
        check(intField(in, "a") == scripted.a, "tick " + tick + " in.a");
        check(intField(in, "s") == scripted.s, "tick " + tick + " in.s");
        check(intField(in, "d") == scripted.d, "tick " + tick + " in.d");
        check(intField(in, "jump") == scripted.jump, "tick " + tick + " in.jump");
        check(intField(in, "sneak") == scripted.sneak, "tick " + tick + " in.sneak");
        check(intField(in, "sprint") == scripted.sprint, "tick " + tick + " in.sprint");
        float expectedYaw = scripted.yaw == null ? scenario.yaw : scripted.yaw;
        check(Float.compare(floatField(in, "yaw"), expectedYaw) == 0,
                "tick " + tick + " in.yaw: trace " + floatField(in, "yaw")
                        + " but scenario says " + expectedYaw);
        check(Float.compare(floatField(in, "pitch"), scenario.pitch) == 0,
                "tick " + tick + " in.pitch");

        float mf = scripted.moveForward();
        float ms = scripted.moveStrafe();
        if (scripted.sneak != 0) {
            mf = (float) ((double) mf * 0.3);
            ms = (float) ((double) ms * 0.3);
        }
        check(Float.compare(floatField(in, "mf"), mf) == 0, "tick " + tick + " in.mf");
        check(Float.compare(floatField(in, "ms"), ms) == 0, "tick " + tick + " in.ms");
    }

    private static void checkStart(Map<String, Object> anchor) {
        MovementSim.State expected = YawRefinerScenarioGenerator.start();
        // The harness teleports to the requested point, but the game derives the anchor
        // from its own player box: Entity.setPosition (Entity.java:392-398) builds the box
        // around the point with half-width 0.3f (EntityPlayer.setSize(0.6f, 1.8f),
        // EntityPlayer.java:501), and Entity.resetPositionToBB (Entity.java:756-760)
        // re-derives posX/posZ from the box midpoints. For x = 0.9 that re-centering is
        // one ULP low, so the anchor is the requested start, not placement drift.
        checkDouble("anchor posX", recentered(expected.x), listField(anchor, "pos", 0));
        checkDouble("anchor posY", expected.y, listField(anchor, "pos", 1));
        checkDouble("anchor posZ", recentered(expected.z), listField(anchor, "pos", 2));
        checkDouble("anchor velX", expected.vx, listField(anchor, "vel", 0));
        checkDouble("anchor velY", expected.vy, listField(anchor, "vel", 1));
        checkDouble("anchor velZ", expected.vz, listField(anchor, "vel", 2));
        check(boolField(anchor, "onGround") == expected.onGround, "anchor onGround");
        check(boolField(anchor, "sprinting") == expected.sprinting, "anchor sprinting");
        check(boolField(anchor, "colH") == expected.colH, "anchor colH");
        check(boolField(anchor, "colV") == expected.colV, "anchor colV");
    }

    /**
     * The trace records only the box midpoint, but the game built the box from the
     * requested scenario start when it teleported (Entity.setPosition, Entity.java:392-398)
     * and then re-derived posX/posZ from that box (:756-760). Rebuilding the box from the
     * recorded midpoint is off by an ULP when the midpoint rounds (x = 0.9 does), and that
     * ULP reaches the collision sweep a few ticks later. So the replay starts from the
     * teleport box, not from a box reconstructed around the recorded midpoint.
     */
    private static void rebuildTeleportBox(MovementSim.State state) {
        MovementSim.State start = YawRefinerScenarioGenerator.start();
        float half = 0.6f / 2.0f;
        state.minX = start.x - (double) half;
        state.maxX = start.x + (double) half;
        state.minZ = start.z - (double) half;
        state.maxZ = start.z + (double) half;
    }

    /**
     * {@code Entity.resetPositionToBB} midpoint for an entity placed at {@code requested}
     * with the player's 0.6f/2 half-width, evaluated in the game's double order.
     */
    private static double recentered(double requested) {
        float half = 0.6f / 2.0f;
        double min = requested - (double) half;
        double max = requested + (double) half;
        return (min + max) / 2.0;
    }

    private static void checkState(String where, MovementSim.State state) {
        check(!Double.isNaN(state.x) && !Double.isInfinite(state.x), where + " posX");
        check(!Double.isNaN(state.y) && !Double.isInfinite(state.y), where + " posY");
        check(!Double.isNaN(state.z) && !Double.isInfinite(state.z), where + " posZ");
    }

    private static void checkState(int tick, MovementSim.State state,
            Map<String, Object> line) {
        checkDouble("tick " + tick + " posX", listField(line, "pos", 0), state.x);
        checkDouble("tick " + tick + " posY", listField(line, "pos", 1), state.y);
        checkDouble("tick " + tick + " posZ", listField(line, "pos", 2), state.z);
        checkDouble("tick " + tick + " velX", listField(line, "vel", 0), state.vx);
        checkDouble("tick " + tick + " velY", listField(line, "vel", 1), state.vy);
        checkDouble("tick " + tick + " velZ", listField(line, "vel", 2), state.vz);
        check(boolField(line, "onGround") == state.onGround,
                "tick " + tick + " onGround");
        check(boolField(line, "sprinting") == state.sprinting,
                "tick " + tick + " sprinting");
        check(boolField(line, "colH") == state.colH, "tick " + tick + " colH");
        check(boolField(line, "colV") == state.colV, "tick " + tick + " colV");
    }

    private static TraceInput traceInput(Map<String, Object> in) {
        return new TraceInput(
                intField(in, "w"), intField(in, "a"), intField(in, "s"),
                intField(in, "d"), intField(in, "jump"), intField(in, "sneak"),
                intField(in, "sprint"), floatField(in, "yaw"), floatField(in, "pitch"),
                floatField(in, "mf"), floatField(in, "ms"));
    }

    private static void checkDouble(String where, double expected, double actual) {
        check(Double.compare(expected, actual) == 0,
                where + ": expected " + expected + " but got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Map<String, Object> object(String line, String where) {
        Object parsed = Json.parse(line);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException(where + " must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    private static Map<String, Object> mapField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("field \"" + key + "\" must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) value;
        return nested;
    }

    private static double listField(Map<String, Object> map, String key, int index) {
        Object value = map.get(key);
        if (!(value instanceof List) || ((List<?>) value).size() <= index) {
            throw new IllegalArgumentException("field \"" + key + "\" must be an array");
        }
        Object number = ((List<?>) value).get(index);
        if (!(number instanceof Double)) {
            throw new IllegalArgumentException("field \"" + key + "\"[" + index
                    + "] must be a number");
        }
        return (Double) number;
    }

    private static double doubleField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException("field \"" + key + "\" must be a number");
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
            throw new IllegalArgumentException("field \"" + key + "\" must be a boolean");
        }
        return (Boolean) value;
    }

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("field \"" + key + "\" must be a string");
        }
        return (String) value;
    }
}
