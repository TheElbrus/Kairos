package dev.kairos.core.replay;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.Json;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.solver.JumpTarget;
import dev.kairos.core.trace.TraceInput;
import dev.kairos.core.trace.TraceLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Task 2.9 L4 replay gate: verifies the real 1.8.9 client traces for the 200-plan
 * corpus against {@link MovementSim} and derives the committed result file.
 *
 * <p>It never writes or repairs a trace. Every mismatch (encoding, line shape, anchor,
 * input, simulated state, final target) stops verification with the plan name and the
 * exact tick. The only writer is {@link #main(String[])}, which refuses to write a result
 * unless all 200 traces verify and land.
 *
 * <p>The corpus start is the game's teleport state, not the requested point:
 * {@code Entity.setPosition} builds the box around the requested position and
 * {@code Entity.resetPositionToBB} re-derives {@code posX}/{@code posZ} from the box
 * midpoint (Entity.java:756-760). {@link SampleCorpusGenerator#start(double)} already
 * applies that same double arithmetic, so the anchor is compared to it directly.
 */
public final class SampleReplayVerifier {

    public static final String CORPUS_PATH = ReplayScenarios.RELATIVE_PATH;
    public static final String TRACE_DIR = "data/traces/replay/sample";
    public static final String RESULT_PATH = "data/replay/sample_01_result.json";

    private SampleReplayVerifier() {
    }

    /** One verified trace and the facts the result file records. */
    public static final class Replay {
        public final String name;
        public final int index;
        public final MovementSim.State finalState;
        public final boolean landed;
        public final int lineCount;
        public final String traceSha256;

        Replay(String name, int index, MovementSim.State finalState, boolean landed,
                int lineCount, String traceSha256) {
            this.name = name;
            this.index = index;
            this.finalState = finalState;
            this.landed = landed;
            this.lineCount = lineCount;
            this.traceSha256 = traceSha256;
        }
    }

    /**
     * Verifies one trace against its corpus scenario.
     *
     * @throws IllegalArgumentException on any encoding, shape, input, state or target
     *                                  mismatch; the message names the plan and tick
     */
    public static Replay verify(byte[] traceBytes, Scenario scenario) {
        int index = indexOf(scenario.name);
        checkTraceEncoding(traceBytes, scenario);
        String text = new String(traceBytes, StandardCharsets.UTF_8);
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        int expectedLines = scenario.totalInputTicks() + 1;
        check(lines.length == expectedLines, scenario.name + ": trace has " + lines.length
                + " lines; expected " + expectedLines + " (anchor + "
                + scenario.totalInputTicks() + " ticks)");

        Map<String, Object> anchor = object(lines[0], scenario.name + " anchor");
        checkHeader(anchor, scenario.name + " anchor");
        check(intField(anchor, "tick") == -1, scenario.name + ": first line must be tick:-1");
        check(intField(anchor, "te") == 0, scenario.name + ": anchor te must be 0");
        check(scenario.name.equals(stringField(anchor, "scenario")),
                scenario.name + ": anchor scenario label");
        MovementSim.State anchorState = MovementSim.initialState(
                listField(anchor, "pos", 0), listField(anchor, "pos", 1),
                listField(anchor, "pos", 2),
                listField(anchor, "vel", 0), listField(anchor, "vel", 1),
                listField(anchor, "vel", 2),
                boolField(anchor, "onGround"), boolField(anchor, "sprinting"),
                boolField(anchor, "colH"), boolField(anchor, "colV"));
        checkStart(scenario, anchorState);

        // The game builds the box around the requested teleport x/z, then re-derives
        // posX/posZ as its midpoint (Entity.resetPositionToBB, Entity.java:756-760).
        // Building the box from the recorded midpoint instead changes maxX/maxZ by an
        // ULP at x=0.9 and makes the replay diverge two ticks later. Use the same
        // generator state the corpus was solved from; checkStart has already compared
        // the trace anchor against it.
        MovementSim.State state = SampleCorpusGenerator.start(scenario.startX);

        CollisionWorld world = CollisionWorld.of(
                SampleCorpusGenerator.blockData(), scenario);
        for (int i = 1; i < lines.length; i++) {
            int tick = i - 1;
            Map<String, Object> line = object(lines[i], scenario.name + " tick " + tick);
            checkHeader(line, scenario.name + " tick " + tick);
            check(intField(line, "tick") == tick, scenario.name + ": tick " + tick + " order");
            check(intField(line, "te") == i, scenario.name + ": tick " + tick + " te");
            check(scenario.name.equals(stringField(line, "scenario")),
                    scenario.name + ": tick " + tick + " scenario label");

            ScenarioInput scripted = scenario.inputAt(tick);
            check(scripted != null, scenario.name + ": no scenario input at tick " + tick);
            Map<String, Object> in = mapField(line, "in");
            checkInput(scenario, tick, in, scripted);
            MovementSim.step(state, traceInput(in), world);
            checkState(scenario, tick, state, line);
        }

        JumpTarget target = targetOf(scenario);
        boolean landed = target.isReachedBy(state);
        check(landed, scenario.name + ": final trace state is not on the target: ("
                + state.x + ", " + state.y + ", " + state.z + ")");
        return new Replay(scenario.name, index, state, landed, lines.length,
                sha256(traceBytes));
    }

    /** The target implied by the corpus scenario's goal block, checked against the recipe. */
    public static JumpTarget targetOf(Scenario scenario) {
        int index = indexOf(scenario.name);
        check(scenario.blocks.size() == 2, scenario.name + ": expected exactly 2 block regions");
        ScenarioBlock goal = scenario.blocks.get(1);
        int dy = goal.fromY - 64;
        int gap = goal.fromZ - 1;
        check(dy == SampleCorpusGenerator.heightDelta(index),
                scenario.name + ": goal dy " + dy + " is not the recipe value "
                        + SampleCorpusGenerator.heightDelta(index));
        check(gap == SampleCorpusGenerator.gap(index),
                scenario.name + ": gap " + gap + " is not the recipe value "
                        + SampleCorpusGenerator.gap(index));
        check(goal.toZ - goal.fromZ + 1 == 7,
                scenario.name + ": goal region must be 7 blocks deep");
        check(Double.compare(scenario.startX, SampleCorpusGenerator.startX(index)) == 0,
                scenario.name + ": start x is not the recipe value");
        check(Float.compare(scenario.yaw, SampleCorpusGenerator.startYaw(index)) == 0,
                scenario.name + ": start yaw is not the recipe value");
        return SampleCorpusGenerator.target(gap, dy, scenario.startX);
    }

    /**
     * Derives the result file from traces that already verified. The caller passes the
     * corpus scenarios in index order and the trace bytes for each; this method verifies
     * again, so a caller cannot hand it unverified data.
     */
    public static String resultJson(byte[] corpusBytes, List<Scenario> scenarios,
            List<byte[]> traces) {
        check(scenarios.size() == ReplayScenarios.EXPECTED_COUNT,
                "corpus must have " + ReplayScenarios.EXPECTED_COUNT + " scenarios");
        check(traces.size() == scenarios.size(), "one trace per scenario is required");
        StringBuilder out = new StringBuilder(scenarios.size() * 160);
        out.append("{\n");
        out.append("  \"corpus\": \"").append(CORPUS_PATH).append("\",\n");
        out.append("  \"corpusSha256\": \"").append(sha256(corpusBytes)).append("\",\n");
        out.append("  \"traceDir\": \"").append(TRACE_DIR).append("\",\n");
        out.append("  \"count\": ").append(scenarios.size()).append(",\n");
        out.append("  \"landed\": ").append(scenarios.size()).append(",\n");
        out.append("  \"traces\": [\n");
        for (int i = 0; i < scenarios.size(); i++) {
            Scenario scenario = scenarios.get(i);
            Replay replay = verify(traces.get(i), scenario);
            check(replay.index == i, scenario.name + ": trace order does not match the corpus");
            out.append("    {\"name\": \"").append(replay.name)
                    .append("\", \"traceSha256\": \"").append(replay.traceSha256)
                    .append("\", \"lineCount\": ").append(replay.lineCount)
                    .append(", \"outcome\": \"landed\"}");
            out.append(i + 1 < scenarios.size() ? ",\n" : "\n");
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    /** Reads the committed traces and writes the derived result file. */
    public static void main(String[] args) throws IOException {
        File root = repoRoot();
        List<String> lines = ReplayScenarios.lines(root);
        check(lines.size() == ReplayScenarios.EXPECTED_COUNT,
                "corpus must have " + ReplayScenarios.EXPECTED_COUNT + " lines");
        File corpusFile = new File(root, CORPUS_PATH);
        byte[] corpusBytes = Files.readAllBytes(corpusFile.toPath());
        List<Scenario> scenarios = new ArrayList<Scenario>(lines.size());
        List<byte[]> traces = new ArrayList<byte[]>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            scenarios.add(ScenarioParser.parse(lines.get(i)));
            File trace = new File(root, TRACE_DIR + "/" + SampleCorpusGenerator.name(i)
                    + ".jsonl");
            if (!trace.isFile()) {
                throw new IOException("in-game trace is still pending: " + trace);
            }
            traces.add(Files.readAllBytes(trace.toPath()));
        }
        String result = resultJson(corpusBytes, scenarios, traces);
        File resultFile = new File(root, RESULT_PATH);
        File parent = resultFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        Files.write(resultFile.toPath(), result.getBytes(StandardCharsets.UTF_8));
        System.out.println("verified " + traces.size() + " traces from " + TRACE_DIR);
        System.out.println("wrote " + RESULT_PATH);
        System.out.println("corpus sha256 " + sha256(corpusBytes));
    }

    static File repoRoot() {
        return SampleCorpusGenerator.repoRoot();
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // --- checks ----------------------------------------------------------------------

    private static void checkTraceEncoding(byte[] traceBytes, Scenario scenario) {
        check(traceBytes.length >= 3, scenario.name + ": trace is too short");
        check(!(traceBytes[0] == (byte) 0xEF && traceBytes[1] == (byte) 0xBB
                && traceBytes[2] == (byte) 0xBF),
                scenario.name + ": trace must not have a UTF-8 BOM");
        for (int i = 0; i < traceBytes.length; i++) {
            check(traceBytes[i] != (byte) 0x0D,
                    scenario.name + ": trace must be LF-only, found CR at byte " + i);
        }
        check(traceBytes[traceBytes.length - 1] == (byte) 0x0A,
                scenario.name + ": trace must end with LF");
    }

    /** The format version, game version and item-use flag of one trace line. */
    private static void checkHeader(Map<String, Object> line, String where) {
        check(intField(line, "v") == TraceLine.FORMAT_VERSION,
                where + ": v must be " + TraceLine.FORMAT_VERSION);
        String mc = stringField(line, "mc");
        check("1.8.9".equals(mc), where + ": mc must be 1.8.9, got " + mc);
        check(!boolField(line, "using"), where + ": using must be false");
    }

    private static void checkStart(Scenario scenario, MovementSim.State state) {
        MovementSim.State expected = SampleCorpusGenerator.start(scenario.startX);
        checkDouble(scenario.name + " anchor posX", expected.x, state.x);
        checkDouble(scenario.name + " anchor posY", expected.y, state.y);
        checkDouble(scenario.name + " anchor posZ", expected.z, state.z);
        checkDouble(scenario.name + " anchor velX", expected.vx, state.vx);
        checkDouble(scenario.name + " anchor velY", expected.vy, state.vy);
        checkDouble(scenario.name + " anchor velZ", expected.vz, state.vz);
        check(state.onGround == expected.onGround, scenario.name + ": anchor onGround");
        check(state.sprinting == expected.sprinting, scenario.name + ": anchor sprinting");
        check(state.colH == expected.colH, scenario.name + ": anchor colH");
        check(state.colV == expected.colV, scenario.name + ": anchor colV");
        check(scenario.blocks.size() == 2, scenario.name + ": expected 2 block regions");
        ScenarioBlock floor = scenario.blocks.get(0);
        check(floor.fromY == 64 && floor.toY == 64,
                scenario.name + ": start floor must be one block at y=64");
        check(floor.fromZ == -20 && floor.toZ == 0,
                scenario.name + ": start floor must span z -20..0");
    }

    private static void checkInput(Scenario scenario, int tick, Map<String, Object> in,
            ScenarioInput scripted) {
        check(intField(in, "w") == scripted.w, scenario.name + ": tick " + tick + " in.w");
        check(intField(in, "a") == scripted.a, scenario.name + ": tick " + tick + " in.a");
        check(intField(in, "s") == scripted.s, scenario.name + ": tick " + tick + " in.s");
        check(intField(in, "d") == scripted.d, scenario.name + ": tick " + tick + " in.d");
        check(intField(in, "jump") == scripted.jump,
                scenario.name + ": tick " + tick + " in.jump");
        check(intField(in, "sneak") == scripted.sneak,
                scenario.name + ": tick " + tick + " in.sneak");
        check(intField(in, "sprint") == scripted.sprint,
                scenario.name + ": tick " + tick + " in.sprint");
        float expectedYaw = scripted.yaw == null ? scenario.yaw : scripted.yaw;
        check(Float.compare(floatField(in, "yaw"), expectedYaw) == 0,
                scenario.name + ": tick " + tick + " in.yaw: trace " + floatField(in, "yaw")
                        + " but scenario says " + expectedYaw);
        check(Float.compare(floatField(in, "pitch"), scenario.pitch) == 0,
                scenario.name + ": tick " + tick + " in.pitch");

        float mf = scripted.moveForward();
        float ms = scripted.moveStrafe();
        if (scripted.sneak != 0) {
            mf = (float) ((double) mf * 0.3);
            ms = (float) ((double) ms * 0.3);
        }
        check(Float.compare(floatField(in, "mf"), mf) == 0,
                scenario.name + ": tick " + tick + " in.mf");
        check(Float.compare(floatField(in, "ms"), ms) == 0,
                scenario.name + ": tick " + tick + " in.ms");
    }

    private static void checkState(Scenario scenario, int tick, MovementSim.State state,
            Map<String, Object> line) {
        String where = scenario.name + ": tick " + tick + " ";
        checkDouble(where + "posX", listField(line, "pos", 0), state.x);
        checkDouble(where + "posY", listField(line, "pos", 1), state.y);
        checkDouble(where + "posZ", listField(line, "pos", 2), state.z);
        checkDouble(where + "velX", listField(line, "vel", 0), state.vx);
        checkDouble(where + "velY", listField(line, "vel", 1), state.vy);
        checkDouble(where + "velZ", listField(line, "vel", 2), state.vz);
        check(boolField(line, "onGround") == state.onGround, where + "onGround");
        check(boolField(line, "sprinting") == state.sprinting, where + "sprinting");
        check(boolField(line, "colH") == state.colH, where + "colH");
        check(boolField(line, "colV") == state.colV, where + "colV");
    }

    private static int indexOf(String name) {
        check(name != null && name.startsWith("sample_") && name.length() == 11,
                "scenario name must be sample_NNNN, got " + name);
        int index;
        try {
            index = Integer.parseInt(name.substring(7));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("scenario name must be sample_NNNN, got "
                    + name, e);
        }
        check(index >= 0 && index < ReplayScenarios.EXPECTED_COUNT,
                "scenario index out of range: " + name);
        check(("sample_" + SampleCorpusGenerator.name(index).substring(7)).equals(name),
                "scenario name must be zero-padded sample_NNNN, got " + name);
        return index;
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
