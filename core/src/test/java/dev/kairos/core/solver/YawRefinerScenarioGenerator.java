package dev.kairos.core.solver;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic source generator for the task 2.7 in-game replay case.
 *
 * <p>It rebuilds the task 2.6 oak/nether-brick fence gap in memory, lets the coarse beam
 * find its loose skeleton, refines it with {@link YawRefiner}, then writes the resulting
 * one-tick-per-segment scenario. The committed scenario is generated output, not a
 * hand-copied plan: {@code YawRefinerScenarioDataTest} compares the file byte-for-byte
 * with {@link #generate()}.
 *
 * <p>The generated yaw values are the exact float bits the planner used, serialized with
 * {@link Float#toString(float)} so a JSON parse followed by a float cast round-trips.
 */
final class YawRefinerScenarioGenerator {

    static final String SCENARIO_NAME = "yaw_refiner_tight_wall_01";
    static final String SCENARIO_PATH = "data/scenarios/" + SCENARIO_NAME + ".json";
    static final String TRACE_PATH = "data/traces/replay/" + SCENARIO_NAME + ".jsonl";
    static final String RESULT_PATH = "data/replay/" + SCENARIO_NAME + ".json";

    static final double START_X = 0.9;
    static final double START_Y = 65.0;
    static final double START_Z = 0.5;
    static final double GAP_SURFACE_Y = 65.0;
    static final double TIGHT_CENTER_X = 0.984915;
    static final double TIGHT_CENTER_Z = 5.811064;
    static final double TIGHT_SIZE = 0.6;
    static final double TIGHT_MARGIN = 0.59;

    private static final double REST_VY = -0.0784000015258789;
    private static final int BEAM_BUDGET = 80;
    private static final int COARSE_BEAM_WIDTH = 512;
    private static final int MAX_CANDIDATES = 2_000_000;

    /**
     * The same world as the task 2.6 test case, but stored as DISJOINT regions. The
     * client harness verifies every region position against that region's own state, so
     * an earlier air region under a later floor would be reported as a mismatch even
     * though the union is correct. {@code YawRefinerScenarioDataTest} pins disjointness.
     */
    private static final String WORLD_JSON =
            "{\"name\":\"" + SCENARIO_NAME + "_source\","
                    + "\"start\":{\"pos\":[" + START_X + "," + START_Y + "," + START_Z + "]},"
                    + "\"blocks\":["
                    // Air: below the floors, above the fence, and the two side strips.
                    + "{\"from\":[-4,60,-2],\"to\":[4,63,26],\"id\":\"air\"},"
                    + "{\"from\":[-4,67,-2],\"to\":[4,80,26],\"id\":\"air\"},"
                    + "{\"from\":[-4,64,-2],\"to\":[-4,66,26],\"id\":\"air\"},"
                    + "{\"from\":[4,64,-2],\"to\":[4,66,26],\"id\":\"air\"},"
                    // Air: the gap itself, then the two run-ups beside the two fence posts.
                    + "{\"from\":[-3,64,1],\"to\":[3,64,4],\"id\":\"air\"},"
                    + "{\"from\":[-3,65,-2],\"to\":[3,66,1],\"id\":\"air\"},"
                    + "{\"from\":[-3,65,3],\"to\":[3,66,26],\"id\":\"air\"},"
                    + "{\"from\":[-3,65,2],\"to\":[-1,66,2],\"id\":\"air\"},"
                    + "{\"from\":[2,65,2],\"to\":[3,66,2],\"id\":\"air\"},"
                    // The two stone floors and the oak / nether-brick fence posts.
                    + "{\"from\":[-3,64,-20],\"to\":[3,64,0],"
                    + "\"id\":\"stone\",\"props\":{\"variant\":\"stone\"}},"
                    + "{\"from\":[-3,64,5],\"to\":[3,64,25],"
                    + "\"id\":\"stone\",\"props\":{\"variant\":\"stone\"}},"
                    + "{\"from\":[0,65,2],\"to\":[0,66,2],\"id\":\"fence\","
                    + "\"props\":{\"east\":\"false\",\"north\":\"false\","
                    + "\"south\":\"false\",\"west\":\"false\"}},"
                    + "{\"from\":[1,65,2],\"to\":[1,66,2],\"id\":\"nether_brick_fence\","
                    + "\"props\":{\"east\":\"false\",\"north\":\"false\","
                    + "\"south\":\"false\",\"west\":\"false\"}}"
                    + "],\"inputs\":[{\"ticks\":1}]}";

    private YawRefinerScenarioGenerator() {
    }

    static MovementSim.State start() {
        return MovementSim.initialState(
                START_X, START_Y, START_Z,
                0.0, REST_VY, 0.0,
                true, false, false, true);
    }

    static JumpTarget tightTarget() {
        double half = TIGHT_SIZE / 2.0;
        return JumpTarget.of(
                TIGHT_CENTER_X - half, TIGHT_CENTER_X + half,
                TIGHT_CENTER_Z - half, TIGHT_CENTER_Z + half,
                GAP_SURFACE_Y, TIGHT_MARGIN);
    }

    static Scenario sourceScenario() {
        return ScenarioParser.parse(WORLD_JSON);
    }

    static CollisionWorld world() {
        return CollisionWorld.of(blockData(), sourceScenario());
    }

    static BlockData blockData() {
        return blocks();
    }

    /** Solves the exact case the committed scenario must replay. */
    static JumpPlan solve() {
        MovementSim.State start = start();
        CollisionWorld world = world();
        SolveResult loose = BeamSolver.solve(start, looseTarget(), world, coarseConfig());
        if (!loose.isSolved()) {
            throw new IllegalStateException("loose skeleton did not solve: " + loose);
        }
        SolveResult refined = YawRefiner.refine(start, loose.plan, tightTarget(), world,
                refinerConfig());
        if (!refined.isSolved()) {
            throw new IllegalStateException("tight target did not refine: " + refined);
        }
        return refined.plan;
    }

    /** @return the exact LF-terminated scenario JSON that must be committed. */
    static String generate() {
        JumpPlan plan = solve();
        Scenario source = sourceScenario();
        StringBuilder out = new StringBuilder(8192);
        out.append("{\n");
        out.append("  \"name\": \"").append(SCENARIO_NAME).append("\",\n");
        out.append("  \"start\": {\"pos\": [")
                .append(Double.toString(START_X)).append(", ")
                .append(Double.toString(START_Y)).append(", ")
                .append(Double.toString(START_Z))
                .append("], \"yaw\": 0.0, \"pitch\": 0.0},\n");
        out.append("  \"blocks\": [\n");
        for (int i = 0; i < source.blocks.size(); i++) {
            ScenarioBlock block = source.blocks.get(i);
            out.append("    {\"from\": [").append(block.fromX).append(", ")
                    .append(block.fromY).append(", ").append(block.fromZ)
                    .append("], \"to\": [").append(block.toX).append(", ")
                    .append(block.toY).append(", ").append(block.toZ)
                    .append("], \"id\": \"").append(block.id).append('"');
            if (!block.props.isEmpty()) {
                out.append(", \"props\": {");
                int n = 0;
                for (java.util.Map.Entry<String, String> prop : block.props.entrySet()) {
                    if (n++ > 0) {
                        out.append(", ");
                    }
                    out.append('"').append(prop.getKey()).append("\": \"")
                            .append(prop.getValue()).append('"');
                }
                out.append('}');
            }
            out.append('}');
            if (i + 1 < source.blocks.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("  ],\n");
        out.append("  \"settleTicks\": 10,\n");
        out.append("  \"inputs\": [\n");
        int ticks = plan.inputs.size();
        for (int i = 0; i < ticks; i++) {
            TraceInput in = plan.inputs.get(i);
            out.append("    {\"ticks\": 1, \"w\": ").append(in.w)
                    .append(", \"a\": ").append(in.a)
                    .append(", \"s\": ").append(in.s)
                    .append(", \"d\": ").append(in.d)
                    .append(", \"jump\": ").append(in.jump)
                    .append(", \"sneak\": ").append(in.sneak)
                    .append(", \"sprint\": ").append(in.sprint)
                    .append(", \"yaw\": ").append(Float.toString(in.yaw))
                    .append('}');
            if (i + 1 < ticks) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    /**
     * Canonical result-file hash: one line per recorded tick, with all non-yaw inputs and
     * the exact float yaw. This is a hash of the plan as it is stored in the scenario.
     */
    static String inputSha256(Scenario scenario) {
        StringBuilder canonical = new StringBuilder(scenario.totalInputTicks() * 64);
        for (int tick = 0; tick < scenario.totalInputTicks(); tick++) {
            ScenarioInput in = scenario.inputAt(tick);
            if (in.yaw == null) {
                throw new IllegalArgumentException(
                        "scenario input tick " + tick + " has no per-tick yaw");
            }
            canonical.append(in.w).append(',').append(in.a).append(',')
                    .append(in.s).append(',').append(in.d).append(',')
                    .append(in.jump).append(',').append(in.sneak).append(',')
                    .append(in.sprint).append(',').append(Float.toString(in.yaw))
                    .append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    private static JumpTarget looseTarget() {
        return JumpTarget.of(1.0, 2.0, 6.0, 7.0, GAP_SURFACE_Y);
    }

    private static BeamSearchConfig coarseConfig() {
        return BeamSearchConfig.of(0.0f, 15.0f, COARSE_BEAM_WIDTH, BEAM_BUDGET);
    }

    private static YawRefinerConfig refinerConfig() {
        return YawRefinerConfig.of(30.0f, 1.0f, 0.01f, 20, MAX_CANDIDATES);
    }

    private static BlockData blocks() {
        File root = repoRoot();
        try {
            return BlockData.load(new File(root, "data/1.8.9/blocks.json"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot load data/1.8.9/blocks.json", e);
        }
    }

    static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return f;
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    /**
     * Writes the committed scenario. Run from {@code core}'s working directory, for
     * example after {@code :core:testClasses}:
     * {@code java -cp build/classes/java/main;build/classes/java/test ...}
     */
    public static void main(String[] args) throws IOException {
        String json = generate();
        File target = new File(repoRoot(), SCENARIO_PATH);
        Files.write(target.toPath(), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + target.getPath());
        System.out.println("sha256 " + sha256(json.getBytes(StandardCharsets.UTF_8)));
    }
}
